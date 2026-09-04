package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

data class AgentMcpConfig(
	val host: String = "127.0.0.1",
	val port: Int = 23871,
	val token: String = AgentMcpCredentials.loadOrCreateToken(),
)

data class AgentMcpConnectionInfo(
	val endpoint: String,
	val token: String,
) {
	val configToml: String
		get() = """
			[mcp_servers.psd2live]
			url = "$endpoint"
			http_headers = { Authorization = "Bearer $token" }
		""".trimIndent()
}

class AgentMcpService(
	private val workspace: AgentWorkspace,
	private val config: AgentMcpConfig = AgentMcpConfig(),
) : AutoCloseable {
	private var engine: EmbeddedServer<*, *>? = null

	lateinit var connectionInfo: AgentMcpConnectionInfo
		private set

	fun start(): AgentMcpConnectionInfo {
		check(engine == null) { "Agent MCP service is already running" }
		val started = embeddedServer(Netty, host = config.host, port = config.port) {
			configureAgentMcp(workspace, config.token)
		}
		started.start(wait = false)
		engine = started
		val actualPort = runBlocking { started.engine.resolvedConnectors().single().port }
		return AgentMcpConnectionInfo(
			endpoint = "http://${config.host}:$actualPort/mcp",
			token = config.token,
		).also { connectionInfo = it }
	}

	override fun close() {
		engine?.stop(gracePeriodMillis = 250, timeoutMillis = 1_500)
		engine = null
		(workspace as? AutoCloseable)?.close()
	}
}

object AgentMcpCredentials {
	private const val TOKEN_KEY = "agent_mcp_bearer_token"
	private val preferences: Preferences by lazy { Preferences.userNodeForPackage(AgentMcpCredentials::class.java) }

	fun loadOrCreateToken(): String {
		preferences.get(TOKEN_KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
		val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { preferences.put(TOKEN_KEY, it) }
	}

	fun rotateToken(): String {
		preferences.remove(TOKEN_KEY)
		return loadOrCreateToken()
	}
}

internal fun Application.configureAgentMcp(workspace: AgentWorkspace, authToken: String) {
	require(authToken.length >= 32) { "Agent MCP bearer token is too short" }
	install(ContentNegotiation) { json(McpJson) }
	install(SSE)
	install(Authentication) {
		bearer("agent-mcp-bearer") {
			authenticate { credential ->
				if (credential.token == authToken) UserIdPrincipal("agent-mcp-client") else null
			}
		}
	}

	val transports = ConcurrentMap<String, StreamableHttpServerTransport>()
	routing {
		authenticate("agent-mcp-bearer") {
			route("/mcp") {
				sse {
					val transport = findTransport(call.request.header(MCP_SESSION_ID_HEADER), transports)
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@sse
					}
					transport.handleRequest(this, call)
				}

				post {
					val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
					val transport = if (sessionId == null) {
						createTransport(workspace, transports)
					} else {
						findTransport(sessionId, transports)
					}
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@post
					}
					transport.handleRequest(null, call)
				}

				delete {
					val transport = findTransport(call.request.header(MCP_SESSION_ID_HEADER), transports)
					if (transport == null) {
						call.respond(HttpStatusCode.NotFound, "MCP session not found")
						return@delete
					}
					transport.handleRequest(null, call)
				}
			}
		}
	}
}

