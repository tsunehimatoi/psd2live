package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthoringParityTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java),
    ) { _, method, _ -> if (method.name == "getSessionId") "test" else error("Unexpected MCP client call") } as ClientConnection
    private class Workspace : AgentWorkspace {
        var classified: Pair<String, LayerClassificationOverride>? = null
        override fun snapshot() = AgentProjectSnapshot(
            projectId = "test", revisionId = "revision", historyHeadNodeId = "head", loaded = true,
            inputName = "test", canvasWidth = 32, canvasHeight = 32, busy = false, status = "ready",
            selectedLayerId = null, parameters = emptyList(),
            layers = listOf(AgentLayerSnapshot(
                id = "hair", sourceName = "hair", rasterWidth = 16, rasterHeight = 16,
                groupPath = "", order = 0, semanticTag = "front_hair", side = "left",
                confidence = 1f, bounds = Bounds(0f, 0f, 16f, 16f),
                opaqueBounds = Bounds(0f, 0f, 16f, 16f), visible = true, deleted = false,
            )),
        )
        override suspend fun classifyLayer(layerId: String, classification: LayerClassificationOverride, expectedHead: String): AgentWorkspaceMutationResult {
            assertEquals("head", expectedHead)
            classified = layerId to classification
            return AgentWorkspaceMutationResult("next", "revision-next", listOf(layerId), "classified")
        }
        override suspend fun renderLayer(layerId: String, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
        override suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView = error("unused")
    }

    @Test fun classificationMergesOmittedFieldsAndRejectsInvalidRequests() = runBlocking {
        val workspace = Workspace()
        val server = createAgentMcpServer(workspace)
        assertEquals(21, server.tools.size)
        val layer = server.tools.getValue("layer")
        val result = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer", buildJsonObject {
            put("state", "head"); put("layer_id", "hair"); put("type", "switch")
            put("parameter", "expression"); put("switch_id", 2)
        })))
        assertFalse(result.isError == true)
        assertEquals("next", result.structuredContent?.get("state")?.jsonPrimitive?.content)
        assertEquals("hair", workspace.classified?.first)
        assertEquals(LayerType.SWITCH, workspace.classified?.second?.type)
        assertEquals(SemanticTag.FRONT_HAIR, workspace.classified?.second?.tag)
        assertEquals("expression", workspace.classified?.second?.parameter)
        assertEquals(2, workspace.classified?.second?.switchId)

        workspace.classified = null
        val invalid = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer", buildJsonObject {
            put("state", "head"); put("layer_id", "hair"); put("switch_id", -1)
        })))
        assertTrue(invalid.isError == true)
        assertNull(workspace.classified)
    }

    @Test fun publicSurfaceIncludesParityRoutes() {
        val server = createAgentMcpServer(Workspace())
        assertTrue(server.tools.keys.containsAll(listOf("layer", "layer_mesh", "paint", "preview", "structure", "canvas", "settings", "parameter", "export", "export_psd", "swing")))
        assertTrue(server.tools.getValue("asset").tool.inputSchema.properties.toString().contains("psd"))
        assertFalse("parameter_delete" in server.tools.keys)
        assertTrue(server.tools.getValue("parameter").tool.inputSchema.properties.toString().contains("delete"))
    }
}
