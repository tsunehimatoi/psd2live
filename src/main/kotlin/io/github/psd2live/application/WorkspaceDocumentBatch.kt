package io.github.psd2live.application

import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import io.github.psd2live.project.WorkspaceMutationResult

internal class WorkspaceBatchEditException(val index: Int, val editOperation: String, cause: Exception) :
    IllegalArgumentException("Edit $index ($editOperation) failed: ${cause.message}", cause)

internal fun registerDocumentBatch(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                   statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val supported = WorkspaceDocumentEdits.supported
    registry.markBatchable(supported)
    val variants = supported.sorted().map { id ->
        val published = registry.definition(id).requestSchema
        val contextFields = setOf("state", "project_id", "request_id")
        val business = JsonObject(published + mapOf(
            "properties" to JsonObject(published.getValue("properties").jsonObject - contextFields),
            "required" to JsonArray(published["required"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content !in contextFields })))
        buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("operation") { put("type", "string"); put("const", id) }
                put("request", business)
            }
            put("required", JsonArray(listOf(JsonPrimitive("operation"), JsonPrimitive("request"))))
        }
    }
    fun schema(allowed: List<JsonObject>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("state") { put("type", "string"); put("minLength", 1) }
            putJsonObject("summary") { put("type", "string"); put("minLength", 1); put("maxLength", 512) }
            putJsonObject("edits") {
                put("type", "array"); put("minItems", 1); put("maxItems", 128)
                putJsonObject("items") { put("oneOf", JsonArray(allowed)) }
                put("description", "Ordered document edits. Each request uses its single operation's business fields, without state/project_id/request_id. Later edits see earlier candidates. All edits commit together or none do.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("state"), JsonPrimitive("edits"))))
    }
    registry.register(WorkspaceOperationDefinition("workspace_apply_edits",
        "Apply 1..128 document operations atomically. Supported members are marked batchable in discovery. Every member uses the exact single-operation business schema. Rebuilds and replays candidates in order, validates the final state and commits one history node; any invalid member, cancellation or state conflict leaves the entire workspace unchanged. Returns a process-owned job handle; use job_wait/job_get for the committed state and changed handles. Disconnecting does not cancel execution. Asset layers resolve immutable resources from the captured catalog. Session changes, jobs, project switching, resource writes and nested batches are excluded.",
        schema(variants), WorkspaceOperationKind.DOCUMENT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("workspace_apply_edits")),
        jobResultSchema = WorkspaceJobResultSchemas.result("workspace_apply_edits"))) { request, context ->
        val edits = request.getValue("edits").jsonArray.map { raw ->
            val edit = raw.jsonObject
            WorkspaceDocumentOperation(edit.getValue("operation").jsonPrimitive.content, edit.getValue("request").jsonObject)
        }
        startWorkspaceOperationJob(statePort, jobs, "workspace_apply_edits") {
            val coroutine = currentCoroutineContext()
            val execution = WorkspaceBatchJobExecution(edits, requireNotNull(coroutine[WorkspaceJobCompletion]), coroutine[WorkspaceJobContext])
            withContext(execution) {
                val result = port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
                    request["summary"]?.jsonPrimitive?.content ?: "Applied ${edits.size} document edits", edits, context.author)
                WorkspaceOperationOutput(result.batchResult(edits.size))
            }
        }
    }
    registry.register(WorkspaceOperationDefinition("workspace_preview_edits",
        "Dry-run 1..128 geometry authoring operations using the same ordered candidate preparation, rebuild and geometry gate as commit. No state, pose, history, dirty flag or resources are published. Returns a read-only job; diagnostics describe the captured input state. New IDs for canvas creation must be explicit so a later commit with the same state and edits reproduces the candidate revision. The geometry scope is parent-local sampled keys, not visual quality or composed animation.",
        schema(variants.filter { it.getValue("properties").jsonObject.getValue("operation").jsonObject.getValue("const").jsonPrimitive.content in WorkspaceCandidateQuality.operations }),
        WorkspaceOperationKind.QUERY, jobBacked = true, workspaceBound = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("workspace_preview_edits")),
        jobResultSchema = WorkspaceGeometryQualitySchemas.preview)) { request, _ ->
        val edits = request.getValue("edits").jsonArray.map { raw ->
            val edit = raw.jsonObject
            WorkspaceDocumentOperation(edit.getValue("operation").jsonPrimitive.content, edit.getValue("request").jsonObject)
        }
        startWorkspaceOperationJob(statePort, jobs, "workspace_preview_edits") {
            WorkspaceOperationOutput(port.previewDocumentEdits(request.getValue("state").jsonPrimitive.content, edits))
        }
    }
}

internal fun WorkspaceMutationResult.batchResult(count: Int): JsonObject = buildJsonObject {
    put("project_id", requireNotNull(projectId)); put("state", requireNotNull(state))
    put("history_node_id", historyNodeId); put("revision", revisionId); put("applied", applied)
    put("edit_count", count); put("changed", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
    geometryDiagnostics?.let { put("geometry_diagnostics", it) }
}

/** Only the exact batch owns this completion marker; nested or unrelated document work cannot claim it. */
internal class WorkspaceBatchJobExecution(
    val edits: List<WorkspaceDocumentOperation>,
    private val completion: WorkspaceJobCompletion,
    private val progress: WorkspaceJobContext?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceBatchJobExecution>
    fun preparing(index: Int) {
        progress?.progress(0.05f + 0.85f * index / edits.size, "Preparing and rebuilding edit ${index + 1}/${edits.size}: ${edits[index].operation}")
    }
    fun baking(index: Int, id: String, fraction: Float) {
        progress?.progress(0.05f + 0.85f * (index + 0.85f * fraction) / edits.size, "Baking simulation $id in edit ${index + 1}/${edits.size}")
    }
    fun fitting(index: Int, id: String, fraction: Float) {
        progress?.progress(0.05f + 0.85f * (index + 0.85f * fraction) / edits.size, "Fitting physics $id in edit ${index + 1}/${edits.size}")
    }
    fun painting(index: Int, fraction: Float, message: String) {
        progress?.progress(0.05f + 0.85f * (index + 0.85f * fraction) / edits.size,
            "$message in edit ${index + 1}/${edits.size}")
    }
    fun committing() { progress?.progress(0.95f, "Committing atomic document batch") }
    fun committed(result: WorkspaceMutationResult) {
        completion.committed(WorkspaceOperationOutput(result.batchResult(edits.size)))
    }
}
