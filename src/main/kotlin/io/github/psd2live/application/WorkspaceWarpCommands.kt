package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Resolve references against the candidate model, including objects created earlier in a batch. */
internal object WorkspaceWarpEdits {
    val supported = setOf("rig_create_warp")

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val targets = request.getValue("targets").jsonArray.map { value ->
            val target = value.jsonPrimitive.content
            require(target.startsWith("mesh:") && target.removePrefix("mesh:").isNotBlank()) { "Warp targets must use mesh:id" }
            target.removePrefix("mesh:")
        }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size) { "Warp targets must be unique meshes" }
        val parents = targets.map { id ->
            requireNotNull(model.rig.puppet.drawables.singleOrNull { it.id.raw == id }) { "Mesh not found: $id" }.parentDeformerId
        }.distinct()
        require(parents.size == 1 && parents.single() != null) { "Targets need a common Warp parent" }
        val edit = RigWarpEdit(request["id"]?.jsonPrimitive?.content
                ?: StableIds.of("Warp_", request) { id -> model.rig.puppet.deformers.any { it.id.raw == id } },
            request.getValue("name").jsonPrimitive.content, parents.single()!!.raw, targets,
            request["rows"]?.jsonPrimitive?.int ?: 16, request["columns"]?.jsonPrimitive?.int ?: 16,
            request["fit_local"]?.jsonPrimitive?.boolean ?: true)
        return WorkspaceDocumentEdits.journal(document, model, buildJsonArray {
            add(buildJsonObject { put("op", "warp"); put("warp", edit.toJson()) })
        })
    }
}

internal data class WorkspaceWarpCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

/** Single Warp creation and atomic batches share the same ordered, serializable candidate edit. */
internal class WorkspaceWarpCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, request: JsonObject, author: MutationAuthor, taskId: String? = null,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceWarpCommit {
        val context = currentCoroutineContext()
        val job = context[WorkspaceWarpJobExecution]
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing independent Warp")
        val summary = "Created independent Warp"
        val result = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            WorkspaceWarpEdits.apply(document, model, request).also {
                context.ensureActive(); context[WorkspaceJobContext]?.progress(0.3f, "Rebuilding independent Warp")
            }
        }, beforeCommit = { captured, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing independent Warp")
            beforeCommit(captured, document, model)
        })
        val mutation = WorkspaceDocumentCommands.mutationResult(before, result, summary, emptyList())
        // No suspension after CAS: host refresh failures and late cancellation cannot discard the handle.
        job?.committed(mutation.warpResult())
        return WorkspaceWarpCommit(result, mutation)
    }
}

internal fun WorkspaceMutationResult.warpResult() = JsonObject(compact() +
    ("target" to JsonPrimitive(affectedObjectIds.single { it.startsWith("warp:") })))

internal class WorkspaceWarpJobExecution(private val completion: WorkspaceJobCompletion) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceWarpJobExecution>
    fun committed(result: JsonObject) = completion.committed(WorkspaceOperationOutput(result))
}