private fun findTransport(
	sessionId: String?,
	transports: ConcurrentMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? = sessionId?.let(transports::get)

private suspend fun createTransport(
	workspace: AgentWorkspace,
	transports: ConcurrentMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport {
	val transport = StreamableHttpServerTransport(
		StreamableHttpServerTransport.Configuration(enableJsonResponse = true),
	)
	transport.setOnSessionInitialized { sessionId -> transports[sessionId] = transport }
	transport.setOnSessionClosed { sessionId -> transports.remove(sessionId) }
	val server = createAgentMcpServer(workspace)
	server.onClose { transport.sessionId?.let(transports::remove) }
	server.createSession(transport)
	return transport
}

internal fun createAgentMcpServer(workspace: AgentWorkspace): Server {
	val server = Server(
		serverInfo = Implementation("psd2live", "0.2.0"),
		options = ServerOptions(
			ServerCapabilities(
				prompts = ServerCapabilities.Prompts(listChanged = false),
				resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
				tools = ServerCapabilities.Tools(listChanged = false),
			),
		),
		instructions = AGENT_INSTRUCTIONS,
	)

	server.addTool(
		name = "project_get_state",
		description = "Read the current PSD2Live project, revision, selection, canvas and summary. Call this before planning work.",
		toolAnnotations = READ_ONLY,
	) {
		val json = workspace.snapshot().toJson(includeLayers = false)
		jsonResult(json)
	}

	server.addTool(
		name = "project_list_layers",
		description = "List stable layer IDs, semantic labels, bounds, visibility and deletion state without reading PSD binary data.",
		inputSchema = ToolSchema(
			properties = buildJsonObject {
				putJsonObject("semantic_tag") {
					put("type", "string")
					put("description", "Optional lowercase semantic tag filter, such as front_hair or unknown")
				}
				putJsonObject("include_deleted") {
					put("type", "boolean")
					put("description", "Include soft-deleted layers; defaults to false")
				}
			},
		),
		toolAnnotations = READ_ONLY,
	) { request ->
		val semanticTag = request.arguments?.get("semantic_tag")?.jsonPrimitive?.contentOrNull
		val includeDeleted = request.arguments?.get("include_deleted")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
		val snapshot = workspace.snapshot()
		val layers = snapshot.layers.filter { layer ->
			(includeDeleted || !layer.deleted) && (semanticTag == null || layer.semanticTag == semanticTag.lowercase())
		}
		jsonResult(snapshot.toJson(includeLayers = true, layers = layers))
	}

	server.addTool(
		name = "project_list_parameters",
		description = "List every parameter ID, range, default, current value and kind in the evaluated rig.",
		toolAnnotations = READ_ONLY,
	) {
		val snapshot = workspace.snapshot()
		jsonResult(buildJsonObject {
			put("revisionId", snapshot.revisionId)
			put("parameterCount", snapshot.parameters.size)
			put("parameters", JsonArray(snapshot.parameters.map(AgentParameterSnapshot::toJson)))
		})
	}

	server.addTool(
		name = "parameter_create",
		description = "Create a real Cubism parameter in the authoritative rig. It survives layer/mesh rebuilds, history checkout, restart and export.",
		inputSchema = parameterCreateSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.createParameter(
				AgentCreateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					name = request.requiredString("name"),
					min = request.float("min", -1f),
					max = request.float("max", 1f),
					default = request.float("default", 0f),
					kind = request.optionalString("kind") ?: "normal",
					repeat = request.boolean("repeat", false),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "parameter_update",
		description = "Update a Cubism parameter's name, numeric range/default, kind, or repeat flag. The stable parameter ID is not renamed.",
		inputSchema = parameterUpdateSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.updateParameter(
				AgentUpdateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					name = request.optionalString("name"),
					min = request.optionalFloat("min"),
					max = request.optionalFloat("max"),
					default = request.optionalFloat("default"),
					kind = request.optionalString("kind"),
					repeat = request.optionalBoolean("repeat"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "parameter_delete",
		description = "Delete a Cubism parameter and safely collapse every drawable, deformer, part and glue keyform axis at its prior default. History remains recoverable.",
		inputSchema = ToolSchema(
			properties = buildJsonObject {
				putJsonObject("parameter_id") { put("type", "string") }
				putJsonObject("expected_history_head_node_id") { put("type", "string") }
				putJsonObject("task_id") { put("type", "string") }
			},
			required = listOf("parameter_id", "expected_history_head_node_id"),
		),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.deleteParameter(
				parameterId = request.requiredString("parameter_id"),
				expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}

	server.addTool(
		name = "history_list",
		description = "Read the append-only branch-preserving workspace history and current HEAD. Nodes cannot be edited or deleted.",
		toolAnnotations = READ_ONLY,
	) {
		mutationResult { workspace.history().toJson() }
	}

	server.addTool(
		name = "task_start",
		description = "Create a resumable in-app checkpoint record from the Agent's own dynamic plan. This does not prescribe or approve the workflow.",
		inputSchema = ToolSchema(
			properties = buildJsonObject {
				putJsonObject("objective") { put("type", "string") }
				putJsonObject("plan") {
					put("type", "array")
					putJsonObject("items") { put("type", "string") }
				}
			},
			required = listOf("objective", "plan"),
		),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.startTask(request.requiredString("objective"), request.stringList("plan")).toJson()
		}
	}

	server.addTool(
		name = "task_update",
		description = "Append a progress/checkpoint event to a long Agent task, including artifact/view/asset/history references.",
		inputSchema = taskUpdateSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			val status = runCatching { AgentTaskStatus.valueOf(request.requiredString("status").uppercase()) }
				.getOrElse { throw IllegalArgumentException("Unknown task status") }
			workspace.updateTask(
				taskId = request.requiredString("task_id"),
				status = status,
				plan = request.optionalStringList("plan"),
				currentStep = request.optionalInteger("current_step"),
				progress = request.optionalFloat("progress"),
				message = request.optionalString("message").orEmpty(),
				artifactIds = request.stringList("artifact_ids", required = false),
			).toJson()
		}
	}

	server.addTool(
		name = "task_get",
		description = "Read one long task with its Agent-authored plan, status, progress, artifacts and append-only event log.",
		inputSchema = ToolSchema(
			properties = buildJsonObject { putJsonObject("task_id") { put("type", "string") } },
			required = listOf("task_id"),
		),
		toolAnnotations = READ_ONLY,
	) { request -> mutationResult { workspace.task(request.requiredString("task_id")).toJson() } }

	server.addTool(
		name = "task_list",
		description = "List long tasks persisted for the currently loaded PSD version.",
		toolAnnotations = READ_ONLY,
	) {
		jsonResult(buildJsonObject {
			putJsonArray("tasks") { workspace.tasks().forEach { add(it.toJson(includeEvents = false)) } }
		})
	}

	server.addTool(
		name = "view_render_layer",
		description = "Render one layer directly from RGBA model data as transparent or checkerboard PNG. This is not a UI screenshot.",
		inputSchema = viewSchema(includeBackground = true),
		toolAnnotations = READ_ONLY,
	) { request ->
		renderResult {
			val layerId = request.requiredString("layer_id")
			val background = request.arguments?.get("background")?.jsonPrimitive?.contentOrNull
				?.uppercase()?.let(AgentViewBackground::valueOf) ?: AgentViewBackground.TRANSPARENT
			workspace.renderLayer(layerId, background, request.outputSpec())
		}
	}

	server.addTool(
		name = "view_render_context",
		description = "Composite visible layers into one focused PNG around a layer. object_scale below 1 includes surrounding context; this never returns a PSD or UI screenshot.",
		inputSchema = viewSchema(includeBackground = true, includeFocus = true),
		toolAnnotations = READ_ONLY,
	) { request ->
		renderResult {
			workspace.renderContext(
				layerId = request.requiredString("layer_id"),
				objectScale = request.float("object_scale", 0.65f),
				aspectRatio = request.float("aspect_ratio", 1f),
				background = request.background(),
				output = request.outputSpec(),
			)
		}
	}

	server.addTool(
		name = "view_render_model",
		description = "Evaluate a rig pose, composite selected layers into one PNG, and render only an explicit canvas rectangle or a focused object region. Returns reversible pixel-to-canvas placement metadata.",
		inputSchema = modelViewSchema(),
		toolAnnotations = READ_ONLY,
	) { request ->
		renderResult {
			workspace.renderModel(
				AgentModelViewRequest(
					parameters = request.floatMap("parameters"),
					includeLayerIds = request.optionalStringSet("include_layer_ids"),
					annotateLayerIds = request.optionalStringSet("annotate_layer_ids").orEmpty(),
					frame = request.viewFrame(),
					background = request.background(),
					output = request.outputSpec(),
				),
			)
		}
	}

	server.addTool(
		name = "asset_import_png",
		description = "Stage an Agent-generated transparent PNG using a View spatial reference. Pixel resolution may differ, but aspect and canvas placement are preserved. This does not change project history.",
		inputSchema = pngImportSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			val sourceRect = request.arguments?.get("source_pixel_rect")?.jsonObject?.let { rect ->
				fun coordinate(name: String): Int = rect[name]?.jsonPrimitive?.intOrNull
					?: throw IllegalArgumentException("source_pixel_rect.$name must be an integer")
				AgentPixelRect(coordinate("left"), coordinate("top"), coordinate("width"), coordinate("height"))
			}
			workspace.importPng(
				AgentPngImportRequest(
					png = decodePngBase64(request.requiredString("png_base64")),
					spatialReferenceId = request.requiredString("spatial_reference_id"),
					sourcePixelRect = sourceRect,
				),
			).toJson()
		}
	}

	server.addTool(
		name = "layer_add_from_asset",
		description = "Add a staged PNG as a real editable source layer, generate its mesh/rig through the normal pipeline, and append one immutable history node. No approval step is required.",
		inputSchema = addLayerSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.addLayer(
				AgentAddLayerRequest(
					assetId = request.requiredString("asset_id"),
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					name = request.requiredString("name"),
					layerId = request.optionalString("layer_id"),
					groupPath = request.optionalString("group_path").orEmpty(),
					insertion = request.layerInsertion(),
					semanticTag = request.optionalString("semantic_tag") ?: "unknown",
					side = request.optionalString("side") ?: "none",
					visible = request.boolean("visible", true),
					opacity = request.float("opacity", 1f),
					trimTransparent = request.boolean("trim_transparent", true),
					parentDeformerId = request.optionalString("parent_deformer_id"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "layer_soft_delete",
		description = "Remove a layer from the active model without erasing its pixels or history. It remains recoverable by history_checkout.",
		inputSchema = ToolSchema(
			properties = buildJsonObject {
				putJsonObject("layer_id") { put("type", "string"); put("description", "Stable layer ID to remove from the active workspace") }
				putJsonObject("expected_history_head_node_id") { put("type", "string"); put("description", "Current HEAD from project_get_state or history_list") }
				putJsonObject("task_id") { put("type", "string"); put("description", "Optional long-task correlation ID") }
			},
			required = listOf("layer_id", "expected_history_head_node_id"),
		),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.softDeleteLayer(
				layerId = request.requiredString("layer_id"),
				expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}

	server.addTool(
		name = "history_checkout",
		description = "Move workspace HEAD to any immutable history node and rebuild that exact editable source/rig state. Old branches remain available.",
		inputSchema = ToolSchema(
			properties = buildJsonObject {
				putJsonObject("node_id") { put("type", "string"); put("description", "History node to restore") }
			},
			required = listOf("node_id"),
		),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult { workspace.checkoutHistory(request.requiredString("node_id")).toJson() }
	}

	server.addPrompt(
		name = "hair-separation",
		description = "Expert workflow constraints for planning non-destructive hair separation and independent physics.",
	) {
		GetPromptResult(
			description = "psd2live hair separation skill",
			messages = listOf(PromptMessage(Role.User, TextContent(loadHairSeparationSkill()))),
		)
	}

	server.addResource(
		uri = "psd2live://project/current/manifest",
		name = "Current psd2live project manifest",
		description = "Live structured manifest for the project currently open in psd2live.",
		mimeType = "application/json",
	) { request ->
		ReadResourceResult(
			contents = listOf(TextResourceContents(workspace.snapshot().toJson(true).toString(), request.uri, "application/json")),
		)
	}

	return server
}

private fun pngImportSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("png_base64") {
			put("type", "string")
			put("description", "PNG bytes encoded as Base64, optionally as a data:image/png;base64 URI")
		}
		putJsonObject("spatial_reference_id") {
			put("type", "string")
			put("description", "spatialReferenceId returned by a View tool in this workspace session")
		}
		putJsonObject("source_pixel_rect") {
			put("type", "object")
			put("description", "Optional crop rectangle in the referenced View's pixel coordinates")
			putJsonObject("properties") {
				listOf("left", "top", "width", "height").forEach { name ->
					putJsonObject(name) { put("type", "integer") }
				}
			}
			putJsonArray("required") {
				listOf("left", "top", "width", "height").forEach { add(JsonPrimitive(it)) }
			}
		}
	},
	required = listOf("png_base64", "spatial_reference_id"),
)

private fun parameterCreateSchema(): ToolSchema = ToolSchema(
	properties = parameterProperties(includeRequiredValues = true),
	required = listOf("parameter_id", "expected_history_head_node_id", "name"),
)

private fun parameterUpdateSchema(): ToolSchema = ToolSchema(
	properties = parameterProperties(includeRequiredValues = false),
	required = listOf("parameter_id", "expected_history_head_node_id"),
)

private fun parameterProperties(includeRequiredValues: Boolean): JsonObject = buildJsonObject {
	putJsonObject("parameter_id") {
		put("type", "string")
		put("description", "Stable Cubism ID. Creation accepts 1-64 ASCII letters/digits/underscores and must start with a letter")
	}
	putJsonObject("expected_history_head_node_id") {
		put("type", "string")
		put("description", "Optimistic concurrency boundary returned by project_get_state or history_list")
	}
	putJsonObject("name") { put("type", "string") }
	putJsonObject("min") { put("type", "number"); if (includeRequiredValues) put("default", -1) }
	putJsonObject("max") { put("type", "number"); if (includeRequiredValues) put("default", 1) }
	putJsonObject("default") { put("type", "number"); if (includeRequiredValues) put("default", 0) }
	putJsonObject("kind") {
		put("type", "string")
		putJsonArray("enum") { listOf("normal", "blend_shape").forEach { add(JsonPrimitive(it)) } }
		if (includeRequiredValues) put("default", "normal")
	}
	putJsonObject("repeat") { put("type", "boolean"); if (includeRequiredValues) put("default", false) }
	putJsonObject("task_id") { put("type", "string") }
}

private fun addLayerSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("asset_id") { put("type", "string") }
		putJsonObject("expected_history_head_node_id") {
			put("type", "string")
			put("description", "Optimistic concurrency boundary returned by project_get_state or history_list")
		}
		putJsonObject("name") { put("type", "string") }
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Optional stable ID; a unique agent: ID is generated when omitted")
		}
		putJsonObject("group_path") { put("type", "string") }
		putJsonObject("semantic_tag") {
			put("type", "string")
			put("description", "SemanticTag enum name in lowercase, for example front_hair")
			put("default", "unknown")
		}
		putJsonObject("side") {
			put("type", "string")
			putJsonArray("enum") { listOf("left", "right", "none").forEach { add(JsonPrimitive(it)) } }
			put("default", "none")
		}
		putJsonObject("insertion") {
			put("type", "object")
			put("description", "Painter-order placement: top, bottom, above or below a stable layer ID")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") { listOf("top", "bottom", "above", "below").forEach { add(JsonPrimitive(it)) } }
				}
				putJsonObject("reference_layer_id") { put("type", "string") }
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("visible") { put("type", "boolean"); put("default", true) }
		putJsonObject("opacity") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("default", 1) }
		putJsonObject("trim_transparent") {
			put("type", "boolean")
			put("default", true)
			put("description", "Crop transparent padding after mapping the complete PNG to canvas units")
		}
		putJsonObject("parent_deformer_id") { put("type", "string") }
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("asset_id", "expected_history_head_node_id", "name"),
)

private fun taskUpdateSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("task_id") { put("type", "string") }
		putJsonObject("status") {
			put("type", "string")
			putJsonArray("enum") {
				AgentTaskStatus.entries.forEach { add(JsonPrimitive(it.name.lowercase())) }
			}
		}
		putJsonObject("current_step") {
			put("type", "integer")
			put("minimum", 0)
			put("description", "Zero-based index into the Agent-authored plan")
		}
		putJsonObject("plan") {
			put("type", "array")
			put("description", "Optional replacement for the Agent-authored dynamic plan")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("progress") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("message") { put("type", "string") }
		putJsonObject("artifact_ids") {
			put("type", "array")
			put("description", "View, PNG asset, layer or history node IDs produced at this checkpoint")
			putJsonObject("items") { put("type", "string") }
		}
	},
	required = listOf("task_id", "status"),
)

private fun viewSchema(includeBackground: Boolean, includeFocus: Boolean = false): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Stable layer ID returned by project_list_layers")
		}
		if (includeBackground) {
			putJsonObject("background") {
				put("type", "string")
				putJsonArray("enum") {
					add(JsonPrimitive("transparent"))
					add(JsonPrimitive("checkerboard"))
				}
				put("default", "transparent")
			}
		}
		if (includeFocus) {
			putJsonObject("object_scale") {
				put("type", "number")
				put("minimum", 0.05)
				put("maximum", 4.0)
				put("default", 0.65)
				put("description", "Fraction of the fitted view occupied by the focused layer; values below 1 show surroundings")
			}
			putJsonObject("aspect_ratio") {
				put("type", "number")
				put("minimum", 0.1)
				put("maximum", 10.0)
				put("default", 1.0)
				put("description", "Output frame width divided by height; the layer is fitted without stretching")
			}
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
			put("description", "Maximum encoded PNG bytes; resolution is reduced without changing canvas placement when necessary")
		}
	},
	required = listOf("layer_id"),
)

