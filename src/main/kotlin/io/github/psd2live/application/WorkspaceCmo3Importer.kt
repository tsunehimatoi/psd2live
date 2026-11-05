package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Authored-model import prepares an isolated document and commits it without a GUI or transport. */
internal class WorkspaceCmo3Importer(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val build: suspend (WorkspaceDocument, RigPreviewModel?) -> RigPreviewModel = { document, current -> WorkspacePreviewBuilder().build(document, current) },
    private val read: suspend (Path) -> ByteArray = { path -> runInterruptible(Dispatchers.IO) { Files.readAllBytes(path) } },
    private val newProjectId: () -> String = { UUID.randomUUID().toString() },
    private val initialWorkspaceIds: () -> List<String> = { emptyList() },
) {
    suspend fun import(path: Path, mode: Cmo3ImportMode, projectId: String?, state: String,
                       author: MutationAuthor, discardUnsaved: Boolean = false,
                       initialConfig: PipelineConfig = PipelineConfig(),
                       sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                       project: (WorkspaceCapture<RigPreviewModel>?, WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.state.value
        WorkspaceExecution(projectId, state, author).check(before.capture?.projectId, before.state)
        require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".cmo3", true)) {
            "Provide an absolute local CMO3 path"
        }
        val replacing = mode == Cmo3ImportMode.REPLACE
        require(!replacing || before.capture != null) { "CMO3 replacement requires a loaded workspace" }
        if (!replacing && !discardUnsaved && before.capture?.dirty == true) throw WorkspaceUnsavedChanges()
        suspend fun progress(fraction: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(fraction, message)
        }
        progress(0.1f, "Reading authored model")
        val bytes = read(path)
        progress(0.3f, "Preparing source and imported rig")
        val current = before.capture?.takeIf { replacing }
        // Raw settings in, raw settings out: the replaced document keeps what was chosen, not what generation derived.
        val base = current?.document?.rawConfig() ?: initialConfig.copy(hairSimulationFront = false, hairSimulationBack = false)
        val (source, config) = runInterruptible(Dispatchers.Default) {
            Cmo3ModelImport.prepare(bytes, mode, current?.model, base)
        }
        val edits = if (!replacing || current?.document?.rigEdits?.modifierStack != null)
            config.rigEdits.copy(modifierStack = io.github.psd2live.core.modifier.RigModifierStack().toJson())
        else config.rigEdits
        // Every ArtMesh's image is in the library too, in a folder named after the file.
        val document = WorkspaceMaterialEdits.addLinked(WorkspaceDocument(source, config.layerVisibility, config.deletedLayerIds, config.layerOverrides,
            config.parentOverrides, edits, WorkspaceSettingsCodec.encode(config), config.meshOverrides,
            layout = current?.document?.layout?.let(::replacedLayout) ?: WorkspaceLayout.Empty), path.fileName.toString())
        progress(0.6f, "Rebuilding imported document")
        val preview = build(document, current?.model)
        progress(0.9f, "Committing imported document")
        val summary = "Imported CMO3 ${path.fileName}"
        val result = if (current != null) {
            val posed = current.auxiliary["posesByWorkspace"]?.jsonObject?.let { poses ->
                val pose = PreviewSessions.encode(WorkspacePose(preview.rig.puppet.parameters.associate { it.id to it.default }, emptySet()))
                JsonObject(current.auxiliary + ("posesByWorkspace" to JsonObject(poses.mapValues { pose })))
            } ?: current.auxiliary
            // The desktop clears every canvas solo on replacement; the stored copy moves in the same commit.
            val auxiliary = CanvasVisibilityProcessor.endSolos(posed)
            runtime.commitPrepared(current.projectId, state, summary, author, document, preview, auxiliary = auxiliary) { _, next, model ->
                project(current, next, model, current.projectId)
            }
        } else {
            val id = newProjectId()
            val installed = runtime.install(state, id, document, preview,
                auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                    PreviewSessions.initial(preview.rig.puppet.parameters, initialWorkspaceIds()) +
                    ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
                project(before.capture, document, preview, id)
            }
            WorkspaceCommit(installed, true)
        }
        // There is no suspending handoff between the authoritative CAS and its durable completion marker.
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).lifecycleResult()))
        return result
    }
}

/**
 * What a CMO3 replacement keeps of the [replaced] document's Layout: the material library, the reference images and
 * the drawing kit. Everything kept per source layer goes, since the model brings layers of its own, and the imported
 * model counts as generated.
 */
internal fun replacedLayout(replaced: WorkspaceLayout): WorkspaceLayout =
    WorkspaceLayout(materials = replaced.materials, references = replaced.references, drawingKit = replaced.drawingKit)

internal fun WorkspaceCommit<RigPreviewModel>.mutation(summary: String) = WorkspaceMutationResult(
    capture.historyHead, capture.revision, if (applied) capture.document.source.layers.map { it.id.raw } else emptyList(), summary,
    affectedObjectIds = if (applied) capture.model.rig.puppet.drawables.map { "mesh:${it.id.raw}" } else emptyList(),
    applied = applied, state = capture.state, projectId = capture.projectId)
