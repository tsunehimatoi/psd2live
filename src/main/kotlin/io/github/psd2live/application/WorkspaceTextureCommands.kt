package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class WorkspaceTextureCommit(val commit: WorkspaceCommit<RigPreviewModel>, val result: WorkspaceTextureResult)

/** Single texture edits use the same pure candidate as batch members, then rebuild and CAS once. */
internal class WorkspaceTextureCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, edit: WorkspaceTextureEdit, author: MutationAuthor, taskId: String? = null,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceTextureCommit {
        val context = currentCoroutineContext()
        val job = context[WorkspaceTextureJobExecution]
        job?.check(edit.operation)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        val progress = context[WorkspaceJobContext]
        progress?.progress(0.05f, "Preparing ${edit.operation}")
        // A file is read only after the state check, and never inside the pure candidate.
        val resolved = if (edit is WorkspaceTextureEdit.ReplaceImage && edit.image is WorkspaceTextureImage.File)
            edit.copy(image = WorkspaceTextureImage.Raster(WorkspaceTextureEdits.decodeFile(edit.image.path) { context.ensureActive() }))
        else edit
        val summary = summary(resolved)
        val result = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            WorkspaceTextureEdits.apply(document, model, resolved) { context.ensureActive() }.also {
                context.ensureActive(); progress?.progress(0.3f, "Rebuilding ${edit.operation}")
            }
        }, beforeCommit = { captured, document, model ->
            context.ensureActive(); progress?.progress(0.95f, "Committing ${edit.operation}")
            beforeCommit(captured, document, model)
        }, validate = { beforeDocument, beforeModel, afterDocument, afterModel ->
            // A moved tile or a new density lands on its spot or not at all.
            if (edit.operation in WorkspaceTextureEdits.placementEdits) WorkspaceTextureEdits.requireKept(beforeDocument, beforeModel, afterDocument, afterModel)
        })
        val layers = if (result.applied) WorkspaceTextureEdits.changedLayers(resolved, before.document, result.capture.document) else emptyList()
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, layers, summary,
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId,
            geometryDiagnostics = result.geometryDiagnostics)
        val texture = WorkspaceTextureResult(mutation, layers, result.capture.model.atlas.fit,
            atlasBudgetNotices(result.capture.model.atlas, result.capture.model.config.effectiveAtlasBudget(),
                result.capture.document.textureOverrides))
        // No suspension after CAS: a host refresh failure or a late cancellation cannot discard the result.
        job?.committed(edit.operation, texture.toJson())
        return WorkspaceTextureCommit(result, texture)
    }

    private fun summary(edit: WorkspaceTextureEdit): String = when (edit) {
        is WorkspaceTextureEdit.SetCanvasRect -> "Moved layer ${edit.layerId}"
        is WorkspaceTextureEdit.ReplaceImage -> "Replaced image of layer ${edit.layerId}"
        is WorkspaceTextureEdit.SetPixelDensity -> "Set texture density"
        is WorkspaceTextureEdit.SetTile -> if (edit.pin == null) "Released atlas tile ${edit.layerId}" else "Pinned atlas tile ${edit.layerId}"
        is WorkspaceTextureEdit.SetBudget -> "Set atlas budget"
        is WorkspaceTextureEdit.Pack -> "Packed atlas"
    }
}

internal fun WorkspaceTextureResult.toJson(): JsonObject = buildJsonObject {
    put("project_id", requireNotNull(mutation.projectId)); put("state", requireNotNull(mutation.state))
    put("history_node_id", mutation.historyNodeId); put("revision", mutation.revisionId); put("applied", mutation.applied)
    put("layers", JsonArray(layerIds.map(::JsonPrimitive)))
    put("atlas_fit", atlasFit)
    put("notices", JsonArray(notices.map(::JsonPrimitive)))
    mutation.geometryDiagnostics?.let { put("geometry_diagnostics", it) }
}

internal class WorkspaceTextureJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceTextureJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active texture operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