private fun modelViewSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("parameters") {
			put("type", "object")
			put("description", "Cubism parameter ID to value, for example {ParamAngleX: 10.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("include_layer_ids") {
			put("type", "array")
			put("description", "Exact layer IDs to composite. Omit to use workspace visibility; [] renders no model layers.")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("annotate_layer_ids") {
			put("type", "array")
			put("description", "Layer IDs to label and outline at their deformed positions")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("viewport") {
			put("type", "object")
			put("description", "Required camera: {mode:canvas_rect,left,top,width,height} or {mode:focus_layers,layer_ids,object_scale,aspect_ratio}")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") {
						add(JsonPrimitive("canvas_rect"))
						add(JsonPrimitive("focus_layers"))
					}
				}
				listOf("left", "top", "width", "height", "object_scale", "aspect_ratio").forEach { name ->
					putJsonObject(name) { put("type", "number") }
				}
				putJsonObject("layer_ids") {
					put("type", "array")
					putJsonObject("items") { put("type", "string") }
				}
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("background") {
			put("type", "string")
			putJsonArray("enum") {
				add(JsonPrimitive("transparent"))
				add(JsonPrimitive("checkerboard"))
			}
			put("default", "transparent")
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
		}
	},
	required = listOf("viewport"),
)

private suspend fun renderResult(block: suspend () -> AgentRenderedView): CallToolResult = try {
	val view = block()
	val metadata = view.toJson()
	CallToolResult(
		content = listOf(
			TextContent(metadata.toString()),
			ImageContent(Base64.getEncoder().encodeToString(view.png), "image/png"),
		),
		structuredContent = metadata,
	)
} catch (failure: IllegalArgumentException) {
	CallToolResult(content = listOf(TextContent(failure.message ?: "Invalid view request")), isError = true)
} catch (failure: IllegalStateException) {
	CallToolResult(content = listOf(TextContent(failure.message ?: "Project is not ready")), isError = true)
}

