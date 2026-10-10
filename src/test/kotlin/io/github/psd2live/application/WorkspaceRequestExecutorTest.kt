package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceRequestExecutorTest {
    private class Backend : WorkspaceStatePort {
        var token = "load:1:0"
        var project = "project"
        override fun snapshot() = WorkspaceProjectSnapshot(project, "revision", "head", true, "art", 4, 4,
            false, "ready", null, emptyList(), emptyList(), state = token)
    }
    private val definition = WorkspaceOperationDefinition("settings_update", "Edit one setting", buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonObject("properties") { putJsonObject("value") { put("type", "integer") } }
        put("required", JsonArray(listOf(JsonPrimitive("value"))))
    }, WorkspaceOperationKind.DOCUMENT, resultSchema = WorkspaceResultSchema.obj(mapOf("state" to WorkspaceResultSchema.handle()), emptySet()))
    private fun request(id: String = "request", state: String = "load:1:0", value: Int = 1, project: String = "project") = buildJsonObject {
        put("request_id", id); put("state", state); put("project_id", project); put("value", value)
    }
    private val context = WorkspaceOperationContext(MutationAuthor.AGENT)

    @Test fun strictRootVariantsAdmitSharedContextWithoutAllowingAnotherVariantsFields() = runBlocking<Unit> {
        val s = WorkspaceResultSchema
        val nested = s.obj(mapOf("amount" to s.number()))
        val business = s.union(listOf(
            s.obj(mapOf("mode" to s.constant("start"), "clip_id" to s.handle())),
            s.obj(mapOf("mode" to s.constant("seek"), "edit" to nested)),
        ))
        WorkspaceRequestExecutor(Backend()).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            var calls = 0
            registry.register(WorkspaceOperationDefinition("preview_control", "Control a preview", business,
                WorkspaceOperationKind.SESSION, resultSchema = s.obj(mapOf("mode" to s.handle())))) { fields, _ ->
                calls++
                assertFalse("request_id" in fields || "project_id" in fields || "state" in fields)
                WorkspaceOperationOutput(buildJsonObject { put("mode", fields.getValue("mode")) })
            }
            fun input(id: String, fields: JsonObject) = JsonObject(fields + buildJsonObject {
                put("request_id", id); put("project_id", "project"); put("state", "load:1:0")
            })
            val start = input("start", buildJsonObject { put("mode", "start"); put("clip_id", "clip") })
            val seek = input("seek", buildJsonObject { put("mode", "seek"); put("edit", buildJsonObject { put("amount", 0.5) }) })
            for (request in listOf(start, seek)) {
                validateOperationSchema(request, registry.definition("preview_control").requestSchema)
                registry.invoke("preview_control", request, context)
            }
            for (invalid in listOf(JsonObject(start + ("edit" to seek.getValue("edit"))),
                JsonObject(seek + ("edit" to buildJsonObject { put("amount", 0.5); put("request_id", "nested") })),
                JsonObject(start - "project_id"))) {
                assertFailsWith<WorkspaceValidationException> { registry.invoke("preview_control", invalid, context) }
            }
            assertEquals(2, calls)
        }
    }

    @Test fun disconnectedWaiterAndConcurrentRetriesShareOneProcessOwnedExecution() = runBlocking {
        val backend = Backend()
        WorkspaceRequestExecutor(backend).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executions = 0
            registry.register(definition) { business, _ ->
                executions++
                assertEquals(setOf("value"), business.keys)
                assertEquals("load:1:0", currentCoroutineContext()[WorkspaceExecution]!!.state)
                entered.complete(Unit); release.await()
                backend.token = "load:1:1"
                WorkspaceOperationOutput(buildJsonObject { put("state", backend.token) })
            }
            val first = launch { registry.invoke("settings_update", request(), context) }
            entered.await(); first.cancelAndJoin()
            val retry = async { registry.invoke("settings_update", request(), context) }
            release.complete(Unit)
            assertEquals("load:1:1", retry.await().data.getValue("state").jsonPrimitive.content)
            assertEquals(retry.await(), registry.invoke("settings_update", request(), context))
            assertEquals(1, executions)
            assertFailsWith<WorkspaceRequestReuse> { registry.invoke("settings_update", request(value = 2), context) }
            assertFailsWith<WorkspaceRequestReuse> { registry.invoke("settings_update", request(), WorkspaceOperationContext(MutationAuthor.USER)) }
            registry.register(definition.copy(id = "parameter_update")) { _, _ -> error("Must reject reused operation") }
            assertFailsWith<WorkspaceRequestReuse> { registry.invoke("parameter_update", request(), context) }
            Unit
        }
    }

    @Test fun wrongProjectHistoryNodeAndReopenedGenerationAreRejectedBeforeExecution() = runBlocking {
        val backend = Backend()
        WorkspaceRequestExecutor(backend).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            var executions = 0
            registry.register(definition) { _, _ -> executions++; WorkspaceOperationOutput(buildJsonObject {}) }
            assertFailsWith<WorkspaceProjectConflict> { registry.invoke("settings_update", request(project = "other"), context) }
            assertFailsWith<WorkspaceConflict> { registry.invoke("settings_update", request(id = "head", state = "head"), context) }
            backend.token = "load:2:0"
            assertFailsWith<WorkspaceConflict> { registry.invoke("settings_update", request(id = "old"), context) }
            assertEquals(0, executions)
            registry.invoke("settings_update", request(id = "new", state = backend.token), context)
            assertEquals(1, executions)
        }
    }

    @Test fun failuresAreRetainedAndMissingSharedFieldsCannotReachTheHandler() = runBlocking {
        WorkspaceRequestExecutor(Backend()).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            var executions = 0
            registry.register(definition) { _, _ -> executions++; error("Rejected candidate") }
            val schema = registry.definition("settings_update").requestSchema
            assertTrue(schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("project_id", "state", "request_id")))
            for (field in listOf("project_id", "state", "request_id")) {
                assertFailsWith<WorkspaceValidationException> { registry.invoke("settings_update", JsonObject(request() - field), context) }
            }
            repeat(2) { assertFailsWith<IllegalStateException> { registry.invoke("settings_update", request(), context) } }
            assertEquals(1, executions)
        }
    }

    @Test fun onlyTheNewestSettledRequestsAreRemembered() = runBlocking {
        WorkspaceRequestExecutor(Backend()).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            val executions = mutableMapOf<Int, Int>()
            registry.register(definition) { business, _ ->
                val value = business.getValue("value").jsonPrimitive.int
                executions[value] = (executions[value] ?: 0) + 1
                WorkspaceOperationOutput(buildJsonObject { put("state", "load:1:0") })
            }
            for (i in 0..600) registry.invoke("settings_update", request(id = "r$i", value = i), context)
            registry.invoke("settings_update", request(id = "r600", value = 600), context)
            assertEquals(1, executions[600], "a recent retry shares its first result")
            registry.invoke("settings_update", request(id = "r0", value = 0), context)
            assertEquals(2, executions[0], "an evicted request runs again")
        }
    }
}
