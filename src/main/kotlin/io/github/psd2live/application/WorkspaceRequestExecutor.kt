package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class WorkspaceProjectConflict(val expectedProject: String?, val actualProject: String?) :
    IllegalStateException("Operation targets project $expectedProject; current project is $actualProject")
internal class WorkspaceRequestReuse : IllegalArgumentException("Request ID was already used with another operation, arguments or author")

/** Trusted adapter author and validated expectation travel to the actual commit, including jobs. */
internal class WorkspaceExecution(
    val projectId: String?, val state: String?, val author: MutationAuthor,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceExecution>
    fun check(projectId: String?, state: String) {
        if (this.projectId != projectId) throw WorkspaceProjectConflict(this.projectId, projectId)
        if (this.state != state) throw WorkspaceConflict(requireNotNull(this.state), state)
    }
}

/** Process-owned requests survive disconnected waiters; retries share both success and failure. */
internal class WorkspaceRequestExecutor(private val workspace: WorkspaceStatePort) : AutoCloseable {
    private data class Entry(val operation: String, val request: JsonObject, val author: MutationAuthor,
                             val result: Deferred<WorkspaceOperationOutput>)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val entries = linkedMapOf<Pair<String?, String>, Entry>()
    private var closed = false

    suspend fun execute(definition: WorkspaceOperationDefinition, request: JsonObject,
                        context: WorkspaceOperationContext, action: suspend () -> WorkspaceOperationOutput): WorkspaceOperationOutput {
        if (definition.kind == WorkspaceOperationKind.QUERY && !definition.jobBacked) return action()
        val projectId = request["project_id"]?.jsonPrimitive?.contentOrNull
        val requestId = request.getValue("request_id").jsonPrimitive.content
        val result = synchronized(lock) {
            check(!closed) { "Workspace request executor is closed" }
            val key = projectId to requestId
            entries[key]?.let {
                if (it.operation != definition.id || it.request != request || it.author != context.author) throw WorkspaceRequestReuse()
                return@synchronized it.result
            }
            val expected = if (definition.workspaceBound) request.getValue("state").jsonPrimitive.content else null
            val execution = WorkspaceExecution(projectId, expected, context.author)
            val pending = scope.async(execution, start = CoroutineStart.LAZY) {
                if (definition.workspaceBound) {
                    val captured = workspace.snapshot()
                    execution.check(captured.projectId, captured.state)
                }
                action()
            }
            entries[key] = Entry(definition.id, request, context.author, pending)
            evictSettled()
            pending.start()
            pending
        }
        return result.await()
    }

    /**
     * Keeps the newest [MAX_SETTLED] settled requests. Every result used to stay until the app exited, images
     * included, so a long agent session grew without bound. A retry of an evicted ID runs again, and its stale state
     * makes a workspace-bound one fail with a conflict rather than apply twice. Requests still running are never evicted.
     */
    private fun evictSettled() {
        var settled = entries.values.count { it.result.isCompleted }
        if (settled <= MAX_SETTLED) return
        val iterator = entries.values.iterator()
        while (settled > MAX_SETTLED && iterator.hasNext()) {
            if (iterator.next().result.isCompleted) { iterator.remove(); settled-- }
        }
    }

    private companion object { const val MAX_SETTLED = 512 }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; scope.cancel() }
    }
}

/** Shared public context is part of the actual schema, not an out-of-band transport convention. */
internal fun WorkspaceOperationDefinition.withRequestContext(): WorkspaceOperationDefinition {
    if (kind == WorkspaceOperationKind.QUERY && !jobBacked) return this
    val fields = requestSchema["properties"]?.jsonObject.orEmpty().toMutableMap()
    fields["request_id"] = buildJsonObject {
        put("type", "string"); put("minLength", 1); put("maxLength", 128)
        put("description", "Unique request ID. Retry unchanged arguments with this ID to recover the same result; use a new ID for a new attempt.")
    }
    if (workspaceBound) {
        fields["project_id"] = buildJsonObject {
            put("type", if (kind == WorkspaceOperationKind.PROJECT) JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))) else JsonPrimitive("string"))
            put("minLength", 1); put("description", "Current project_id from workspace_inspect; null only when no project is loaded.")
        }
        fields["state"] = buildJsonObject {
            put("type", "string"); put("minLength", 1)
            put("description", "Opaque state from workspace_inspect or the preceding result. Includes load generation and durable version; history node IDs are not state tokens.")
        }
    }
    val contextNames = if (workspaceBound) setOf("request_id", "project_id", "state") else setOf("request_id")
    val contextFields = fields.filterKeys { it in contextNames }
    fun contextual(schema: JsonObject): JsonObject {
        val next = schema.toMutableMap()
        next["properties"] = JsonObject(schema["properties"]?.jsonObject.orEmpty() + contextFields)
        next["required"] = JsonArray((schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content } + contextNames)
            .distinct().map(::JsonPrimitive))
        // These branches validate the same request object, so each strict branch must admit its context.
        schema["oneOf"]?.jsonArray?.let { branches ->
            next["oneOf"] = JsonArray(branches.map { contextual(it.jsonObject) })
        }
        return JsonObject(next)
    }
    return copy(idempotent = true, requestSchema = contextual(requestSchema))
}
