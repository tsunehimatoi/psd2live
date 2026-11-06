package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.createAgentMcpServer
import io.github.psd2live.application.HeadlessPresentation
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.application.WorkspaceHost
import io.github.psd2live.application.WorkspaceOperations
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The MCP endpoint on the workspace host without a window: no view model or Compose state exists, and an agent still
 * lays out a project, generates it, renders views, saves, reopens and exports it. The same calls on the desktop
 * editor's host give the same document.
 */
class HeadlessHostScenario {
	@TempDir lateinit var temp: Path

	private val client = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
		if (method.name == "getSessionId") "test" else null
	} as ClientConnection

	private class Agent(val backend: WorkspaceBackend, val server: Server)

	private suspend fun Agent.call(operation: String, request: JsonObject): JsonObject {
		val result = server.tools.getValue("workspace_call").handler.invoke(client,
			CallToolRequest(CallToolRequestParams("workspace_call", buildJsonObject { put("operation", operation); put("request", request) })))
		val content = result.structuredContent ?: error("No structured result from $operation")
		if (content["ok"]?.jsonPrimitive?.boolean != true) fail("$operation failed: $content")
		return content["data"]?.jsonObject?.let { it["result"]?.jsonObject ?: it } ?: fail("$operation gave no data: $content")
	}

	private suspend fun Agent.state(): String = call("workspace_inspect", req("scope" to "project")).getValue("state").jsonPrimitive.content

	private suspend fun Agent.edit(operation: String, vararg fields: Pair<String, Any?>) =
		call(operation, req("state" to state(), *fields))

	/** A Layout project laid out against a reference, generated and saved; returns the saved head's revision. */
	private suspend fun Agent.layOut(files: Path): String {
		edit("project_create", "width" to 64, "height" to 64, "discard_unsaved" to true)
		edit("material_import", "paths" to listOf(Characters.disc(files, size = 32).toString()))
		val reference = call("material_list", req()).getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
		edit("layout_set_reference", "material_id" to reference)
		edit("material_crop", "id" to reference, "new_id" to "close",
			"canvas_rect" to mapOf("left" to 16, "top" to 16, "width" to 32, "height" to 32))
		edit("layer_place", "material_id" to "close", "layer_id" to "close")
		edit("layer_tag", "layer_ids" to listOf("close"), "set" to listOf("face"))
		edit("rig_generate")
		return backend.snapshot().revisionId ?: fail("No revision after generating")
	}

	@Test fun anAgentBuildsAProjectWithoutAWindow() = runBlocking {
		HeadlessPresentation().use { presentation ->
			WorkspaceHost(presentation, temp.resolve("store")).use { host ->
				presentation.attach(host)
				WorkspaceOperations(host).use { operations ->
					val agent = Agent(host, createAgentMcpServer(host, operations, AgentToolProfile.CORE))
					val revision = agent.layOut(Files.createDirectories(temp.resolve("files")))
					assertTrue(host.hasUnsavedChanges(), "a new project has unsaved changes")

					val view = agent.call("view_render_model", req("viewport" to mapOf("mode" to "canvas_rect", "left" to 0, "top" to 0, "width" to 64, "height" to 64),
						"reference" to mapOf("mode" to "difference", "opacity" to 1), "target_long_edge" to 128))
					assertNotNull(view["referenceDifference"], "the view compared nothing with the reference")

					val archive = temp.resolve("headless.psd2live")
					agent.edit("project_save_as", "path" to archive.toString())
					assertTrue(Files.isRegularFile(archive) && !host.hasUnsavedChanges(), "saving left the project unsaved")
					assertEquals(archive.toString(), host.captureQueries().snapshot().projectFile, "the queries do not report where it was saved")
					agent.edit("project_save")

					agent.edit("project_create", "width" to 32, "height" to 32, "discard_unsaved" to true)
					agent.edit("project_open", "path" to archive.toString(), "discard_unsaved" to true)
					assertEquals(revision, host.snapshot().revisionId, "the reopened project is another document")

					val output = Files.createDirectories(temp.resolve("export"))
					agent.edit("project_export_model", "output_directory" to output.toString())
					assertTrue(Files.list(output).use { files -> files.anyMatch { it.fileName.toString().endsWith(".model3.json") } },
						"the export wrote no model")
				}
			}
		}
	}

	@Test fun timelineAidsKeepTheStateWhenNothingChangesAndADeletedMotionsIdStaysTaken() = runBlocking {
		HeadlessPresentation().use { presentation ->
			WorkspaceHost(presentation, temp.resolve("store")).use { host ->
				presentation.attach(host)
				WorkspaceOperations(host).use { operations ->
					val agent = Agent(host, createAgentMcpServer(host, operations, AgentToolProfile.CORE))
					agent.layOut(Files.createDirectories(temp.resolve("files")))
					agent.edit("parameter_create", "parameter_id" to "ParamWheel", "name" to "Wheel", "min" to 0, "max" to 360, "default" to 0, "repeat" to true)
					val marked = agent.edit("timeline_bookmark_set", "parameter" to "ParamWheel", "bookmarked" to true).getValue("state").jsonPrimitive.content
					val again = agent.call("timeline_bookmark_set", req("state" to marked, "parameter" to "ParamWheel", "bookmarked" to true))
					assertEquals(marked, again.getValue("state").jsonPrimitive.content, "bookmarking a bookmarked parameter made the held state stale")

					val existingIds = host.captureQueries().motionClips().mapTo(HashSet()) { it.id }
					agent.edit("motion_create", "name" to "Custom spin")
					val firstId = host.captureQueries().motionClips().single { it.name == "Custom spin" }.id
					assertTrue(firstId !in existingIds)
					agent.edit("motion_set_key", "id" to firstId, "parameter" to "ParamWheel", "key" to mapOf("time" to 0, "value" to 0))
					agent.edit("timeline_freeze_set", "clip_id" to firstId, "parameter" to "ParamWheel", "frozen" to true, "value" to 90)
					agent.edit("motion_delete", "id" to firstId)
					agent.edit("motion_create", "name" to "Custom wave")
					val created = host.captureQueries().motionClips().single { it.name == "Custom wave" }.id
					assertNotEquals(firstId, created, "a new motion took the deleted one's ID and its frozen track")
					assertEquals(mapOf("ParamWheel" to 90f), host.savedProjectData().data.timelineAids.clip(firstId).frozen,
						"deleting the motion dropped the aids an undo would find")
					assertTrue(host.savedProjectData().data.timelineAids.clip(created).isEmpty, "the new motion inherited aids")
				}
			}
		}
	}

	@Test fun theDesktopHostGivesTheSameDocument() = runBlocking {
		val desktop = PSD2LiveViewModel().use { vm ->
			DesktopWorkspace(vm, temp.resolve("desktop")).use { workspace ->
				vm.attachWorkspace(workspace)
				WorkspaceOperations(workspace).use { operations ->
					Agent(workspace, createAgentMcpServer(workspace, operations, AgentToolProfile.CORE))
						.layOut(Files.createDirectories(temp.resolve("desktop-files"))) to vm.state.value.rawConfig()
				}
			}
		}
		val headless = HeadlessPresentation(desktop.second).use { presentation ->
			WorkspaceHost(presentation, temp.resolve("headless")).use { host ->
				presentation.attach(host)
				WorkspaceOperations(host).use { operations ->
					Agent(host, createAgentMcpServer(host, operations, AgentToolProfile.CORE)).layOut(Files.createDirectories(temp.resolve("headless-files")))
				}
			}
		}
		assertEquals(desktop.first, headless, "the same calls built different documents on the two hosts")
	}

	@Test fun theHeadlessDefaultsAreTheEditorsOwn() {
		val editor = PSD2LiveViewModel().use { it.state.value.rawConfig() }
		assertEquals(editor, PipelineConfig(), "a headless project would start from other settings than one made in the editor")
	}
}
