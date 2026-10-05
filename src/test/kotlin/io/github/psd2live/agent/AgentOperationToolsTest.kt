package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.quality.*
import io.github.psd2live.project.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentOperationToolsTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "test" else error("Unexpected client notification")
    } as ClientConnection

    private class Backend : WorkspaceBackendStub() {
        val release = CompletableDeferred<Unit>()
        var exports = 0
        override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true, "artwork",
            4, 4, false, "ready", null, emptyList(), emptyList(), state = "generation:0")
        override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Not used")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Not used")
        override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView = error("Not used")
        override suspend fun exportModel(state: String, outputDirectory: String): JsonObject {
            exports++
            release.await()
            return buildJsonObject {
                put("state", state); put("revision", "revision"); put("warnings", JsonArray(emptyList()))
                put("quality", QualityInspection.combine(QualityFence.EXPORT_PUBLICATION, emptyList()).toJson())
                putJsonArray("files") { add(buildJsonObject { put("path", outputDirectory + "/model.cmo3"); put("bytes", 42) }) }
            }
        }
    }

    @Test fun publishedSchemaIsTheExactRegistryContractAndValidationPrecedesExecution() = runBlocking {
        val backend = Backend()
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations = operations)
            val tool = server.tools.getValue("project_export_model")
            assertEquals(listOf("request"), tool.tool.inputSchema.required)
            assertEquals(operations.registry.definition("project_export_model").requestSchema,
                tool.tool.inputSchema.properties!!.getValue("request"))
            assertFalse("export" in server.tools)
            val result = tool.handler.invoke(connection, CallToolRequest(CallToolRequestParams("project_export_model",
                buildJsonObject { putJsonObject("request") { put("state", "head"); put("output_directory", "/out"); put("unexpected", 1) } })))
            assertTrue(result.isError == true)
            assertEquals("invalid_request", result.structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(0, backend.exports)
        }
    }

    @Test fun exportJobSurvivesCancelledWaitAndRequestRetryDoesNotExportAgain() = runBlocking {
        val backend = Backend()
        WorkspaceOperations(backend).use { operations ->
            val firstServer = createAgentMcpServer(backend, operations = operations)
            suspend fun call(server: io.modelcontextprotocol.kotlin.sdk.server.Server, id: String, request: JsonObject) =
                server.tools.getValue(id).handler.invoke(connection, CallToolRequest(CallToolRequestParams(id,
                    buildJsonObject { put("request", request) }))).structuredContent!!.getValue("data").jsonObject
            val request = buildJsonObject { put("request_id", "export-1"); put("project_id", "project"); put("state", "generation:0"); put("output_directory", "/out") }
            val started = call(firstServer, "project_export_model", request)
            val id = started.getValue("id").jsonPrimitive.content
            val waiting = launch { call(firstServer, "job_wait", buildJsonObject { put("id", id) }) }
            yield()
            waiting.cancelAndJoin()
            val reconnected = createAgentMcpServer(backend, operations = operations)
            assertEquals(id, call(reconnected, "project_export_model", request).getValue("id").jsonPrimitive.content)
            backend.release.complete(Unit)
            val completed = call(reconnected, "job_wait", buildJsonObject { put("id", id) })
            assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
            assertEquals("/out/model.cmo3", completed.getValue("result").jsonObject.getValue("files").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content)
            assertEquals(1, backend.exports)
            assertFailsWith<IllegalArgumentException> {
                operations.registry.invoke("project_export_model", JsonObject(request + ("output_directory" to JsonPrimitive("/another"))),
                    WorkspaceOperationContext(MutationAuthor.AGENT))
            }
            Unit
        }
    }

    @Test fun capabilitiesPageAndDetailDescribeThePublishedOperation() = runBlocking {
        WorkspaceOperations(Backend()).use { operations ->
            val context = WorkspaceOperationContext(MutationAuthor.AGENT)
            val page = operations.registry.invoke("workspace_list_operations", buildJsonObject {
                put("domain", "skeleton"); put("limit", 2)
            }, context).data
            assertEquals(2, page.getValue("items").jsonArray.size)
            assertEquals(2, page.getValue("next").jsonPrimitive.int)
            val id = page.getValue("items").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
            val detail = operations.registry.invoke("workspace_get_operation", buildJsonObject { put("id", id) }, context).data
            assertEquals(operations.registry.definition(id).requestSchema, detail.getValue("request_schema"))
            assertEquals(operations.registry.definition(id).kind.name.lowercase(), detail.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test fun requestEnvelopeAndOperationFieldsRejectUnknownArguments() = runBlocking {
        WorkspaceOperations(Backend()).use { operations ->
            val server = createAgentMcpServer(Backend(), operations)
            for (arguments in listOf(
                buildJsonObject { putJsonObject("request") {}; put("extra", true) },
                buildJsonObject { putJsonObject("request") { put("mode", "get") } },
                buildJsonObject { put("request", "get") },
            )) {
                val result = server.tools.getValue("skeleton_get").handler.invoke(connection,
                    CallToolRequest(CallToolRequestParams("skeleton_get", arguments)))
                assertTrue(result.isError == true)
                assertEquals("invalid_request", result.structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test fun authoringPublicationRetainsVariantsAndFieldHelp() {
        val server = createAgentMcpServer(Backend())
        val definitions = WorkspaceOperations(Backend()).use { operations -> operations.registry.definitions().associateBy { it.id } }
        server.tools.values.forEach { registered ->
            assertEquals(listOf("request"), registered.tool.inputSchema.required)
            assertEquals(setOf("request"), registered.tool.inputSchema.properties!!.keys)
        }
        val skeleton = server.tools.getValue("skeleton_put").tool.inputSchema.properties!!.getValue("request").jsonObject
        assertEquals(setOf("project_id", "state", "request_id", "spec"), skeleton.getValue("properties").jsonObject.keys)
        assertTrue(server.tools.keys.intersect(setOf("skeleton", "motion", "asset", "revision")).isEmpty())
        val asset = server.tools.getValue("asset_prepare_reference").tool.inputSchema.properties!!.getValue("request").jsonObject
        assertTrue(asset.getValue("properties").jsonObject.values.any { it is JsonObject && "description" in it })
        assertTrue(server.tools.getValue("skeleton_get").tool.annotations!!.readOnlyHint == true)
        assertFalse(server.tools.getValue("skeleton_put").tool.annotations!!.readOnlyHint == true)
        server.tools.values.filter { it.tool.annotations?.readOnlyHint != true }.forEach { registered ->
            val request = registered.tool.inputSchema.properties!!.getValue("request").jsonObject
            val required = request.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("request_id" in required, registered.tool.name)
            val workspaceBound = definitions.getValue(registered.tool.name).workspaceBound
            if (workspaceBound) assertTrue(required.containsAll(listOf("project_id", "state")), registered.tool.name)
            else assertTrue(listOf("project_id", "state").none { it in required }, registered.tool.name)
        }
    }
}