private suspend fun mutationResult(block: suspend () -> JsonObject): CallToolResult = try {
	jsonResult(block())
} catch (failure: IllegalArgumentException) {
	CallToolResult(content = listOf(TextContent(failure.message ?: "Invalid mutation request")), isError = true)
} catch (failure: IllegalStateException) {
	CallToolResult(content = listOf(TextContent(failure.message ?: "Workspace is not ready")), isError = true)
}

private fun jsonResult(json: JsonObject): CallToolResult = CallToolResult(
	content = listOf(TextContent(json.toString())),
	structuredContent = json,
)

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.requiredString(name: String): String =
	arguments?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("Missing required argument: $name")

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalString(name: String): String? =
	arguments?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.boolean(name: String, default: Boolean): Boolean =
	arguments?.get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
		?: if (arguments?.containsKey(name) == true) throw IllegalArgumentException("$name must be a boolean") else default

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalBoolean(name: String): Boolean? =
	arguments?.get(name)?.let { value ->
		value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull()
			?: throw IllegalArgumentException("$name must be a boolean")
	}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalInteger(name: String): Int? =
	arguments?.get(name)?.let { value ->
		value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
	}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalFloat(name: String): Float? =
	arguments?.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.stringList(
	name: String,
	required: Boolean = true,
): List<String> {
	val value = arguments?.get(name)
	if (value == null) {
		if (required) throw IllegalArgumentException("Missing required argument: $name")
		return emptyList()
	}
	return value.jsonArray.map { item ->
		item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
			?: throw IllegalArgumentException("$name must contain non-empty strings")
	}
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalStringList(name: String): List<String>? =
	if (arguments?.containsKey(name) == true) stringList(name) else null

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.outputSpec(): AgentViewOutputSpec =
	AgentViewOutputSpec(
		targetLongEdge = integerOrDefault("target_long_edge", integerOrDefault("max_edge", 1024)),
		maxBytes = integerOrDefault("max_bytes", 4 * 1024 * 1024),
	)

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.integerOrDefault(name: String, default: Int): Int =
	arguments?.get(name)?.let { value ->
		value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
	} ?: default

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.float(name: String, default: Float): Float =
	arguments?.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	} ?: default

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.background(): AgentViewBackground =
	arguments?.get("background")?.jsonPrimitive?.contentOrNull
		?.uppercase()?.let(AgentViewBackground::valueOf) ?: AgentViewBackground.TRANSPARENT

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.floatMap(name: String): Map<String, Float> =
	arguments?.get(name)?.jsonObject?.mapValues { (id, value) ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Parameter $id must be numeric")
	}.orEmpty()

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalStringSet(name: String): Set<String>? =
	arguments?.get(name)?.jsonArray?.map { value ->
		value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
			?: throw IllegalArgumentException("$name must contain non-empty strings")
	}?.toSet()

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.viewFrame(): AgentViewFrame {
	val viewport = arguments?.get("viewport")?.jsonObject
		?: throw IllegalArgumentException("Missing required argument: viewport")
	fun requiredFloat(name: String): Float = viewport[name]?.jsonPrimitive?.floatOrNull
		?: throw IllegalArgumentException("viewport.$name must be numeric")
	fun optionalFloat(name: String, default: Float): Float = viewport[name]?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("viewport.$name must be numeric")
	} ?: default
	return when (viewport["mode"]?.jsonPrimitive?.contentOrNull) {
		"canvas_rect" -> {
			val left = requiredFloat("left")
			val top = requiredFloat("top")
			val width = requiredFloat("width")
			val height = requiredFloat("height")
			AgentViewFrame.CanvasRect(Bounds(left, top, left + width, top + height))
		}

		"focus_layers" -> {
			val layerIds = viewport["layer_ids"]?.jsonArray?.map { value ->
				value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
					?: throw IllegalArgumentException("viewport.layer_ids must contain non-empty strings")
			}?.toSet().orEmpty()
			AgentViewFrame.FocusLayers(
				layerIds = layerIds,
				objectScale = optionalFloat("object_scale", 0.65f),
				aspectRatio = optionalFloat("aspect_ratio", 1f),
			)
		}

		null -> throw IllegalArgumentException("Missing required argument: viewport.mode")
		else -> throw IllegalArgumentException("viewport.mode must be canvas_rect or focus_layers")
	}
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.layerInsertion(): AgentLayerInsertion {
	val insertion = arguments?.get("insertion")?.jsonObject ?: return AgentLayerInsertion.Top
	val mode = insertion["mode"]?.jsonPrimitive?.contentOrNull
		?: throw IllegalArgumentException("insertion.mode is required")
	fun reference(): String = insertion["reference_layer_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("insertion.reference_layer_id is required for $mode")
	return when (mode) {
		"top" -> AgentLayerInsertion.Top
		"bottom" -> AgentLayerInsertion.Bottom
		"above" -> AgentLayerInsertion.Above(reference())
		"below" -> AgentLayerInsertion.Below(reference())
		else -> throw IllegalArgumentException("insertion.mode must be top, bottom, above, or below")
	}
}

private fun AgentProjectSnapshot.toJson(
	includeLayers: Boolean,
	layers: List<AgentLayerSnapshot> = this.layers,
): JsonObject = buildJsonObject {
	projectId?.let { put("projectId", it) }
	put("revisionId", revisionId)
	historyHeadNodeId?.let { put("historyHeadNodeId", it) }
	put("loaded", loaded)
	inputName?.let { put("inputName", it) }
	canvasWidth?.let { put("canvasWidth", it) }
	canvasHeight?.let { put("canvasHeight", it) }
	put("busy", busy)
	put("status", status)
	put("persistenceStatus", persistenceStatus)
	persistenceError?.let { put("persistenceError", it) }
	selectedLayerId?.let { put("selectedLayerId", it) }
	put("layerCount", layers.size)
	put("totalLayerCount", this@toJson.layers.size)
	put("parameterCount", parameters.size)
	if (includeLayers) put("layers", JsonArray(layers.map(AgentLayerSnapshot::toJson)))
}

private fun AgentParameterSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("name", name)
	put("min", min)
	put("max", max)
	put("default", default)
	put("current", current)
	put("kind", kind)
}

