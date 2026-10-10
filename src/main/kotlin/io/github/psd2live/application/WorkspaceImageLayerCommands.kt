package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Decode privately, then publish the complete insertion as one document transaction. */
internal class WorkspaceImageLayerCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun importImages(projectId: String, state: String, paths: List<Path>, parent: String?, summary: String,
                             author: MutationAuthor,
                             beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceLayerCommit {
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(before.projectId == projectId) { "Operation targets another project" }
        val context = currentCoroutineContext()
        val job = context[WorkspaceLayerJobExecution]
        job?.check(OP)
        context.ensureActive()
        require(paths.size in 1..128) { "Import requires 1..128 image paths" }
        require(paths.all { it.isAbsolute }) { "Image paths must be absolute" }
        require(parent == null || before.model.rig.puppet.deformers.any { it.id.raw == parent }) { "Parent deformer not found" }
        val layers = runInterruptible(Dispatchers.Default) {
            var pixels = 0L
            // A fresh ID per imported layer: undoing an import and importing the same file again never revives state kept
            // under the earlier layer's ID.
            val taken = before.document.source.layers.mapTo(HashSet()) { it.id.raw }
            paths.mapIndexed { index, path ->
                context.ensureActive()
                context[WorkspaceJobContext]?.progress(0.05f + 0.2f * index / paths.size, "Reading image ${index + 1}/${paths.size}")
                val file = path.toFile()
                val image = LayerImport.decodeRasterFile(file, { context.ensureActive() })
                pixels += image.width.toLong() * image.height
                require(pixels <= 33_554_432L) { "Image batch exceeds 32 megapixels" }
                val id = StableIds.fresh("import:" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)) { it in taken }
                taken += id
                LayerImport.placedLayer(image, before.document.source.widthPx, before.document.source.heightPx,
                    LayerImport.displayNameOf(file), id, checkCancelled = { context.ensureActive() })
            }
        }
        context.ensureActive()
        val ids = layers.map { it.id.raw }
        val committed = commands.executeCandidate(projectId, state, summary, author, mutation = { document, model ->
            context.ensureActive()
            val all = document.source.layers + layers
            val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
                all.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, all.lastIndex - index) }, document.source.groups)
            // An imported image is the user's object: its mesh is created once, under its parent, in the authored rig, and the
            // generators never read it ([RigGenerationSource.createdCoverage]). Moving it later is a mesh edit like any other.
            val candidate = document.copy(source = source, layerVisibility = document.layerVisibility + ids.associateWith { true },
                layerOverrides = document.layerOverrides + ids.associateWith { LayerClassificationOverride(LayerType.PRESET, SemanticTag.UNKNOWN, Side.NONE) })
            WorkspaceLayerInsertionEdits.materialize(candidate, model, ids.toSet(), parent) { context.ensureActive() }
        }, beforeCommit = { captured, document, model ->
            context.ensureActive()
            context[WorkspaceJobContext]?.progress(0.95f, "Committing imported images")
            validateRegisteredNeutral(model, ids.toSet())
            beforeCommit(captured, document, model)
        })
        val mutation = WorkspaceMutationResult(committed.capture.historyHead, committed.capture.revision,
            if (committed.applied) ids else emptyList(), summary,
            affectedObjectIds = if (committed.applied) WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet,
                committed.capture.model.rig.puppet) else emptyList(), applied = committed.applied,
            state = committed.capture.state, projectId = committed.capture.projectId)
        job?.committed(OP, mutation.layerResult())
        return WorkspaceLayerCommit(committed, mutation)
    }

    companion object { const val OP = "layer_import_images" }

}
