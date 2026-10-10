package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Frozen immutable resources are inputs to document candidates, never auxiliary writes. */
internal object WorkspaceAssetLayerEdits {
    val supported = setOf("layer_add_from_asset", "layer_set_placement", "layer_finalize_placement")

    fun request(a: JsonObject, state: String = ""): WorkspaceAddLayerRequest {
        val insertionValue = a["insertion"]?.jsonObject
        val anchor = insertionValue?.get("reference_layer_id")?.jsonPrimitive?.content
        val mode = if (insertionValue == null) "top" else insertionValue.text("mode")
        val insertion = when (mode) {
            "top" -> WorkspaceLayerInsertion.Top
            "bottom" -> WorkspaceLayerInsertion.Bottom
            "above" -> WorkspaceLayerInsertion.Above(requireNotNull(anchor) { "reference_layer_id is required" })
            "below" -> WorkspaceLayerInsertion.Below(requireNotNull(anchor) { "reference_layer_id is required" })
            else -> throw IllegalArgumentException("Unknown layer insertion")
        }
        return WorkspaceAddLayerRequest(a.text("asset_id"), state, a.text("name"), a["layer_id"]?.jsonPrimitive?.content,
            a["group_path"]?.jsonPrimitive?.content.orEmpty(), insertion, a["semantic_tag"]?.jsonPrimitive?.content ?: "unknown",
            a["side"]?.jsonPrimitive?.content ?: "none", a["visible"]?.jsonPrimitive?.boolean ?: true,
            a["opacity"]?.jsonPrimitive?.float ?: 1f, a["trim_transparent"]?.jsonPrimitive?.boolean ?: true,
            a["registration_id"]?.jsonPrimitive?.content, a["parent_deformer_id"]?.jsonPrimitive?.content)
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation,
              resources: WorkspaceAssetWorkflow?, checkCancelled: () -> Unit = {}): WorkspaceDocument = when (operation.operation) {
        "layer_add_from_asset" -> add(document, model, request(operation.request), requireNotNull(resources) { "Asset resources are required" }, checkCancelled)
        "layer_set_placement" -> place(document, model, operation.request.text("layer_id"), operation.request.text("registration_id"),
            requireNotNull(resources) { "Asset resources are required" }, checkCancelled)
        "layer_finalize_placement" -> {
            val id = operation.request.text("layer_id")
            val entry = document.rigEdits.assetLayers[id] ?: error("Layer has no registered placement")
            if (entry.flag("placement_finalized")) document else {
                editable(document, model, id)
                document.copy(rigEdits = document.rigEdits.copy(assetLayers = document.rigEdits.assetLayers +
                    (id to JsonObject(entry + ("placement_finalized" to JsonPrimitive(true))))))
            }
        }
        else -> throw IllegalArgumentException("Unknown asset layer operation")
    }

    private fun placement(document: WorkspaceDocument, layerId: String, asset: WorkspacePngAsset,
                          registration: JsonObject, reference: JsonObject): WorkspaceDocument = document.copy(
        rigEdits = document.rigEdits.copy(assetLayers = document.rigEdits.assetLayers + (layerId to buildJsonObject {
            put("registration_id", registration.text("id")); put("asset_id", asset.public.id)
            put("reference_id", reference.text("id")); put("placement_finalized", false)
        }), calibrationLayerIds = document.rigEdits.calibrationLayerIds.ifEmpty { reference.strings("calibration_layer_ids").toSet() }))