private fun AgentLayerSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("sourceName", sourceName)
	put("rasterWidth", rasterWidth)
	put("rasterHeight", rasterHeight)
	put("canvasUnitsPerSourcePixelX", bounds.width / rasterWidth.coerceAtLeast(1).toFloat())
	put("canvasUnitsPerSourcePixelY", bounds.height / rasterHeight.coerceAtLeast(1).toFloat())
	put("groupPath", groupPath)
	put("order", order)
	put("semanticTag", semanticTag)
	put("side", side)
	put("confidence", confidence)
	put("visible", visible)
	put("deleted", deleted)
	put("derived", derived)
	sourceAssetId?.let { put("sourceAssetId", it) }
	sourceSpatialReferenceId?.let { put("sourceSpatialReferenceId", it) }
	putJsonObject("bounds") {
		put("left", bounds.left)
		put("top", bounds.top)
		put("right", bounds.right)
		put("bottom", bounds.bottom)
	}
	putJsonArray("sourcePixelToCanvas") {
		add(JsonPrimitive(bounds.width / rasterWidth.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.left))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.height / rasterHeight.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(bounds.top))
	}
	putJsonObject("opaqueBounds") {
		put("left", opaqueBounds.left)
		put("top", opaqueBounds.top)
		put("right", opaqueBounds.right)
		put("bottom", opaqueBounds.bottom)
	}
}

