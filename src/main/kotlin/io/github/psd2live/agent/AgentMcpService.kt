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
	val configGeminiJson: String
		get() {
			val endpointJson = JsonPrimitive(endpoint)
			val authorizationJson = JsonPrimitive("Bearer $token")
			return """
				{
				  "mcpServers": {
				    "psd2live": {
				      "serverUrl": $endpointJson,
				      "headers": {
				        "Authorization": $authorizationJson
				      }
				    }
				  }
				}
			""".trimIndent()
		}

	// Kept for source compatibility with integrations created before the
	// connection dialog exposed ChatGPT/Codex and Gemini as peer hosts.
	val configAntigravityJson: String
		get() = configGeminiJson

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
		serverInfo = Implementation("psd2live", "0.4.0"),
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
		name = "object_get",
		description = "Read an authoritative rig object (mesh, warp deformer, rotation deformer, part, glue) including its current keyforms, channels, deformer hierarchy, and geometry bounds.",
		inputSchema = objectGetSchema(),
		toolAnnotations = READ_ONLY,
	) { request ->
		mutationResult {
			workspace.getObject(request.targetRef()).toJson()
		}
	}

	server.addTool(
		name = "keyform_set",
		description = "Set or update keyform geometry and/or channels (opacity, draw order, multiply/screen color, glue intensity) on a target at an exact N-D parameter coordinate.",
		inputSchema = keyformSetSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.setKeyform(
				AgentKeyformSetRequest(
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					target = request.targetRef(),
					coordinate = request.coordinateMap(),
					geometry = request.optionalGeometry(),
					channels = request.optionalChannels(),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "keyform_delete",
		description = "Delete a keyform key or an entire parameter axis from a target's geometry grid or specific channel track.",
		inputSchema = keyformDeleteSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.deleteKeyform(
				AgentKeyformDeleteRequest(
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					target = request.targetRef(),
					parameterId = request.requiredString("parameter_id"),
					keyValue = request.optionalFloat("key_value"),
					channel = request.optionalString("channel"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "keyform_copy",
		description = "Copy keyform geometry and/or channels from a source parameter coordinate to a destination parameter coordinate (on the same or another target).",
		inputSchema = keyformCopySchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			workspace.copyKeyform(
				AgentKeyformCopyRequest(
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					sourceTarget = request.targetRef("source_target"),
					sourceCoordinate = request.coordinateMap("source_coordinate"),
					destinationTarget = if (request.hasTarget("destination_target")) request.targetRef("destination_target") else null,
					destinationCoordinate = request.coordinateMap("destination_coordinate"),
					channels = request.optionalStringList("channels"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	server.addTool(
		name = "rig_k_pose",
		description = "Capture an explicit or current parameter pose deformation onto a target as keyform keys across all specified parameters.",
		inputSchema = rigKPoseSchema(),
		toolAnnotations = MUTATING,
	) { request ->
		mutationResult {
			val params = if (request.arguments?.containsKey("parameters") == true) {
				request.floatMap("parameters")
			} else {
				request.coordinateMap("coordinate")
			}
			workspace.rigKPose(
				AgentRigKPoseRequest(
					expectedHistoryHeadNodeId = request.requiredString("expected_history_head_node_id"),
					target = request.targetRef(),
					parameters = params,
					geometry = request.optionalGeometry(),
					channels = request.optionalChannels(),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

    server.addTool(
        name = "project_save",
        toolAnnotations = MUTATING,
        description = "Save the complete portable project to its selected file, creating an immediate history checkpoint. Choose the file location in the UI first.",
    ) { mutationResult { workspace.saveProject().toJson() } }
    server.addTool(
        name = "history_checkpoint",
        toolAnnotations = MUTATING,
        description = "Append an explicit history checkpoint even when the model is unchanged.",
        inputSchema = ToolSchema(properties = buildJsonObject { putJsonObject("summary") { put("type", "string") } }, required = listOf("summary")),
    ) { request -> mutationResult { workspace.checkpoint(request.requiredString("summary")).toJson() } }

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
		description = "Render one layer directly from RGBA model data as transparent or checkerboard PNG. This is not a UI screenshot. For differences, separation, or missing-pixel completion, pass this View to Nano Banana Pro/NBP or GPT Image 2; never redraw it with Python/PIL/OpenCV.",
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
		description = "Composite visible layers into one focused PNG around a layer. object_scale below 1 includes surrounding context; this never returns a PSD or UI screenshot. Use it as a reference for Nano Banana Pro/NBP or GPT Image 2 when painted pixels must be changed or reconstructed.",
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

    registerAssetWorkflowTools(server, workspace)

    server.addTool(
        name = "agent_get_workflow",
        description = "Read natural hair-separation guidance: infer local depth before editing, allow crossing complete locks, assemble early, judge natural appearance and intended motion rather than exact edges, use white/black matte fallback, and wire independent Warp/physics.",
        inputSchema = ToolSchema(properties = buildJsonObject {}), toolAnnotations = READ_ONLY,
    ) { CallToolResult(content = listOf(TextContent(loadHairSeparationSkill()))) }

    server.addTool(
        name = "rig_list_objects",
        description = "Discover actual mesh, Warp and rotation IDs before object_get or warp_create. Layer IDs are not necessarily mesh IDs.",
        inputSchema = ToolSchema(properties = buildJsonObject {}), toolAnnotations = READ_ONLY,
    ) { mutationResult { buildJsonObject { putJsonArray("objects") {
        workspace.listRigObjects().forEach { ref -> add(buildJsonObject { put("kind", ref.kind); put("id", ref.id) }) }
    } } } }

    server.addTool(
        name = "warp_create",
        description = "Create an independent identity Warp for one or more existing meshes under their common Warp parent. Preserves mesh pixels, keyforms, masks and inherited motion. The new lattice uses parent-normalized 0..1 coordinates across the parent frame; requested rows/columns are minima, rounded up together to align parent knots and preserve inherited motion. Inspect actual dimensions with object_get. Use rig_list_objects and object_get first; animate with keyform_set. No special hair-split API is required.",
        inputSchema = rigObjectCreateSchema(false), toolAnnotations = MUTATING,
    ) { request -> mutationResult {
        workspace.createWarp(io.github.psd2live.core.RigWarpEdit.fromJson(request.arguments ?: error("Missing arguments")),
            request.requiredString("expected_history_head_node_id"), request.optionalString("task_id")).toJson()
    } }

    server.addTool(
        name = "physics_list",
        description = "List explicitly authored independent physics groups. Built-in front/back hair presets are generated separately. Physics drives parameters, not Warp IDs; bind each output to its Warp with keyform_set.",
        inputSchema = ToolSchema(properties = buildJsonObject {}), toolAnnotations = READ_ONLY,
    ) { mutationResult { buildJsonObject { putJsonArray("groups") { workspace.listPhysics().forEach { add(it.toJson()) } } } } }

    server.addTool(
        name = "physics_put",
        description = "Create or replace an independent two-particle Angle-input physics group by ID. Input/output parameters must already exist; each group needs a distinct output parameter and corresponding Warp keyforms. A matching built-in preset ID or output is replaced by this custom group. Adjustable length, mobility, delay, acceleration and output_scale. Enables physics in the same history commit and exports to physics3.json and editable CMO3 outside mesh-only mode.",
        inputSchema = rigObjectCreateSchema(true), toolAnnotations = MUTATING,
    ) { request -> mutationResult {
        workspace.putPhysics(io.github.psd2live.core.RigPhysicsEdit.fromJson(request.arguments ?: error("Missing arguments")),
            request.requiredString("expected_history_head_node_id"), request.optionalString("task_id")).toJson()
    } }

    server.addTool(
        name = "asset_inspect",
        description = "Inspect actual staged PNG pixels, spatial placement and transparency counts. Use for quick usability/alpha diagnosis, then trial assembly. Overlapping hair, minor tone differences and hidden-root/edge variation are not automatic rejection reasons; judge depth, seams and intended motion in composition.",
        inputSchema = ToolSchema(properties = buildJsonObject { putJsonObject("asset_id") { put("type", "string") } }, required = listOf("asset_id")),
        toolAnnotations = READ_ONLY,
    ) { request ->
        try {
            val preview = workspace.inspectAsset(request.requiredString("asset_id"))
            val metadata = buildJsonObject {
                put("asset", preview.asset.toJson()); put("transparentPixels", preview.transparentPixels); put("translucentPixels", preview.translucentPixels)
                put("image_order", "processed, original (when retained)")
            }
            CallToolResult(content = listOf(TextContent(metadata.toString()), ImageContent(Base64.getEncoder().encodeToString(preview.png), "image/png")) + listOfNotNull(preview.originalPng?.let { ImageContent(Base64.getEncoder().encodeToString(it), "image/png") }), structuredContent = metadata)
        } catch (failure: IllegalArgumentException) {
            CallToolResult(content = listOf(TextContent(failure.message ?: "Invalid asset request")), isError = true)
        } catch (failure: IllegalStateException) {
            CallToolResult(content = listOf(TextContent(failure.message ?: "Workspace is not ready")), isError = true)
        }
    }

	server.addTool(
		name = "asset_import_png",
		description = "Stage a generated solid-matte PNG using reference_id; MCP keeps raw pixels and performs background cleanup. Register placement before adding. Legacy spatial_reference_id import semantics remain available. Differences, separated painted parts, occlusion completion, and reconstructed pixels must come from an actual Nano Banana Pro/NBP, GPT Image 2, or equivalent host-native image-tool call—not Python/PIL/OpenCV/Matplotlib/SVG/Canvas drawing. Exact unchanged-pixel extraction and non-creative post-generation alpha cleanup are the only procedural exceptions. Pixel resolution may differ, but aspect and canvas placement are preserved. This does not change project history.",
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
					spatialReferenceId = request.optionalString("spatial_reference_id").orEmpty(),
                    referenceId = request.optionalString("reference_id"),
                    processing = request.arguments?.get("processing") as? JsonObject ?: JsonObject(emptyMap()),
					sourcePixelRect = sourceRect,
                    solidBackground = request.optionalString("solid_background"),
                    backgroundTolerance = request.integerOrDefault("background_tolerance", 16),
                    requireTransparency = request.boolean("require_transparency", false),
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
                    registrationId = request.optionalString("registration_id"),
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
		description = "Natural hair separation: infer local depth, complete crossing root-to-tip locks with a host image editor, assemble candidates early, and judge coherent appearance and intended motion without requiring exact source edges.",
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

private fun rigObjectCreateSchema(physics: Boolean): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        listOf("id", "name", "expected_history_head_node_id", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        if (physics) {
            listOf("input_parameter", "output_parameter").forEach { key -> putJsonObject(key) { put("type", "string") } }
            listOf("length", "mobility", "delay", "acceleration", "output_scale").forEach { key -> putJsonObject(key) { put("type", "number") } }
        } else {
            putJsonObject("parent_id") { put("type", "string"); put("description", "Existing common Warp parent from object_get") }
            putJsonObject("mesh_ids") { put("type", "array"); put("minItems", 1); putJsonObject("items") { put("type", "string") } }
            listOf("rows", "columns").forEach { key -> putJsonObject(key) { put("type", "integer"); put("minimum", 1); put("maximum", 32) } }
        }
    },
    required = listOf("id", "name", "expected_history_head_node_id") + if (physics) listOf("input_parameter", "output_parameter") else listOf("parent_id", "mesh_ids"),
)

private fun pngImportSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
        putJsonObject("reference_id") { put("type", "string"); put("description", "Reference package from asset_prepare_reference. V2 import keeps raw PNG, removes declared matte, and requires asset_register before adding a layer. Replaces spatial_reference_id.") }
        put("processing", processingSchema())
        putJsonObject("solid_background") { put("type", "string"); put("pattern", "^#[0-9a-fA-F]{6}$"); put("description", "Actual generated matte color. Default generation to pure white #FFFFFF for dark hair or pure black #000000 for light hair to avoid colored fringe; do not guess or automatically strip alpha when omitted. Removes only border-connected near-color pixels, not a baked checkerboard. Inspect remaining matte in composition.") }
        putJsonObject("background_tolerance") { put("type", "integer"); put("minimum", 0); put("maximum", 64); put("default", 16) }
        putJsonObject("require_transparency") { put("type", "boolean"); put("description", "Set true for separated hair: reject fully opaque or empty results after cleanup.") }

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
	required = listOf("png_base64"),
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
        putJsonObject("registration_id") { put("type", "string"); put("description", "Required for reference-package assets; immutable absolute placement from asset_register") }
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

private fun targetSchemaProperty(description: String = "Authoritative rig target reference"): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", description)
	putJsonObject("properties") {
		putJsonObject("kind") {
			put("type", "string")
			putJsonArray("enum") {
				listOf("mesh", "warp", "rotation", "part", "glue").forEach { add(JsonPrimitive(it)) }
			}
			put("description", "Object type: mesh, warp, rotation, part, or glue")
		}
		putJsonObject("id") {
			put("type", "string")
			put("description", "Stable object ID (e.g. layer ID for mesh, deformer ID, part ID)")
		}
		putJsonObject("secondary_id") {
			put("type", "string")
			put("description", "Optional secondary ID for composite targets such as glue bindings")
		}
	}
	putJsonArray("required") {
		add(JsonPrimitive("kind"))
		add(JsonPrimitive("id"))
	}
}

private fun geometrySchemaProperty(): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", "Keyform geometry deformation values")
	putJsonObject("properties") {
		putJsonObject("control_points") {
			put("type", "array")
			put("description", "Flat list of [x0, y0, x1, y1, ...] warp lattice control points")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("origin_x") { put("type", "number"); put("description", "Rotation deformer pivot X") }
		putJsonObject("origin_y") { put("type", "number"); put("description", "Rotation deformer pivot Y") }
		putJsonObject("angle") { put("type", "number"); put("description", "Rotation angle in degrees") }
		putJsonObject("scale") { put("type", "number"); put("description", "Rotation scale factor") }
		putJsonObject("position_deltas") {
			put("type", "array")
			put("description", "Flat list of [dx0, dy0, dx1, dy1, ...] vertex deltas for ArtMesh")
			putJsonObject("items") { put("type", "number") }
		}
	}
}

private fun channelsSchemaProperty(): JsonObject = buildJsonObject {
	put("type", "object")
	put("description", "Keyform visual/state channel values")
	putJsonObject("properties") {
		putJsonObject("opacity") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("draw_order") { put("type", "number") }
		putJsonObject("multiply_color") {
			put("type", "array")
			put("description", "Normalized RGBA [r, g, b, a] multiply color")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("screen_color") {
			put("type", "array")
			put("description", "Normalized RGB [r, g, b] screen color")
			putJsonObject("items") { put("type", "number") }
		}
		putJsonObject("glue_intensity") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
		putJsonObject("flip_x") { put("type", "boolean") }
		putJsonObject("flip_y") { put("type", "boolean") }
	}
}

private fun objectGetSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		put("target", targetSchemaProperty())
		putJsonObject("kind") {
			put("type", "string")
			putJsonArray("enum") {
				listOf("mesh", "warp", "rotation", "part", "glue").forEach { add(JsonPrimitive(it)) }
			}
		}
		putJsonObject("id") { put("type", "string") }
		putJsonObject("secondary_id") { put("type", "string") }
	},
)

private fun keyformSetSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("expected_history_head_node_id") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("coordinate") {
			put("type", "object")
			put("description", "Keyform parameter coordinate, e.g. {\"ParamAngleX\": 0.0, \"ParamAngleY\": 1.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("geometry", geometrySchemaProperty())
		put("channels", channelsSchemaProperty())
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("expected_history_head_node_id", "coordinate"),
)

private fun keyformDeleteSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("expected_history_head_node_id") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("parameter_id") { put("type", "string") }
		putJsonObject("key_value") {
			put("type", "number")
			put("description", "Specific key value to remove. If omitted, the entire parameter axis is deleted.")
		}
		putJsonObject("channel") {
			put("type", "string")
			put("description", "Optional channel name (e.g. opacity, draw_order). If omitted, operates on geometry.")
		}
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("expected_history_head_node_id", "parameter_id"),
)

private fun keyformCopySchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("expected_history_head_node_id") { put("type", "string") }
		put("source_target", targetSchemaProperty("Source target reference"))
		putJsonObject("source_coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("destination_target", targetSchemaProperty("Destination target (defaults to source if omitted)"))
		putJsonObject("destination_coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("channels") {
			put("type", "array")
			put("description", "Optional list of channels to copy (e.g. [\"geometry\", \"opacity\"]). Null copies all.")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("expected_history_head_node_id", "source_coordinate", "destination_coordinate"),
)

private fun rigKPoseSchema(): ToolSchema = ToolSchema(
	properties = buildJsonObject {
		putJsonObject("expected_history_head_node_id") { put("type", "string") }
		put("target", targetSchemaProperty())
		putJsonObject("parameters") {
			put("type", "object")
			put("description", "Pose parameters to set/capture, e.g. {\"ParamAngleX\": 30.0}")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("coordinate") {
			put("type", "object")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		put("geometry", geometrySchemaProperty())
		put("channels", channelsSchemaProperty())
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("expected_history_head_node_id"),
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

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.hasTarget(prefix: String = "target"): Boolean {
	if (arguments?.containsKey(prefix) == true) return true
	if (arguments?.containsKey("${prefix}_kind") == true && arguments?.containsKey("${prefix}_id") == true) return true
	if (prefix == "target" && arguments?.containsKey("kind") == true && arguments?.containsKey("id") == true) return true
	return false
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.targetRef(
	prefix: String = "target",
): AgentKeyformTargetRef {
	val obj = (arguments?.get(prefix) ?: if (prefix == "source_target") arguments?.get("target") else null)?.jsonObject
	if (obj != null) {
		val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
			?: throw IllegalArgumentException("$prefix.kind is required")
		val id = obj["id"]?.jsonPrimitive?.contentOrNull
			?: throw IllegalArgumentException("$prefix.id is required")
		val secondaryId = obj["secondary_id"]?.jsonPrimitive?.contentOrNull
			?: obj["secondaryId"]?.jsonPrimitive?.contentOrNull
		return AgentKeyformTargetRef(kind = kind, id = id, secondaryId = secondaryId)
	}
	val kind = arguments?.get("${prefix}_kind")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments?.get("target_kind")?.jsonPrimitive?.contentOrNull ?: arguments?.get("kind")?.jsonPrimitive?.contentOrNull else null)
		?: throw IllegalArgumentException("Missing target kind")
	val id = arguments?.get("${prefix}_id")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments?.get("target_id")?.jsonPrimitive?.contentOrNull ?: arguments?.get("id")?.jsonPrimitive?.contentOrNull else null)
		?: throw IllegalArgumentException("Missing target id")
	val secondaryId = arguments?.get("${prefix}_secondary_id")?.jsonPrimitive?.contentOrNull
		?: (if (prefix == "target" || prefix == "source_target") arguments?.get("secondary_id")?.jsonPrimitive?.contentOrNull else null)
	return AgentKeyformTargetRef(kind = kind, id = id, secondaryId = secondaryId)
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.coordinateMap(
	name: String = "coordinate",
): Map<String, Float> {
	val obj = arguments?.get(name)?.jsonObject
		?: throw IllegalArgumentException("Missing required argument: $name")
	return obj.mapValues { (k, v) ->
		v.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Coordinate $k must be numeric")
	}
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalGeometry(
	name: String = "geometry",
): AgentKeyformGeometry? {
	val obj = arguments?.get(name)?.jsonObject ?: return null
	fun fl(key: String, alt: String? = null): Float? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.floatOrNull
	fun flList(key: String, alt: String? = null): List<Float>? {
		val arr = (obj[key] ?: alt?.let { obj[it] })?.jsonArray ?: return null
		return arr.map { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$key must contain numbers") }
	}
	return AgentKeyformGeometry(
		controlPoints = flList("control_points", "controlPoints"),
		originX = fl("origin_x", "originX"),
		originY = fl("origin_y", "originY"),
		angle = fl("angle"),
		scale = fl("scale"),
		positionDeltas = flList("position_deltas", "positionDeltas"),
	)
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalChannels(
	name: String = "channels",
): AgentKeyformChannels? {
	val obj = arguments?.get(name)?.jsonObject ?: return null
	fun fl(key: String, alt: String? = null): Float? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.floatOrNull
	fun bl(key: String, alt: String? = null): Boolean? =
		(obj[key] ?: alt?.let { obj[it] })?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
	fun flList(key: String, alt: String? = null): List<Float>? {
		val arr = (obj[key] ?: alt?.let { obj[it] })?.jsonArray ?: return null
		return arr.map { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$key must contain numbers") }
	}
	return AgentKeyformChannels(
		opacity = fl("opacity"),
		drawOrder = fl("draw_order", "drawOrder"),
		multiplyColor = flList("multiply_color", "multiplyColor"),
		screenColor = flList("screen_color", "screenColor"),
		glueIntensity = fl("glue_intensity", "glueIntensity"),
		flipX = bl("flip_x", "flipX"),
		flipY = bl("flip_y", "flipY"),
	)
}

private fun AgentProjectSnapshot.toJson(
	includeLayers: Boolean,
	layers: List<AgentLayerSnapshot> = this.layers,
): JsonObject = buildJsonObject {
	projectId?.let { put("projectId", it) }
	put("revisionId", revisionId)
	historyHeadNodeId?.let { put("historyHeadNodeId", it) }
    projectFile?.let { put("projectFile", it) }
    put("projectDirty", projectDirty)
    put("projectSaving", projectSaving)
    projectSaveError?.let { put("projectSaveError", it) }
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
    put("details", details)
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

internal fun AgentWorkspaceMutationResult.toJson(): JsonObject = buildJsonObject {
	put("historyNodeId", historyNodeId)
	put("revisionId", revisionId)
	put("summary", summary)
	putJsonArray("affectedLayerIds") { affectedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedParameterIds") { affectedParameterIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedObjectIds") { affectedObjectIds.forEach { add(JsonPrimitive(it)) } }
}

private fun AgentObjectSnapshot.toJson(): JsonObject = buildJsonObject {
	putJsonObject("target") {
		put("kind", target.kind)
		put("id", target.id)
		target.secondaryId?.let { put("secondaryId", it) }
	}
	put("name", name)
	parentId?.let { put("parentId", it) }
	partId?.let { put("partId", it) }
	put("visible", visible)
	if (topologyInfo.isNotEmpty()) {
		putJsonObject("topologyInfo") {
			topologyInfo.forEach { (k, v) -> put(k, v) }
		}
	}
	geometry?.let { geom ->
		putJsonObject("geometry") {
			put("keyformCount", geom.keyformCount)
			putJsonArray("axes") {
				geom.axes.forEach { axis ->
					add(buildJsonObject {
						put("parameterId", axis.parameterId)
						putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
					})
				}
			}
			putJsonArray("cells") {
				geom.cells.forEach { cell ->
					add(buildJsonObject {
						putJsonObject("coordinate") {
							cell.coordinate.forEach { (p, v) -> put(p, v) }
						}
						cell.originX?.let { put("originX", it) }
						cell.originY?.let { put("originY", it) }
						cell.angle?.let { put("angle", it) }
						cell.scale?.let { put("scale", it) }
						cell.controlPoints?.let { cps ->
							putJsonArray("controlPoints") { cps.forEach { cp -> add(JsonPrimitive(cp)) } }
						}
						cell.positionDeltas?.let { pds ->
							putJsonArray("positionDeltas") { pds.forEach { pd -> add(JsonPrimitive(pd)) } }
						}
					})
				}
			}
		}
	}
	if (channels.isNotEmpty()) {
		putJsonArray("channels") {
			channels.forEach { track ->
				add(buildJsonObject {
					put("channel", track.channel)
					put("staticValue", track.staticValue)
					put("keyformCount", track.keyformCount)
					putJsonArray("axes") {
						track.axes.forEach { axis ->
							add(buildJsonObject {
								put("parameterId", axis.parameterId)
								putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
							})
						}
					}
				})
			}
		}
	}
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
	PSD2Live exposes one authoritative local workspace. Before every mutation, call project_get_state, wait while persistenceStatus is restoring, and pass the current historyHeadNodeId as expected_history_head_node_id. Never blindly retry a mutation after a timeout, disconnect, or lost response: reconnect and inspect project_get_state, history_list, the task log, and affected objects to determine whether it committed. Read-only discovery and rendering may use a small bounded retry. A stale-head error requires refreshing and reconciling concurrent changes before a new request.

	An authenticated Agent may use the exposed workspace capabilities without per-operation approval. History is append-only; use history_checkout to branch instead of rewriting prior nodes. For multi-step work, call task_start and keep task_update checkpoints current with View, asset, object, and history node IDs. Task state coordinates recovery and is not an approval gate.

    Call agent_get_workflow and rig_list_objects for capability discovery. Do not refuse hair separation because there is no single natural-strand split tool: compose Views, host image editing, asset_import_png, layer_add_from_asset, warp_create, parameter_create/keyform_set and physics_put. Before any split or difference, infer local depth and occlusion from the actual reference, including where each lock covers or passes behind others. Natural crossings and overlapping hidden volumes are expected; bangs and side hair have no fixed category-wide order. Generate complete logical locks, assemble usable candidates early, and judge a coherent hairstyle with plausible attachments and gap-free intended motion, not exact original edges or root coordinates. Harmless contour and tone differences are acceptable; do not stop after a fixed retry quota or delay all rigging for isolated perfection. Always request a declared uniform RGB matte, not transparent output. Choose and record background_color in asset_prepare_reference; white/black are useful defaults, but another suitable RGB is allowed. Pass the actual color as solid_background to asset_import_png with reference_id. MCP retains the original PNG, removes the matte and decontaminates a narrow edge band. Use asset_inspect for raw/processed pixels and diagnostics; asset_reprocess accepts foreground protection and background hole points in original PNG coordinates. A checkerboard or nonuniform matte is a diagnostic failure, not successful transparency. Change matte or hints when needed; judge small residual edges in normal-size composition.

	These instructions are mandatory whenever this PSD2Live MCP server is invoked, regardless of the task type. If the host supports skills, read psd2live-rigging before using any PSD2Live tool; also read hair-separation for hair work. This requirement is scoped to skill/MCP use and does not apply to unrelated repository programming.

	IMAGE-GENERATOR GATE: Treat every painted difference or expression variant, part/layer split, overlap removal, occlusion completion, reconstruction, inpaint, outpaint, or new drawable as an image-generation/editing task. Before any shell or Python raster work, make an actual host image-tool call for every output piece that creates, guesses, reconstructs, or changes illustrated pixels. Use Nano Banana Pro (Nano Banana Pro/NBP) when exposed, otherwise GPT Image 2 (gpt-image-2), otherwise an equivalent host-native generator. In Codex/ChatGPT, load imagegen and call image_gen; in Gemini/Antigravity, call Nano Banana Pro when available. For assembly defects first check depth order, placement and motion coverage; call the generator again only when painted pixels need correction. Retain useful candidates and continue independent work.

	The PSD2Live MCP server provides reference Views and imports outputs; it does not contain the host-private image generator. The absence of an image-generation tool in this MCP tool list is not evidence that the host lacks one: leave the MCP call chain, invoke Nano Banana Pro/NBP, GPT Image 2, or the host's image tool, then return to asset_import_png. Never use Python, PIL/Pillow, OpenCV, Matplotlib, SVG, Canvas, ImageMagick, shell scripts, procedural masks/polygons, dilation, smearing, or texture cloning to draw a draft, fallback, separated part, variant, or hidden structure. Code is allowed only for byte transport, diagnostics, exact unchanged-pixel extraction/cropping, and non-creative alpha cleanup after native generation. If no native image tool exists, stop at the last reversible state instead of fabricating artwork.

	Discover available host and MCP capabilities instead of assuming an exact host-side tool name. Use stable object IDs and direct View tools; never infer source coordinates from application screenshots. View tools return a composited PNG plus a reversible pixel-to-canvas spatial reference, not a PSD. Preserve spatialReferenceId when creating replacement pixels. Prefer transparent output.

	Discover tools/list (including pagination or host search). Use asset_prepare_reference -> host image editing -> asset_import_png/asset_inspect -> asset_register -> asset_preview_composite -> layer_add_from_asset -> layer_set_placement if needed -> layer_finalize_placement -> optional dedicated Warp/keyforms/physics -> posed composition. agent_get_workflow exposes this guidance when skills are unavailable. References contain a clean source image and a separate labeled context; labels must never be painted into the target. Before generation choose root/tip and preferably a noncollinear side anchor in source canvas coordinates and record local depth and hidden coverage. Use reference_id and registration_id for generated assets. Frame registration is appropriate only when the generator kept the declared frame; declare generated_pixel_rect/source_canvas_rect for padding or crops. When content was recentered or resized, mark matching generated_anchors in the full original PNG and use landmarks registration. Pixel resolution and alpha bounds never determine target size. Coordinates are top-left, X right, Y down. Mirrors require explicit mirror_x/mirror_y; do not flip to compensate for unexplained rig drift. Registration instances are immutable; create another registration to adjust position, scale or rotation and apply it with layer_set_placement. This recomputes from the original processed pixels. Imported replacements immediately inherit the reference source layer’s existing parent Warp (or explicit parent_deformer_id). For example front hair 1/2/3 belong inside the existing front-hair Warp. There is no unbound-layer mode. Placement finalization marks readiness for dedicated edits; it does not create the first binding. Inherited parent motion is preserved during positioning. Once a piece has dedicated Warp/keyform/glue edits or finalized placement, whole-rig relocation is outside this version; do not erase animation. Add an independent child Warp/output parameter/physics group only when requested. Physics requires corresponding sway keyforms. Preserve shared parent motion and test the intended range. Legacy spatial_reference_id imports map the PNG or declared source_pixel_rect to the referenced canvas; new reference_id imports require explicit registration. Mutations rebuild the actual source, mesh, rig, and export preview before committing history. Soft deletion remains recoverable. Use object_get, keyform_set, keyform_delete, keyform_copy, and rig_k_pose for geometry and visual channels at arbitrary N-dimensional parameter coordinates. For hair separation, load the hair-separation prompt; isolated Views diagnose coverage, while assembled neutral and intended-range posed Views determine acceptance. Exclude the original source from replacement trial compositions without erasing its recoverable pixels.
""".trimIndent()