    private fun add(document: WorkspaceDocument, model: RigPreviewModel, request: WorkspaceAddLayerRequest,
                    resources: WorkspaceAssetWorkflow, checkCancelled: () -> Unit): WorkspaceDocument {
        val asset = resources.asset(request.assetId)
        require(asset.public.details["registration_required"] == null || request.registrationId != null) { "Generated asset needs asset_register before adding a layer" }
        val registration = request.registrationId?.let { resources.record(it, "registration") }
        require(registration?.flag("orientation_conflict") != true) { "Orientation conflict; inspect anchors before adding layer" }
        val reference = registration?.let { resources.record(it.text("reference_id"), "reference") }
        val parent = request.parentDeformerId ?: reference?.get("source_parent_id")?.jsonPrimitive?.content
        require(parent == null || model.rig.puppet.deformers.any { it.id.raw == parent }) { "Reference parent no longer exists" }
        val (added, id) = document.addLayer(registration?.let { placedAsset(asset, it) } ?: asset, request.copy(parentDeformerId = parent), checkCancelled)
        val placed = if (registration == null) added else placement(added, id, asset, registration, requireNotNull(reference))
        // Like a file import: the layer is the user's, with its own mesh under its parent, which the generators never make.
        return WorkspaceLayerInsertionEdits.materialize(placed, model, setOf(id), parent, checkCancelled)
    }

    private fun place(document: WorkspaceDocument, model: RigPreviewModel, id: String, registrationId: String,
                      resources: WorkspaceAssetWorkflow, checkCancelled: () -> Unit): WorkspaceDocument {
        val registration = resources.record(registrationId, "registration")
        require(!registration.flag("orientation_conflict")) { "Anchor orientation conflict; correct anchors or explicitly mirror before placement" }
        val source = document.source.layers.singleOrNull { it.id.raw == id } as? WorkspaceSourceMetadata
            ?: error("Only imported asset layers support placement editing")
        require(source.sourceAssetId == registration.text("asset_id")) { "Registration belongs to a different asset" }
        if (document.rigEdits.assetLayers[id]?.get("registration_id")?.jsonPrimitive?.content == registrationId) return document
        editable(document, model, id)
        val asset = resources.asset(registration.text("asset_id"))
        val candidate = placement(document.replacePlacedLayer(id, placedAsset(asset, registration), checkCancelled), id, asset, registration,
            resources.record(registration.text("reference_id"), "reference"))
        // The layer's own mesh is made again over the new placement, under the parent it hangs from.
        val parent = model.rig.puppet.drawables.firstOrNull { model.rig.layerIdByDrawableId[it.id.raw] == id }?.parentDeformerId?.raw
            ?: document.parentOverrides[id]
        return WorkspaceLayerInsertionEdits.materialize(candidate, model, setOf(id), parent, checkCancelled)
    }

    private fun editable(document: WorkspaceDocument, model: RigPreviewModel, id: String) {
        require(document.rigEdits.assetLayers[id]?.flag("placement_finalized") != true) { "Placement is finalized" }
        WorkspaceLayerPlacementGuard.check(document, model, id)
    }

    fun validate(before: WorkspaceDocument, document: WorkspaceDocument, model: RigPreviewModel) {
        val prior = before.source.layers.mapTo(HashSet()) { it.id.raw }
        val addedAssets = document.source.layers.filter { it.id.raw !in prior && (it as? WorkspaceSourceMetadata)?.sourceAssetId != null }
            .mapTo(HashSet()) { it.id.raw }
        val changedPlacements = document.rigEdits.assetLayers.filter { (id, record) -> before.rigEdits.assetLayers[id] != record }.keys
        validateRegisteredNeutral(model, addedAssets + changedPlacements)
    }

}

internal class WorkspaceAssetLayerCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                          resources: (WorkspaceCapture<RigPreviewModel>) -> WorkspaceAssetWorkflow) {
    private val commands = WorkspaceDocumentCommands(runtime, assetResources = resources)
    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
                        author: MutationAuthor, taskId: String? = null,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceLayerCommit {
        val before = runtime.capture(); val context = currentCoroutineContext(); val job = context[WorkspaceLayerJobExecution]
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(before.projectId == projectId) { "Operation targets another project" }
        job?.check(operation.operation)
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing asset layer")
        val result = commands.execute(projectId, state, summary, listOf(operation), author, taskId) { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing asset layer")
            beforeCommit(capture, document, model)
        }
        val added = result.capture.document.source.layers.map { it.id.raw }.filterNot { id -> before.document.source.layers.any { it.id.raw == id } }
        val affected = if (!result.applied) emptyList() else (added + listOfNotNull(operation.request["layer_id"]?.jsonPrimitive?.content)).distinct()
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, affected, summary,
            affectedObjectIds = if (result.applied) WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet) else emptyList(),
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        job?.committed(operation.operation, mutation.layerResult())
        return WorkspaceLayerCommit(result, mutation)
    }
}

