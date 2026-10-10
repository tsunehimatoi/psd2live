package io.github.psd2live.application

import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import io.github.psd2live.project.WorkspaceMutationResult

internal class WorkspaceBatchEditException(val index: Int, val editOperation: String, cause: Exception) :
    IllegalArgumentException("Edit $index ($editOperation) failed: ${cause.message}", cause)

/** Each batchable operation's business request: its published schema without the shared request context. */
internal fun batchMemberSchemas(registry: WorkspaceOperationRegistry): Map<String, JsonObject> =
    WorkspaceDocumentEdits.supported.sorted().associateWith { id ->
        val published = registry.definition(id).requestSchema
        val contextFields = setOf("state", "project_id", "request_id")
        JsonObject(published + mapOf(
            "properties" to JsonObject(published.getValue("properties").jsonObject - contextFields),
            "required" to JsonArray(published["required"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content !in contextFields })))
    }

/**
 * Starts one atomic document batch as the job [operation]. workspace_apply_edits and the intent operations that compile
 * to member edits share it, so every one of them prepares, rebuilds, checks and commits exactly the same way.
 */
internal suspend fun startDocumentBatch(port: WorkspaceDocumentPort, statePort: WorkspaceStatePort, jobs: WorkspaceJobs,
                                        operation: String, state: String, summary: String,
                                        edits: List<WorkspaceDocumentOperation>, author: io.github.psd2live.project.MutationAuthor) =
    startWorkspaceOperationJob(statePort, jobs, operation) {
        val coroutine = currentCoroutineContext()
        val execution = WorkspaceBatchJobExecution(edits, requireNotNull(coroutine[WorkspaceJobCompletion]), coroutine[WorkspaceJobContext])
        withContext(execution) {
            WorkspaceOperationOutput(port.applyDocumentEdits(state, summary, edits, author).batchResult(edits.size))
        }
    }

internal fun registerDocumentBatch(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                   statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val supported = WorkspaceDocumentEdits.supported
    registry.markBatchable(supported)
    val variants = batchMemberSchemas(registry).map { (id, business) ->
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
        startDocumentBatch(port, statePort, jobs, "workspace_apply_edits", request.getValue("state").jsonPrimitive.content,
            request["summary"]?.jsonPrimitive?.content ?: "Applied ${edits.size} document edits", edits, context.author)
    }
    registry.register(WorkspaceOperationDefinition("workspace_preview_edits",
        "Dry-run 1..128 geometry authoring operations using the same ordered candidate preparation, rebuild and geometry gate as commit. No state, pose, history, dirty flag or resources are published. Returns a read-only job; diagnostics describe the captured input state. New IDs for canvas creation must be explicit so a later commit with the same state and edits reproduces the candidate revision. The geometry scope is parent-local sampled keys, not visual quality or composed animation.",
        schema(variants.filter { it.getValue("properties").jsonObject.getValue("operation").jsonObject.getValue("const").jsonPrimitive.content in WorkspaceGeometrySafety.operations }),
        WorkspaceOperationKind.QUERY, jobBacked = true, workspaceBound = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("workspace_preview_edits")),
        jobResultSchema = WorkspaceGeometrySafetySchemas.preview)) { request, _ ->
        val edits = request.getValue("edits").jsonArray.map { raw ->
            val edit = raw.jsonObject
            WorkspaceDocumentOperation(edit.getValue("operation").jsonPrimitive.content, edit.getValue("request").jsonObject)
        }
        startWorkspaceOperationJob(statePort, jobs, "workspace_preview_edits") {
            WorkspaceOperationOutput(port.previewDocumentEdits(request.getValue("state").jsonPrimitive.content, edits))
        }
    }
    registry.register(WorkspaceOperationDefinition("workspace_preview_regeneration",
        "Dry-run 1..128 document operations - typically ones that regenerate the rig: settings_update, layer_classify, source splits, rig_update_generation - with the same ordered candidate preparation and rebuild as workspace_apply_edits, without publishing state, history or resources. Reports the checkpoints the commit would add to the journal (kind regeneration: what the generators make now merged onto the user's rig, with the issues the merge could not carry over cleanly; kind materialized: the authored rig checkpointed before new entries) and the object handles the candidate adds and removes. Asset layer operations are excluded. Returns a read-only job.",
        schema(variants.filter { it.getValue("properties").jsonObject.getValue("operation").jsonObject.getValue("const").jsonPrimitive.content !in WorkspaceAssetLayerEdits.supported }),
        WorkspaceOperationKind.QUERY, jobBacked = true, workspaceBound = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput("workspace_preview_regeneration")),
        jobResultSchema = WorkspaceJobResultSchemas.regenerationPreview)) { request, _ ->
        val edits = request.getValue("edits").jsonArray.map { raw ->
            val edit = raw.jsonObject
            WorkspaceDocumentOperation(edit.getValue("operation").jsonPrimitive.content, edit.getValue("request").jsonObject)
        }
        startWorkspaceOperationJob(statePort, jobs, "workspace_preview_regeneration") {
            WorkspaceOperationOutput(port.previewRegeneration(request.getValue("state").jsonPrimitive.content, edits))
        }
    }
}

internal fun WorkspaceMutationResult.batchResult(count: Int): JsonObject = buildJsonObject {
    put("project_id", requireNotNull(projectId)); put("state", requireNotNull(state))
    put("history_node_id", historyNodeId); put("revision", revisionId); put("applied", applied)
    put("edit_count", count); put("changed", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
    geometryDiagnostics?.let { put("geometry_diagnostics", it) }
    atlasFit?.let { fit -> put("atlas_fit", fit); put("notices", JsonArray(atlasNotices.map(::JsonPrimitive))) }
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
