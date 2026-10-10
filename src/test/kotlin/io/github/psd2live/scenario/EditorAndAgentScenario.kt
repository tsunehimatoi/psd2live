package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.createAgentMcpServer
import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ProjectController
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.*

/**
 * The editor and an agent on the same workspace, through the real desktop backend and the MCP server. The agent's
 * edits, batches, retries and conflicts behave as the contract says and show in the editor; the user's own gestures
 * (sliders, settings switches, undo, save, reopen) leave the editor showing exactly the committed document.
 */
class EditorAndAgentScenario {
	@TempDir lateinit var temp: Path

	private class Editor(val vm: PSD2LiveViewModel, val workspace: DesktopWorkspace, val operations: WorkspaceOperations, val server: Server)

	private fun editor(body: suspend Editor.() -> Unit) = runBlocking {
		PSD2LiveViewModel().use { vm ->
			vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
			DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
				vm.attachWorkspace(workspace)
				WorkspaceOperations(workspace).use { operations ->
					Editor(vm, workspace, operations, createAgentMcpServer(workspace, operations, AgentToolProfile.CORE)).body()
				}
			}
		}
	}

	private val client = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
		if (method.name == "getSessionId") "test" else null
	} as ClientConnection

	/** An MCP tool call as an agent makes it; the structured result, or the error object. */
	private suspend fun Editor.agent(tool: String, arguments: JsonObject): JsonObject {
		val result = server.tools.getValue(tool).handler.invoke(client, CallToolRequest(CallToolRequestParams(tool, arguments)))
		return result.structuredContent ?: error("No structured result from $tool")
	}

	private suspend fun Editor.call(operation: String, request: JsonObject): JsonObject =
		agent("workspace_call", buildJsonObject { put("operation", operation); put("request", request) })

	private fun JsonObject.data(): JsonObject = this["data"]?.jsonObject?.let { it["result"]?.jsonObject ?: it }
		?: fail("Expected a result, got $this")

	private fun JsonObject.errorCode(): String = (this["error"] ?: this["data"]?.jsonObject?.get("error"))?.jsonObject?.get("code")?.jsonPrimitive?.content
		?: fail("Expected an error, got $this")

	/** Waits until the editor has applied everything queued and is idle. */
	private suspend fun Editor.settle() {
		repeat(3) { withContext(Dispatchers.Main) { yield() } }
		withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy && !it.isAnalyzing && !it.isGenerating } }
		withContext(Dispatchers.Main) { yield() }
	}

	/** The editor shows the committed document: the same rig, skeleton and motions, and no error. */
	private suspend fun Editor.consistent(step: String) {
		settle()
		val state = vm.state.value
		assertNull(state.errorMessage, "[$step] the editor reports an error")
		val queries = workspace.captureQueries()
		val committed = assertNotNull(queries.currentPuppet(), "[$step] nothing is loaded")
		val shown = assertNotNull(state.previewModel, "[$step] the editor shows no model").rig.puppet
		if (Oracles.hash(committed) != Oracles.hash(shown))
			fail("[$step] the editor shows another rig than the committed one:\n    " + Oracles.difference(committed, shown).joinToString("\n    "))
		assertEquals(queries.skeletonSpec(), state.rigEdits.skeleton, "[$step] the editor shows another skeleton")
		assertEquals(queries.motionClips(), state.rigEdits.motionClips, "[$step] the editor shows other motions")
		assertEquals(workspace.snapshot().historyHeadNodeId, state.historySnapshot?.headNodeId, "[$step] the editor shows another history head")
	}

	private fun Editor.state(): String = workspace.snapshot().state!!

	@Test fun anAgentRigsTheCharacterAndTheEditorFollows() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val layers = workspace.snapshot().layers.associate { it.sourceName to it.id }

		// A batch whose last member fails publishes nothing, and its retry recovers the same failure.
		val history = workspace.history()
		val failing = buildJsonObject {
			put("request_id", "classify-1"); put("state", state())
			putJsonArray("edits") {
				add(buildJsonObject { put("operation", "layer_classify"); put("request", req("layer_id" to layers.getValue("sleeve_l"), "role" to "handwear", "side" to "left")) })
				add(buildJsonObject { put("operation", "parameter_update"); put("request", req("parameter_id" to "NoSuchParameter", "name" to "x")) })
			}
		}
		val failed = call("workspace_apply_edits", failing)
		failed.errorCode()
		assertEquals(history, workspace.history(), "a failed batch added history")
		assertEquals(failed, call("workspace_apply_edits", failing), "a retried request recovers another outcome")
		consistent("failed batch")

		val classified = call("workspace_apply_edits", buildJsonObject {
			put("request_id", "classify-2"); put("state", state())
			putJsonArray("edits") {
				for ((name, role) in listOf("sleeve_l" to "left", "sleeve_r" to "right")) add(buildJsonObject {
					put("operation", "layer_classify"); put("request", req("layer_id" to layers.getValue(name), "role" to "handwear", "side" to role))
				})
			}
		}).data()
		assertEquals(1, workspace.history().nodes.size - history.nodes.size, "a batch is one history node: $classified")
		call("skeleton_auto", req("state" to state())).data()
		consistent("skeleton")
		assertNotNull(vm.state.value.rigEdits.skeleton, "the editor does not show the agent's skeleton")

		// A dry run names the objects the commit then makes.
		val sleeve = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == layers.getValue("sleeve_r") }.key
		val warp = buildJsonArray { add(buildJsonObject { put("operation", "canvas_warp"); put("request", req("name" to "Cuff", "meshes" to listOf(sleeve))) }) }
		val preview = call("workspace_preview_edits", buildJsonObject { put("state", state()); put("edits", warp) }).data()
		val applied = call("workspace_apply_edits", buildJsonObject { put("request_id", "warp-1"); put("state", state()); put("edits", warp) }).data()
		assertEquals(preview["changed"], applied["changed"], "the dry run and the commit made different objects")
		consistent("warp")

		// An agent working from a stale state is refused without touching the workspace.
		val stale = call("workspace_apply_edits", buildJsonObject { put("request_id", "stale"); put("state", "generation:stale"); put("edits", warp) })
		assertEquals("state_conflict", stale.errorCode())

		// Every query an agent can make without arguments answers within its published result schema.
		for (definition in operations.registry.definitions().filter { it.kind == WorkspaceOperationKind.QUERY && !it.jobBacked }) {
			try { operations.registry.invoke(definition.id, JsonObject(emptyMap()), WorkspaceOperationContext(MutationAuthor.AGENT)) }
			catch (violation: WorkspaceOutputContractFailure) { fail("${definition.id} answered outside its result schema: ${violation.message}") }
			catch (_: Exception) { /* needs arguments, or a workspace in another state */ }
		}
	}

	@Test fun theUsersGesturesLeaveTheEditorShowingTheCommittedDocument() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val root = workspace.snapshot().historyHeadNodeId!!
		vm.setFeatureDisplacementEnabled(true)
		consistent("settings switch")
		vm.createMotionClip("Wave")
		consistent("motion")
		vm.undoHistory(); consistent("undo")
		vm.redoHistory(); consistent("redo")

		val archive = temp.resolve("figure.psd2live")
		val saved = vm.saveProjectNow(archive)
		assertFalse(vm.state.value.projectDirty, "a saved project is dirty")
		val nodes = workspace.history().nodes.size
		vm.saveProjectNow(archive)
		assertEquals(nodes, workspace.history().nodes.size, "saving an unchanged project added a revision")
		val shown = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		ProjectController(vm).open(workspace, archive)
		consistent("reopened")
		assertEquals(saved, workspace.snapshot().historyHeadNodeId)
		assertEquals(shown, Oracles.hash(vm.state.value.previewModel!!.rig.puppet), "the reopened project shows another rig")
		workspace.checkoutHistory(root, MutationAuthor.USER)
		consistent("back to the import")

		// A slider moved while a settings switch is still committing keeps its value.
		vm.setFeatureDisplacementEnabled(true)
		vm.setParameterValue(ParameterId("ParamAngleX"), 20f)
		consistent("a slider moved during a settings commit")
		assertEquals(20f, vm.state.value.parameterValues[ParameterId("ParamAngleX")], "the slider jumped back")
	}
}
