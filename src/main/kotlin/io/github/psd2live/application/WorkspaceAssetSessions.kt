package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A captured catalog resolves immutable records and collects all candidate writes privately. */
private class AssetCandidate(private val projectId: String, private val store: WorkspaceStore,
                             val baseline: WorkspaceAssetCatalog) : WorkspaceAssetRepository {
    val assets = linkedMapOf<String, WorkspacePngAsset>()
    val workflow = linkedMapOf<String, JsonObject>()
    val spatial = linkedMapOf<String, WorkspaceViewSpatialMetadata>()
    val catalog get() = WorkspaceAssetCatalog(baseline.assets + assets.keys, baseline.workflow + workflow.keys)
    private fun checkProject(id: String) = require(id == projectId) { "Asset operation targets another project" }
    override fun loadWorkflow(projectId: String, id: String): JsonObject {
        checkProject(projectId)
        return workflow[id] ?: run {
            require(id in baseline.workflow) { "Workflow record not found: $id" }
            store.loadWorkflow(projectId, id)
        }
    }
    override fun loadAsset(projectId: String, assetId: String): WorkspacePngAsset? {
        checkProject(projectId)
        return assets[assetId] ?: if (assetId in baseline.assets) store.loadAsset(projectId, assetId) else null
    }
    override fun registrationsForAsset(projectId: String, assetId: String): List<JsonObject> {
        checkProject(projectId)
        return (baseline.workflow + workflow.keys).sorted().map { loadWorkflow(projectId, it) }
            .filter { it["kind"]?.jsonPrimitive?.content == "registration" && it["asset_id"]?.jsonPrimitive?.content == assetId }
    }
    override fun loadSpatial(projectId: String, viewId: String): WorkspaceViewSpatialMetadata? {
        checkProject(projectId)
        return spatial[viewId] ?: store.loadSpatial(projectId, viewId)
    }
    override fun persistWorkflow(projectId: String, id: String, value: JsonObject) { checkProject(projectId); workflow[id] = value }
    override fun persistAsset(projectId: String, asset: WorkspacePngAsset) { checkProject(projectId); assets[asset.public.id] = asset }
    override fun persistSpatial(projectId: String, viewId: String, spatial: WorkspaceViewSpatialMetadata) { checkProject(projectId); this.spatial[viewId] = spatial }
}