private fun AgentHistorySnapshot.toJson(): JsonObject = buildJsonObject {
	put("headNodeId", headNodeId)
	putJsonArray("nodes") {
		nodes.forEach { node ->
			add(buildJsonObject {
				put("id", node.id)
				node.parentId?.let { put("parentId", it) }
				put("revisionId", node.revisionId)
				put("summary", node.summary)
				put("actor", node.actor)
				node.taskId?.let { put("taskId", it) }
				put("createdAt", node.createdAt)
				put("isHead", node.isHead)
			})
		}
	}
}

private fun AgentImportedPngAsset.toJson(): JsonObject = buildJsonObject {
	put("assetId", id)
	put("sha256", sha256)
	put("mimeType", "image/png")
	put("pixelWidth", pixelWidth)
	put("pixelHeight", pixelHeight)
	put("sourceSpatialReferenceId", placement.sourceViewId)
	putJsonObject("canvasRect") {
		put("left", placement.canvasRect.left)
		put("top", placement.canvasRect.top)
		put("right", placement.canvasRect.right)
		put("bottom", placement.canvasRect.bottom)
		put("width", placement.canvasRect.width)
		put("height", placement.canvasRect.height)
	}
	put("canvasUnitsPerPixelX", placement.canvasUnitsPerPixelX)
	put("canvasUnitsPerPixelY", placement.canvasUnitsPerPixelY)
}

