package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentMcpServiceTest {
	private val token = "test-token-that-is-at-least-thirty-two-characters"
	private val workspace = FakeAgentWorkspace()
	private val service = AgentMcpService(workspace, AgentMcpConfig(port = 0, token = token))
	private val httpClient = HttpClient(CIO) {
		install(SSE)
		defaultRequest {
			headers { append(HttpHeaders.Authorization, "Bearer $token") }
		}
	}
	private lateinit var client: Client
	private lateinit var endpoint: String

	@BeforeAll
	fun beforeAll() = runBlocking {
		val connection = service.start()
		endpoint = connection.endpoint
		client = httpClient.mcpStreamableHttp(endpoint)
	}

	@AfterAll
	fun afterAll() = runBlocking {
		if (::client.isInitialized) client.close()
		httpClient.close()
		service.close()
	}

	@Test
	fun `negotiates read only server capabilities and identity`() {
		assertEquals("psd2live", client.serverVersion?.name)
		assertNotNull(client.serverCapabilities?.tools)
		assertNotNull(client.serverCapabilities?.prompts)
		assertNotNull(client.serverCapabilities?.resources)
	}

	@Test
	fun `rejects clients without the bearer token`() = runBlocking {
		val unauthenticated = HttpClient(CIO)
		try {
			val response = unauthenticated.post(endpoint) {
				contentType(ContentType.Application.Json)
				setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
			}
			assertEquals(HttpStatusCode.Unauthorized, response.status)
		} finally {
			unauthenticated.close()
		}
	}

	@Test
	fun `lists and calls project and direct view tools`() = runBlocking {
		val tools = client.listTools().tools
		val expectedTools = setOf(
			"project_get_state",
			"project_list_layers",
			"project_list_parameters",
			"parameter_create",
			"parameter_update",
			"parameter_delete",
			"history_list",
			"history_checkout",
			"task_start",
			"task_update",
			"task_get",
			"task_list",
			"view_render_layer",
			"view_render_context",
			"view_render_model",
			"asset_import_png",
			"layer_add_from_asset",
			"layer_soft_delete",
		)
		assertEquals(expectedTools, tools.map { it.name }.toSet())
		val readOnlyToolNames = setOf(
			"project_get_state",
			"project_list_layers",
			"project_list_parameters",
			"history_list",
			"task_get",
			"task_list",
			"view_render_layer",
			"view_render_context",
			"view_render_model",
		)
		assertTrue(tools.filter { it.name in readOnlyToolNames }.all { it.annotations?.readOnlyHint == true })
		assertTrue(tools.filterNot { it.name in readOnlyToolNames }.all { it.annotations?.readOnlyHint == false })

		val state = client.callTool("project_get_state", emptyMap())
		val json = state.structuredContent?.jsonObject
		assertEquals("revision-test", json?.get("revisionId")?.jsonPrimitive?.content)
		val parameters = client.callTool("project_list_parameters", emptyMap()).structuredContent?.jsonObject
		assertEquals("1", parameters?.get("parameterCount")?.jsonPrimitive?.content)
		assertEquals(
			"ParamAngleX",
			parameters?.get("parameters")?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content,
		)
		val layers = client.callTool("project_list_layers", emptyMap()).structuredContent?.jsonObject
		val layer = layers?.get("layers")?.jsonArray?.single()?.jsonObject
		assertEquals("200", layer?.get("rasterWidth")?.jsonPrimitive?.content)
		assertEquals("1.0", layer?.get("canvasUnitsPerSourcePixelX")?.jsonPrimitive?.content)

		val view = client.callTool(
			name = "view_render_layer",
			arguments = mapOf("layer_id" to "hair-front", "background" to "transparent", "target_long_edge" to 1024),
		)
		assertIs<TextContent>(view.content.first())
		val image = assertIs<ImageContent>(view.content.last())
		assertEquals("image/png", image.mimeType)
		assertTrue(image.data.isNotBlank())
		assertEquals("hair-front", workspace.lastRenderedLayer)

		val posed = client.callTool(
			name = "view_render_model",
			arguments = mapOf(
				"parameters" to mapOf("ParamAngleX" to 10),
				"include_layer_ids" to listOf("hair-front"),
				"annotate_layer_ids" to listOf("hair-front"),
				"viewport" to mapOf(
					"mode" to "focus_layers",
					"layer_ids" to listOf("hair-front"),
					"object_scale" to 0.6,
					"aspect_ratio" to 1.5,
				),
				"target_long_edge" to 768,
			),
		)
		assertIs<ImageContent>(posed.content.last())
		assertEquals(10f, workspace.lastModelRequest?.parameters?.get("ParamAngleX"))
		assertEquals(setOf("hair-front"), workspace.lastModelRequest?.includeLayerIds)
		assertEquals(setOf("hair-front"), workspace.lastModelRequest?.annotateLayerIds)
		assertEquals(
			AgentViewFrame.FocusLayers(setOf("hair-front"), objectScale = 0.6f, aspectRatio = 1.5f),
			workspace.lastModelRequest?.frame,
		)
		assertEquals(768, workspace.lastModelRequest?.output?.targetLongEdge)
		val spatial = posed.structuredContent?.jsonObject?.get("spatial")?.jsonObject
		assertEquals("canvas_top_left_y_down", spatial?.get("coordinateSpace")?.jsonPrimitive?.content)
		assertEquals("view-test", spatial?.get("spatialReferenceId")?.jsonPrimitive?.content)

		client.callTool(
			name = "view_render_model",
			arguments = mapOf(
				"viewport" to mapOf(
					"mode" to "canvas_rect",
					"left" to 100,
					"top" to 200,
					"width" to 300,
					"height" to 150,
				),
			),
		)
		assertEquals(
			AgentViewFrame.CanvasRect(Bounds(100f, 200f, 400f, 350f)),
			workspace.lastModelRequest?.frame,
		)
	}

	@Test
	fun `serves bundled hair separation skill as prompt`() = runBlocking {
		val prompts = client.listPrompts().prompts
		assertEquals(listOf("hair-separation"), prompts.map { it.name })
		val prompt = client.getPrompt(GetPromptRequest(GetPromptRequestParams(name = "hair-separation")))
		val text = assertIs<TextContent>(prompt.messages.single().content).text
		assertTrue(text.contains("non-destructive", ignoreCase = true))
		assertTrue(text.contains("occlusion", ignoreCase = true))
	}

	private class FakeAgentWorkspace : AgentWorkspace {
		var lastRenderedLayer: String? = null
		var lastModelRequest: AgentModelViewRequest? = null

		override fun snapshot() = AgentProjectSnapshot(
			projectId = "project-test",
			revisionId = "revision-test",
			loaded = true,
			inputName = "character.psd",
			canvasWidth = 1024,
			canvasHeight = 1024,
			busy = false,
			status = "Ready",
			selectedLayerId = "hair-front",
			layers = listOf(
				AgentLayerSnapshot(
					id = "hair-front",
					sourceName = "front hair",
					rasterWidth = 200,
					rasterHeight = 400,
					groupPath = "head/hair",
					order = 0,
					semanticTag = "front_hair",
					side = "none",
					confidence = 0.98f,
					bounds = Bounds(10f, 12f, 210f, 412f),
					opaqueBounds = Bounds(12f, 14f, 208f, 410f),
					visible = true,
					deleted = false,
				),
			),
			parameters = listOf(
				AgentParameterSnapshot("ParamAngleX", "Angle X", -45f, 45f, 0f, 0f, "normal"),
			),
		)

		override suspend fun renderLayer(
			layerId: String,
			background: AgentViewBackground,
			output: AgentViewOutputSpec,
		): AgentRenderedView {
			lastRenderedLayer = layerId
			return view("layer-transparent", layerId)
		}

		override suspend fun renderContext(
			layerId: String,
			objectScale: Float,
			aspectRatio: Float,
			background: AgentViewBackground,
			output: AgentViewOutputSpec,
		) = view("context-composite-png", layerId)

		override suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView {
			lastModelRequest = request
			return view("model-parameterized", request.annotateLayerIds.firstOrNull() ?: "hair-front").copy(
				appliedParameters = request.parameters,
				includedLayerIds = request.includeLayerIds.orEmpty().sorted(),
				annotatedLayerIds = request.annotateLayerIds.sorted(),
			)
		}

		private fun view(kind: String, layerId: String) = AgentRenderedView(
			viewId = "view-test",
			revisionId = "revision-test",
			kind = kind,
			objectIds = listOf(layerId),
			png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
			originalWidth = 200,
			originalHeight = 400,
			renderedWidth = 200,
			renderedHeight = 400,
			canvasRect = Bounds(10f, 12f, 210f, 412f),
			scale = 1f,
			sha256 = "0".repeat(64),
			spatial = AgentViewSpatialMetadata(
				pixelWidth = 200,
				pixelHeight = 400,
				canvasWidth = 1024f,
				canvasHeight = 1024f,
				requestedViewRect = Bounds(10f, 12f, 210f, 412f),
				viewRect = Bounds(10f, 12f, 210f, 412f),
				canvasUnitsPerPixelX = 1f,
				canvasUnitsPerPixelY = 1f,
			),
		)
	}
}