/** Asset preparation, import, registration and reprocessing share auxiliary CAS and no Rig history. */
internal class WorkspaceAssetSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    companion object { val supported = setOf("asset_prepare_reference", "asset_import_png", "asset_register", "asset_reprocess") }
    private fun candidate(capture: WorkspaceCapture<RigPreviewModel>, store: WorkspaceStore) = AssetCandidate(capture.projectId, store,
        WorkspaceAssetCatalog.read(capture.auxiliary) ?: store.existingAssetCatalog(capture.projectId))

    fun workflow(capture: WorkspaceCapture<RigPreviewModel>, store: WorkspaceStore, document: WorkspaceDocument = capture.document): WorkspaceAssetWorkflow =
        WorkspaceAssetWorkflow(capture.projectId, capture.revision, document, candidate(capture, store), WorkspacePngAssetStore())

    fun captureWorkflow(store: WorkspaceStore): WorkspaceAssetWorkflow = runtime.withCapture { workflow(it, store) }

    suspend fun execute(projectId: String, state: String, store: WorkspaceStore, operation: String, arguments: JsonObject,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>) -> Unit = {}): WorkspaceWorkflowResult {
        val context = currentCoroutineContext()
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        require(operation in supported) { "Unknown asset operation: $operation" }
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing asset candidate")
        val candidate = withContext(Dispatchers.IO) { candidate(before, store) }
        val assets = WorkspacePngAssetStore { context.ensureActive() }
        val api = WorkspaceAssetWorkflow(projectId, before.revision, before.document, candidate, assets) { context.ensureActive() }
        val prepared = withContext(Dispatchers.Default) {
            when (operation) {
                "asset_prepare_reference" -> {
                    val id = arguments.text("layer_id")
                    val rig = before.model.rig
                    val parent = rig.puppet.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == id }?.parentDeformerId?.raw
                    api.prepare(if (parent == null) arguments else JsonObject(arguments + ("source_parent_id" to JsonPrimitive(parent))))
                }
                "asset_register" -> api.register(arguments)
                "asset_reprocess" -> api.reprocess(arguments)
                else -> import(before, arguments, candidate, assets)
            }
        }
        context.ensureActive()
        val changed = candidate.catalog != candidate.baseline
        val auxiliary = if (!changed) before.auxiliary else JsonObject(before.auxiliary + ("assetCatalog" to candidate.catalog.encode()))
        context[WorkspaceJobContext]?.progress(0.6f, "Staging immutable asset records")
        var stage: WorkspaceAssetStage? = null
        var failure: Throwable? = null
        try {
            if (changed) withContext(Dispatchers.IO) {
                stage = store.stageAssets(projectId, candidate.assets.values, candidate.workflow, candidate.spatial) { context.ensureActive() }
            }
            context.ensureActive()
            context[WorkspaceJobContext]?.progress(0.95f, "Committing asset catalog")
            val committed = runtime.updateAuxiliary(projectId, state, auxiliary) { captured, _ ->
                context.ensureActive()
                requireNotNull(stage).publish({ context.ensureActive() }) { beforeCommit(captured) }
            }
            val result = prepared.copy(metadata = JsonObject(prepared.metadata + mapOf("state" to JsonPrimitive(committed.state),
                "project_id" to JsonPrimitive(committed.projectId), "history_node_id" to JsonPrimitive(committed.historyHead))))
            // Preserve the exact result before dispatcher cancellation or host refresh can surface.
            context[WorkspaceAssetJobExecution]?.committed(operation, result)
            return result
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            try { withContext(NonCancellable + Dispatchers.IO) { stage?.close() } }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private suspend fun import(before: WorkspaceCapture<RigPreviewModel>, a: JsonObject, candidate: AssetCandidate,
                               assets: WorkspacePngAssetStore): WorkspaceWorkflowResult {
        val png = withContext(Dispatchers.IO) {
            a["png_path"]?.jsonPrimitive?.content?.let { name ->
                require(a["png_base64"] == null) { "Provide png_path or png_base64, not both" }
                val file = Path.of(name)
                require(file.isAbsolute && Files.isRegularFile(file)) { "png_path must be an existing absolute file path" }
                require(Files.size(file) in 8..(64L * 1024 * 1024)) { "PNG file exceeds the import budget" }
                Files.readAllBytes(file)
            } ?: decodePngBase64(a.text("png_base64")).also { require(it.size in 8..(64 * 1024 * 1024)) { "PNG exceeds the import budget" } }
        }
        val reference = a.text("reference_id")
        val record = candidate.loadWorkflow(before.projectId, reference)
        require(record.text("kind") == "reference") { "Expected reference record" }
        require(record.number("canvas_width").toInt() == before.document.source.widthPx &&
            record.number("canvas_height").toInt() == before.document.source.heightPx) { "Reference canvas size changed; prepare a new reference" }
        val spatial = requireNotNull(candidate.loadSpatial(before.projectId, reference)) { "Spatial reference not found: $reference" }
        val rect = a["source_pixel_rect"]?.jsonObject?.let { value ->
            WorkspacePixelRect(value.getValue("left").jsonPrimitive.int, value.getValue("top").jsonPrimitive.int,
                value.getValue("width").jsonPrimitive.int, value.getValue("height").jsonPrimitive.int)
        }
        val imported = assets.import(WorkspacePngImportRequest(png, reference, rect,
            a["solid_background"]?.jsonPrimitive?.content, a["background_tolerance"]?.jsonPrimitive?.int ?: 16,
            a["require_transparency"]?.jsonPrimitive?.boolean ?: false, reference, a["processing"]?.jsonObject ?: JsonObject(emptyMap())), spatial)
        candidate.persistAsset(before.projectId, assets.require(imported.id))
        return WorkspaceWorkflowResult(imported.toJson())
    }
}

internal class WorkspaceAssetJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceAssetJobExecution>
    fun committed(id: String, result: WorkspaceWorkflowResult) {
        if (id == operation) completion.committed(WorkspaceOperationOutput(result.metadata, result.images))
    }
}