private fun AgentWorkspaceMutationResult.toJson(): JsonObject = buildJsonObject {
	put("historyNodeId", historyNodeId)
	put("revisionId", revisionId)
	put("summary", summary)
	putJsonArray("affectedLayerIds") { affectedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedParameterIds") { affectedParameterIds.forEach { add(JsonPrimitive(it)) } }
}

private fun AgentTaskSnapshot.toJson(includeEvents: Boolean = true): JsonObject = buildJsonObject {
	put("taskId", id)
	put("objective", objective)
	putJsonArray("plan") { plan.forEach { add(JsonPrimitive(it)) } }
	put("status", status.name.lowercase())
	currentStep?.let { put("currentStep", it) }
	put("progress", progress)
	put("inputRevisionId", inputRevisionId)
	put("inputHistoryHeadNodeId", inputHistoryHeadNodeId)
	put("createdAt", createdAt)
	put("updatedAt", updatedAt)
	putJsonArray("artifactIds") { artifactIds.forEach { add(JsonPrimitive(it)) } }
	if (includeEvents) putJsonArray("events") {
		events.forEach { event ->
			add(buildJsonObject {
				put("sequence", event.sequence)
				put("createdAt", event.createdAt)
				put("status", event.status.name.lowercase())
				put("message", event.message)
				putJsonArray("artifactIds") { event.artifactIds.forEach { add(JsonPrimitive(it)) } }
			})
		}
	}
}

private fun AgentRenderedView.toJson(): JsonObject = buildJsonObject {
	put("viewId", viewId)
	put("revisionId", revisionId)
	put("kind", kind)
	put("mimeType", "image/png")
	put("pngBytes", png.size)
	put("sha256", sha256)
	put("originalWidth", originalWidth)
	put("originalHeight", originalHeight)
	put("renderedWidth", renderedWidth)
	put("renderedHeight", renderedHeight)
	put("scale", scale)
	putJsonObject("appliedParameters") {
		appliedParameters.forEach { (id, value) -> put(id, value) }
	}
	putJsonArray("outOfRangeParameters") {
		outOfRangeParameters.forEach { diagnostic ->
			add(buildJsonObject {
				put("id", diagnostic.id)
				put("value", diagnostic.value)
				put("min", diagnostic.min)
				put("max", diagnostic.max)
			})
		}
	}
	putJsonArray("includedLayerIds") { includedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedLayerIds") { annotatedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("objectIds") { objectIds.forEach { add(JsonPrimitive(it)) } }
	putJsonObject("canvasRect") {
		put("left", canvasRect.left)
		put("top", canvasRect.top)
		put("right", canvasRect.right)
		put("bottom", canvasRect.bottom)
	}
	putJsonObject("spatial") {
		put("spatialReferenceId", viewId)
		put("coordinateSpace", spatial.coordinateSpace)
		put("pixelOrigin", "top_left_edge")
		put("pixelWidth", spatial.pixelWidth)
		put("pixelHeight", spatial.pixelHeight)
		put("pixelAspectRatio", 1)
		put("colorSpace", "sRGB")
		put("alphaMode", "straight")
		put("canvasWidth", spatial.canvasWidth)
		put("canvasHeight", spatial.canvasHeight)
		put("canvasUnitsPerPixelX", spatial.canvasUnitsPerPixelX)
		put("canvasUnitsPerPixelY", spatial.canvasUnitsPerPixelY)
		putJsonObject("requestedViewRect") {
			put("left", spatial.requestedViewRect.left)
			put("top", spatial.requestedViewRect.top)
			put("right", spatial.requestedViewRect.right)
			put("bottom", spatial.requestedViewRect.bottom)
			put("width", spatial.requestedViewRect.width)
			put("height", spatial.requestedViewRect.height)
		}
		putJsonObject("viewRect") {
			put("left", spatial.viewRect.left)
			put("top", spatial.viewRect.top)
			put("right", spatial.viewRect.right)
			put("bottom", spatial.viewRect.bottom)
			put("width", spatial.viewRect.width)
			put("height", spatial.viewRect.height)
		}
		spatial.focusRect?.let { focus ->
			putJsonObject("focusRect") {
				put("left", focus.left)
				put("top", focus.top)
				put("right", focus.right)
				put("bottom", focus.bottom)
				put("width", focus.width)
				put("height", focus.height)
			}
		}
		putJsonArray("focusLayerIds") { spatial.focusLayerIds.forEach { add(JsonPrimitive(it)) } }
		spatial.objectScale?.let { put("objectScale", it) }
		putJsonArray("pixelToCanvas") {
			add(JsonPrimitive(spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.viewRect.left))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(spatial.viewRect.top))
		}
		putJsonArray("canvasToPixel") {
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(-spatial.viewRect.left / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(-spatial.viewRect.top / spatial.canvasUnitsPerPixelY))
		}
		put("placementRule", "map_full_png_to_view_rect_preserve_aspect")
	}
}

private fun loadHairSeparationSkill(): String =
	AgentMcpService::class.java.getResourceAsStream("/agent/skills/hair-separation.md")
		?.bufferedReader()
		?.use { it.readText() }
		?: error("Bundled hair separation skill is missing")

private val READ_ONLY = ToolAnnotations(
	readOnlyHint = true,
	destructiveHint = false,
	idempotentHint = true,
	openWorldHint = false,
)

private val MUTATING = ToolAnnotations(
	readOnlyHint = false,
	destructiveHint = false,
	idempotentHint = false,
	openWorldHint = false,
)

private val AGENT_INSTRUCTIONS = """
	psd2live treats an authenticated Agent as the owner of the open workspace: every exposed workspace capability may be used without per-operation approval. The append-only persisted history store is the sole immutable boundary: inspect historyHeadNodeId, pass it to mutations, and use history_checkout rather than attempting to rewrite history. Read project_get_state before planning and wait while persistenceStatus is restoring. For multi-step work, call task_start with your own plan and keep task_update checkpoints current; task state is not an approval gate and its plan may be replaced as evidence changes. Use stable object IDs and direct View tools; never infer coordinates from application screenshots. View tools return one composited PNG plus a reversible pixel-to-canvas spatial reference, never a PSD. view_render_model requires an explicit canvas rectangle or object-relative focus frame in addition to pose, layer composition, and annotations. Preserve spatialReferenceId when generating a replacement PNG, stage it with asset_import_png, then add it with layer_add_from_asset. Generated pixel resolution is independent from canvas size; the importer maps the entire PNG or declared source_pixel_rect back to the referenced canvas rectangle and refuses silent aspect stretching. Mutations rebuild the actual source, mesh, rig, and export preview before committing history. Soft deletion remains recoverable. For hair separation, load the hair-separation prompt and inspect isolated, context, and posed model views before editing.
""".trimIndent()