internal fun WorkspaceAddLayerRequest.documentOperation() = WorkspaceDocumentOperation("layer_add_from_asset", buildJsonObject {
    put("asset_id", assetId); put("name", name); layerId?.let { put("layer_id", it) }; put("group_path", groupPath)
    put("semantic_tag", semanticTag); put("side", side); put("visible", visible); put("opacity", opacity); put("trim_transparent", trimTransparent)
    registrationId?.let { put("registration_id", it) }; parentDeformerId?.let { put("parent_deformer_id", it) }
    putJsonObject("insertion") { when (val value = insertion) {
        WorkspaceLayerInsertion.Top -> put("mode", "top")
        WorkspaceLayerInsertion.Bottom -> put("mode", "bottom")
        is WorkspaceLayerInsertion.Above -> { put("mode", "above"); put("reference_layer_id", value.layerId) }
        is WorkspaceLayerInsertion.Below -> { put("mode", "below"); put("reference_layer_id", value.layerId) }
    } }
})

internal object WorkspaceLayerPlacementGuard {
    fun check(document: WorkspaceDocument, model: RigPreviewModel, id: String) {
        val puppet: PuppetModel = model.rig.puppet
        val meshes = puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == id }.mapTo(HashSet()) { it.id.raw }
        require(meshes.isNotEmpty() && id !in document.deletedLayerIds) { "Layer mesh not found" }
        val edits = document.rigEdits
        fun references(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.any { (key, nested) ->
                key !in setOf("name", "user_data") && references(nested)
            }
            is JsonArray -> value.any(::references)
            is JsonPrimitive -> value.isString && (value.content in meshes || value.content.removePrefix("mesh:") in meshes)
        }
        require(edits.authoringJournal.none { entry ->
            // Its own neutral creation is the geometry we replace; later bindings must be preserved.
            val creation = entry["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP &&
                entry["layer_id"]?.jsonPrimitive?.content == id
            !creation && references(entry)
        } && edits.structureEdits.none(::references)) { "Layer has authored motion; relocate before binding" }
        require(edits.warpEdits.none { w -> w.meshIds.any { it in meshes } } &&
            edits.keyformSetEdits.none { it.target.id in meshes } && edits.keyformCopyEdits.none { it.destinationTarget.id in meshes } &&
            edits.keyformDeleteEdits.none { it.target.id in meshes }) { "Layer has dedicated Warp/keyform edits" }
        require(edits.skeleton?.let { skeleton ->
            skeleton.bones.any { bone -> bone.drawableIds.any { it in meshes } } || skeleton.manualWeights.keys.any { it in meshes }
        } != true && edits.swingEdits.none { edit -> edit.targets.any { it == id || it in meshes } } &&
            edits.simEdits.none { edit -> edit.targets.any { it == id || it in meshes } }) { "Layer has skeleton or simulation bindings" }
        require(puppet.drawables.filter { it.id.raw in meshes }.none { mesh ->
            mesh.geometryGrid?.axes?.isNotEmpty() == true || mesh.channelGrids.gridsByChannel.values.any { it.axes.isNotEmpty() } ||
                mesh.blendShapes.isNotEmpty()
        }) { "Layer has parameter bindings; relocate before binding" }
        require(puppet.glues.none { it.meshA.raw in meshes || it.meshB.raw in meshes }) { "Layer has glue constraints" }
        require(puppet.drawables.none { it.id.raw in meshes && it.maskedBy.isNotEmpty() || it.maskedBy.any { mask -> mask.raw in meshes } }) { "Masked layer placement requires coordinated editing" }
    }

}
