package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Deleting a layer removes it: its pixels leave the document (history keeps them, so undo brings it back), and its
 * meshes leave the authored rig through a `layer_delete` journal entry ([LayerDeletionJournal]). A layer the generators
 * read leaves their input too, so the regeneration that follows stops making its meshes and no longer shapes any frame.
 * Nothing is kept aside for a later restore.
 *
 * `layer_restore` only brings back layers an older version soft-deleted ([WorkspaceDocument.deletedLayerIds]); a row
 * that is not a source layer of its own (a mouth lip ribbon, a legacy left or right half) is still hidden that way.
 */
internal object WorkspaceLayerEdits {
    const val DELETE = "layer_delete"
    val supported = setOf(DELETE, "layer_restore")

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation): WorkspaceDocument {
        val known = document.source.layers.mapTo(HashSet()) { it.id.raw } + model.analysis.layers.map { it.source.id.raw } +
            document.deletedLayerIds
        return when (operation.operation) {
            DELETE -> {
                val id = operation.request.getValue("layer_id").jsonPrimitive.content
                require(id in known) { "Layer not found: $id" }
                if (document.source.layers.any { it.id.raw == id }) delete(document, model, id)
                else if (id in document.deletedLayerIds) document
                else document.copy(deletedLayerIds = document.deletedLayerIds + id, rigEdits = RigLayerDeletion.preserve(model, document.config()))
            }
            "layer_restore" -> {
                val ids = operation.request["layer_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: document.deletedLayerIds.toList()
                require(ids.distinct().size == ids.size && ids.all { it in known }) { "Restore requires existing unique layer IDs" }
                if (ids.none { it in document.deletedLayerIds }) document else
                    document.copy(deletedLayerIds = document.deletedLayerIds - ids.toSet(),
                        rigEdits = RigLayerDeletion.preserve(model, document.config()))
            }
            else -> error("Not a layer membership operation")
        }
    }

    private fun delete(document: WorkspaceDocument, model: RigPreviewModel, id: String): WorkspaceDocument {
        val remaining = document.source.layers.filterNot { it.id.raw == id }
        require(remaining.isNotEmpty()) { "The last layer cannot be deleted" }
        // Its meshes, and the ones generated from it under derived IDs (mouth lip ribbons, legacy halves).
        val meshes = model.rig.layerIdByDrawableId.filter { (_, layer) -> layer == id || layer.startsWith("$id:") }.keys
        fun without(source: org.umamo.format.art.SourceArt?) = source?.let { art ->
            if (art.layers.none { it.id.raw == id }) art else WorkspaceSourceArt(art.widthPx, art.heightPx, art.layers.filterNot { it.id.raw == id }, art.groups)
        }
        val candidate = document.copy(
            source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
                remaining.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, remaining.lastIndex - index) }, document.source.groups),
            generationSource = without(document.generationSource), meshSource = without(document.meshSource),
            layerVisibility = document.layerVisibility - id, layerOverrides = document.layerOverrides - id,
            parentOverrides = document.parentOverrides - id, meshOverrides = document.meshOverrides - id,
            textureOverrides = document.textureOverrides - id, deletedLayerIds = document.deletedLayerIds - id,
            rigEdits = document.rigEdits.copy(splitDrawableIds = document.rigEdits.splitDrawableIds - id,
                assetLayers = document.rigEdits.assetLayers - id, calibrationLayerIds = document.rigEdits.calibrationLayerIds - id))
        if (meshes.isEmpty()) return candidate
        return candidate.copy(rigEdits = candidate.rigEdits.copy(authoringJournal = candidate.rigEdits.authoringJournal +
            LayerDeletionJournal.encode(id, meshes)))
    }
}

internal data class WorkspaceLayerCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

internal class WorkspaceLayerCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
        author: MutationAuthor, taskId: String? = null,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceLayerCommit {
        require(operation.operation in WorkspaceLayerEdits.supported)
        val context = currentCoroutineContext()
        val job = context[WorkspaceLayerJobExecution]
        job?.check(operation.operation)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing layer membership")
        val result = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            WorkspaceLayerEdits.apply(document, model, operation).also {
                context.ensureActive(); context[WorkspaceJobContext]?.progress(0.3f, "Rebuilding layer membership")
            }
        }, beforeCommit = { captured, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing layer membership")
            beforeCommit(captured, document, model)
        })
        val changed = (before.document.deletedLayerIds - result.capture.document.deletedLayerIds) +
            (result.capture.document.deletedLayerIds - before.document.deletedLayerIds)
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, changed.toList(), summary,
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        // Retain the actual CAS result before a host refresh or a late cancellation can intervene.
        job?.committed(operation.operation, mutation.layerResult())
        return WorkspaceLayerCommit(result, mutation)
    }
}

internal fun WorkspaceMutationResult.layerResult() = buildJsonObject {
    put("state", requireNotNull(state)); put("project_id", requireNotNull(projectId)); put("history_node_id", historyNodeId)
    put("revision", revisionId); put("applied", applied)
    if (affectedLayerIds.isNotEmpty()) put("affectedLayerIds", JsonArray(affectedLayerIds.map(::JsonPrimitive)))
    if (affectedObjectIds.isNotEmpty()) put("affectedObjectIds", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
}

internal class WorkspaceLayerJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceLayerJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active layer operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
