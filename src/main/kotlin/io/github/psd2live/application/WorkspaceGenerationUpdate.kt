package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The explicit regeneration with this build's generators ([RigRegenerationCheckpoint.updated]): a pure candidate. A
 * document whose journal has a checkpoint keeps what the generators made when it was written - a newer build does not
 * change it on its own - until this command merges what they make now onto the user's edits.
 */
internal object WorkspaceGenerationUpdate {
    const val OP = "rig_update_generation"
    val supported = setOf(OP)
    private val pipeline = PSD2LivePipeline()

    class Result(val document: WorkspaceDocument, val updated: Boolean, val issues: List<RigRegeneration.Issue>)

    /** Whether [overlay] keeps a generated rig the command can compare against: a checkpoint storing one. */
    fun updatable(overlay: RigEditOverlay): Boolean =
        overlay.authoringJournal.lastOrNull(RigCheckpoint::isRecord)?.let { "generated" in it } == true

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        require(operation.operation in supported)
        return update(document, model, work).document
    }

    /** [document] regenerated and merged, or unchanged when the generators make what its last checkpoint stores. */
    fun update(document: WorkspaceDocument, model: RigPreviewModel, work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): Result {
        require(document.rigEdits.modifierStack == null) { "Update generated content through its modifier instead of legacy regeneration" }
        require(model.config.rigEdits.importedCmo3 == null) { "An imported model has no generated rig to update" }
        work.progress(0.1f, "Generating the rig")
        val update = RigRegenerationCheckpoint.updated(pipeline, model, document.source) {
            work.checkpoint(); work.progress(0.5f, "Merging the regenerated rig")
        } ?: return Result(document, false, emptyList())
        work.progress(1f, "Merged the regenerated rig")
        return Result(document.copy(rigEdits = update.config.rigEdits), true, update.issues)
    }

    fun issues(issues: List<RigRegeneration.Issue>) = JsonArray(issues.map { issue -> buildJsonObject {
        put("kind", issue.kind.code); put("target", issue.target); if (issue.detail.isNotEmpty()) put("detail", issue.detail)
    } })
}

internal data class WorkspaceGenerationUpdateCommit(val commit: WorkspaceCommit<RigPreviewModel>, val result: JsonObject)

/** The single update command: one candidate, one history node when the generators changed anything, none otherwise. */
internal class WorkspaceGenerationUpdateCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                                 private val observer: WorkspaceRasterWork = WorkspaceRasterWork.Direct) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, summary: String, author: MutationAuthor,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceGenerationUpdateCommit {
        val context = currentCoroutineContext()
        val job = context[WorkspaceGenerationUpdateJobExecution]
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        var outcome: WorkspaceGenerationUpdate.Result? = null
        val result = commands.executeCandidate(projectId, state, summary, author, mutation = { document, model ->
            WorkspaceGenerationUpdate.update(document, model, observer.cancellable(context) { fraction, message ->
                context[WorkspaceJobContext]?.progress(0.05f + 0.5f * fraction, message)
            }).also { outcome = it }.document
        }, beforeCommit = { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing the regenerated rig")
            beforeCommit(capture, document, model)
        })
        val output = buildJsonObject {
            put("state", result.capture.state); put("project_id", result.capture.projectId)
            put("history_node_id", result.capture.historyHead); put("revision", result.capture.revision)
            put("applied", result.applied)
            put("updated", outcome?.updated == true)
            put("issues", WorkspaceGenerationUpdate.issues(outcome?.issues.orEmpty()))
        }
        // Retain the CAS result before host projection refresh or late cancellation can intervene.
        job?.committed(output)
        return WorkspaceGenerationUpdateCommit(result, output)
    }
}

internal class WorkspaceGenerationUpdateJobExecution(private val completion: WorkspaceJobCompletion) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceGenerationUpdateJobExecution>
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
