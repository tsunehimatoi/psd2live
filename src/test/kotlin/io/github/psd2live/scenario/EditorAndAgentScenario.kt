package io.github.psd2live.scenario

import io.github.psd2live.agent.AgentToolProfile
import io.github.psd2live.agent.createAgentMcpServer
import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
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
		withTimeout(20_000) { vm.state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.canvasEditBusy && !it.isAnalyzing && !it.isGenerating } }
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

	@Test fun backgroundPaintEditsQueueAndEndWithTheSession() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		withContext(Dispatchers.Main) {
			val layers = vm.state.value.previewModel!!.analysis.source.layers
			vm.selectLayer(layers.first().id.raw)
			val materialId = assertNotNull(workspace.captureQueries().layoutState()).layout.layerMaterials.getValue(layers.last().id.raw)
			val canvas = io.github.psd2live.ui.CanvasEditor(vm, vm.state.value.activeWorkspace.id, "material-window")
			canvas.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.PAINT
			val session = assertNotNull(canvas.startPaintSession("material:$materialId", forceReload = true))
			// A selection change made while a clear runs waits for it rather than being dropped.
			canvas.clearCurrentLayerPaint()
			canvas.selectAllPaint()
			canvas.awaitPaintWork()
			assertTrue(session.isDirty)
			assertTrue(session.hasSelection)
			assertFalse(canvas.paintWorking)
			// Ending the session with edits queued lets go of it at once and runs nothing more on it.
			canvas.clearCurrentLayerPaint()
			canvas.deselectPaint()
			canvas.closePaintSession()
			assertNull(canvas.paintSession)
			assertFalse(canvas.paintWorking)
			assertNull(canvas.error)
		}
	}

	@Test fun presetPatchesKeepTheCommittedJournalEvenWithStalePreviewMetadata() = editor {
		workspace.importPsd(Characters.mouthPsd(temp).toString(), true)
		consistent("imported mouth")
		call("parameter_update", req("state" to state(), "parameter_id" to "ParamAngleX", "name" to "Authored turn")).data()
		consistent("authored parameter")
		val original = vm.state.value.rigEdits.authoringJournal.toList()
		vm.setStateForTest(vm.state.value.copy(rigEdits = io.github.psd2live.core.RigEditOverlay.Empty))
		vm.setMouthThickness(4f)
		consistent("settings patch with stale preview metadata")
		assertEquals(original, vm.state.value.rigEdits.authoringJournal.take(original.size))
		assertEquals("Authored turn", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
	}

	@Test fun anAgentReadsBackTheStaticFieldsItWrites() = editor {
		workspace.importPsd(Characters.mouthPsd(temp).toString(), true)
		consistent("imported mouth")
		val puppet = vm.state.value.previewModel!!.rig.puppet
		val (mask, masked) = puppet.drawables[0].id.raw to puppet.drawables[1].id.raw
		val deformer = puppet.deformers.first()
		val kind = if (deformer is org.umamo.runtime.model.Deformer.Warp) "warp" else "rotation"
		call("rig_edit_structure", req("state" to state(), "edits" to listOf(
			mapOf("action" to "static", "kind" to "mesh", "id" to masked, "blend_mode" to "Multiply", "masked_by" to listOf(mask),
				"invert_mask" to true, "opacity" to 0.5, "screen_color" to listOf(0.25, 0, 0), "user_data" to "note", "selectable" to false),
			mapOf("action" to "static", "kind" to kind, "id" to deformer.id.raw, "opacity" to 0.75, "selectable" to false)))).data()
		consistent("static fields")
		suspend fun static(target: String) = call("workspace_inspect", req("target" to target)).data().getValue("static").jsonObject
		val read = static("mesh:$masked")
		assertEquals("Multiply", read["blend_mode"]?.jsonPrimitive?.content)
		assertEquals(listOf(mask), read["masked_by"]?.jsonArray?.map { it.jsonPrimitive.content })
		assertEquals(true, read["invert_mask"]?.jsonPrimitive?.boolean)
		assertEquals(0.5f, read["opacity"]?.jsonPrimitive?.float)
		assertEquals(listOf(0.25f, 0f, 0f), read["screen_color"]?.jsonArray?.map { it.jsonPrimitive.float })
		assertEquals("note", read["user_data"]?.jsonPrimitive?.content)
		assertEquals(false, read["selectable"]?.jsonPrimitive?.boolean)
		val plain = static("mesh:$mask")
		assertEquals("Normal", plain["blend_mode"]?.jsonPrimitive?.content)
		assertEquals(emptyList(), plain["masked_by"]?.jsonArray?.toList())
		assertEquals(1f, plain["opacity"]?.jsonPrimitive?.float)
		val deformed = static("$kind:${deformer.id.raw}")
		assertEquals(0.75f, deformed["opacity"]?.jsonPrimitive?.float)
		assertEquals(false, deformed["selectable"]?.jsonPrimitive?.boolean)
	}

	@Test fun aColorGestureAndTopologyChangesKeepUserGroupsAndCommitOnce() = editor {
		workspace.importPsd(Characters.mouthPsd(temp).toString(), true)
		consistent("imported mouth")
		val lip = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.first { it.value.contains("mouth-lip") }.key
		call("vertex_group_update", req("state" to state(), "target" to "mesh:$lip", "name" to "Pinned lip", "kind" to "pin", "rule" to "fill", "value" to 1f)).data()
		consistent("authored lip weights")
		val original = vm.state.value.rigEdits.authoringJournal.toList()
		val nodes = workspace.history().nodes.size
		vm.beginEditorField("mouthColor")
		repeat(100) { vm.setMouthColor(0x440000 + it * 16) }
		vm.setMouthColor(0xAABBCC)
		vm.setMouthThickness(5f)
		vm.setMouthShapeCurve("w", io.github.psd2live.core.MouthCurve.preset("w"))
		withTimeout(20_000) { vm.state.first { it.previewModel?.config?.mouthColor == 0xAABBCC && it.previewModel.config.mouthThickness == 5f } }
		assertEquals(nodes, workspace.history().nodes.size, "color samples must remain transient")
		vm.endEditorField("mouthColor")
		consistent("committed color gesture")
		assertEquals(nodes + 1, workspace.history().nodes.size, "one color gesture must create one history node")
		assertEquals(0xAABBCC, vm.state.value.previewModel!!.config.mouthColor)
		assertEquals(original, vm.state.value.rigEdits.authoringJournal.take(original.size))
		val group = vm.state.value.previewModel!!.rig.puppet.vertexGroups.single { it.name == "Pinned lip" }
		assertTrue(group.weights.all { it in 0f..1f })
		val shown = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		val archive = temp.resolve("mouth-color.psd2live")
		vm.saveProjectNow(archive)
		workspace.open(archive)
		consistent("reopened color gesture")
		assertEquals(shown, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
	}

	@Test fun theDeformMenuCopiesPastesInterpolatesAndResetsAKeyform() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val angle = ParameterId("ParamAngleX")
		val parameters = vm.state.value.previewModel!!.rig.puppet.parameters
		val range = parameters.single { it.id == angle }
		val warp = vm.state.value.previewModel!!.rig.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().first { warp ->
			warp.geometryGrid?.axes.orEmpty().any { axis -> axis.parameterId == angle && listOf(range.min, 0f, range.max).all { value -> axis.keys.any { it == value } } }
		}
		val axes = warp.geometryGrid!!.axes.map { it.parameterId.raw }
		fun key(value: Float) = axes.associateWith { id -> if (id == angle.raw) value else parameters.single { it.id.raw == id }.default }
		fun shape(value: Float) = io.github.psd2live.core.RigGeometryTools.geometry(vm.state.value.previewModel!!.rig.puppet, "warp", warp.id.raw, key(value)).points
		fun differs(a: FloatArray, b: FloatArray) = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }
		val turned = shape(range.max)
		assertTrue(differs(turned, shape(range.min)) > 1e-3f, "the two turns must differ for a paste to show")
		val canvas = withContext(Dispatchers.Main) {
			vm.selectDeformer(warp.id.raw)
			io.github.psd2live.ui.CanvasEditor(vm, vm.state.value.activeWorkspace.id, "deform-test").also {
				it.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
				vm.setParameterValue(angle, range.max)
			}
		}
		settle()
		withContext(Dispatchers.Main) {
			canvas.copyKeyform()
			assertEquals(key(range.max), canvas.keyformClipboard.single().key)
			vm.setParameterValue(angle, range.min)
		}
		settle()
		withContext(Dispatchers.Main) { canvas.runKeyformTool(io.github.psd2live.ui.CanvasEditor.KeyformToolAction.PASTE) }
		consistent("pasted the right turn onto the left")
		assertTrue(differs(turned, shape(range.min)) < 1e-4f, "the paste did not write the copied shape")
		withContext(Dispatchers.Main) { vm.setParameterValue(angle, 0f) }
		settle()
		withContext(Dispatchers.Main) {
			assertTrue(angle.raw in canvas.keyformInterpolationAxes(), "the neutral key sits between the two turns")
			canvas.runKeyformTool(io.github.psd2live.ui.CanvasEditor.KeyformToolAction.INTERPOLATE, angle.raw)
		}
		consistent("interpolated the neutral key")
		assertTrue(differs(turned, shape(0f)) < 1e-3f, "both turns are the same shape, so the middle must be too")
		withContext(Dispatchers.Main) { vm.undoHistory() }
		consistent("undo the interpolation")
		withContext(Dispatchers.Main) { vm.setParameterValue(angle, range.min) }
		settle()
		val neutral = shape(0f)
		withContext(Dispatchers.Main) { canvas.runKeyformTool(io.github.psd2live.ui.CanvasEditor.KeyformToolAction.RESET_DEFAULT) }
		consistent("reset the left turn")
		assertTrue(differs(neutral, shape(range.min)) < 1e-4f, "the reset did not write the default-key shape")

		// Soft selection: dragging the middle control point carries its neighbours part of the way.
		val before = shape(range.min)
		val columns = warp.columns + 1
		val middle = (warp.rows / 2) * columns + warp.columns / 2
		withContext(Dispatchers.Main) {
			canvas.setEditLevel(1)
			canvas.activateTool(io.github.psd2live.ui.CanvasTool.SELECT)
			canvas.vertices = setOf(middle)
			canvas.softSelection = true
			val puppet = vm.state.value.previewModel!!.rig.puppet
			val viewport = io.github.psd2live.core.CanvasViewport(1.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
			canvas.softRadius = puppet.canvasWidth
			val t = assertNotNull(canvas.target())
			val at = canvas.screen(t.geometry.points, t, viewport)[middle]
			canvas.press(at, viewport, shift = false, alt = false)
			canvas.move(at + androidx.compose.ui.geometry.Offset(12f, 0f), viewport, shift = false)
			canvas.release()
		}
		consistent("soft drag")
		val after = shape(range.min)
		fun moved(i: Int) = kotlin.math.hypot(after[i * 2] - before[i * 2], after[i * 2 + 1] - before[i * 2 + 1])
		val neighbour = middle + 1
		assertTrue(moved(middle) > 1e-4f, "the picked point did not move")
		assertTrue(moved(neighbour) > 1e-6f && moved(neighbour) < moved(middle), "a neighbour must follow part of the way: ${moved(neighbour)} of ${moved(middle)}")

		// Paste with options: half way toward the copied right turn.
		withContext(Dispatchers.Main) {
			canvas.softSelection = false
			canvas.vertices = emptySet()
			vm.setParameterValue(angle, range.max)
		}
		settle()
		val right = shape(range.max)
		withContext(Dispatchers.Main) { canvas.copyKeyform(); vm.setParameterValue(angle, range.min) }
		settle()
		val left = shape(range.min)
		withContext(Dispatchers.Main) {
			canvas.runKeyformCommands(canvas.keyformPasteCommands(options = io.github.psd2live.ui.CanvasEditor.KeyformPasteOptions(ratio = 0.5f)))
		}
		consistent("blended the right turn into the left")
		val blended = shape(range.min)
		assertTrue(blended.indices.all { kotlin.math.abs(blended[it] - (left[it] + right[it]) / 2f) < 1e-3f }, "the blend did not land half way")

		// Multi-key editing: every key of the warp half transparent, one history step.
		withContext(Dispatchers.Main) {
			canvas.runKeyformCommands(listOf(buildJsonObject {
				put("op", "multi_key"); put("targets", JsonArray(canvas.multiKeyTargets().map(::JsonPrimitive))); put("opacity", 0.5f)
			}))
		}
		consistent("multi-key opacity")
		val owner = org.umamo.runtime.model.KeyformOwner.Deformer(warp.id)
		for (value in listOf(range.min, 0f, range.max)) assertEquals(0.5f, (io.github.psd2live.core.KeyformTools.channelAt(vm.state.value.previewModel!!.rig.puppet, owner,
			org.umamo.runtime.model.FormChannel.OPACITY, key(value)) as org.umamo.runtime.model.ChannelValue.Scalar).value, 1e-4f)

		// Restore: every key back to the default-key shape.
		withContext(Dispatchers.Main) { canvas.runKeyformTool(io.github.psd2live.ui.CanvasEditor.KeyformToolAction.RESTORE) }
		consistent("restored the warp")
		val rest = shape(0f)
		for (value in listOf(range.min, range.max)) assertTrue(differs(rest, shape(value)) < 1e-4f, "key $value was not restored")

		// Temporary deform tool: drag the lattice's last corner; the warp's matching corner follows it, one step per drag.
		val beforeLattice = shape(range.min)
		withContext(Dispatchers.Main) {
			canvas.temporaryWarpDivisions = 1
			canvas.beginTemporaryWarp()
			val puppet = vm.state.value.previewModel!!.rig.puppet
			val viewport = io.github.psd2live.core.CanvasViewport(1.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
			val corner = canvas.temporaryWarpScreen(viewport).last()
			canvas.press(corner, viewport, shift = false, alt = false)
			canvas.move(corner + androidx.compose.ui.geometry.Offset(10f, 10f), viewport, shift = false)
			canvas.release()
		}
		consistent("temporary warp drag")
		val afterLattice = shape(range.min)
		// The lattice is padded around the shape, so every point follows a little; the one nearest the dragged corner most.
		val moves = (0 until beforeLattice.size / 2).map { kotlin.math.hypot(afterLattice[it * 2] - beforeLattice[it * 2], afterLattice[it * 2 + 1] - beforeLattice[it * 2 + 1]) }
		assertTrue(moves.max() > 1f, "the lattice drag moved nothing: ${moves.max()}")
		assertTrue(moves.min() < moves.max() * 0.2f, "the far points must barely follow: ${moves.min()} of ${moves.max()}")

		// After an undo the moved lattice no longer matches the shape: a press lays it again over what the canvas shows
		// and writes nothing; the next drag moves the points from the undone shape by the lattice's own move only.
		withContext(Dispatchers.Main) { vm.undoHistory() }
		consistent("undo the lattice drag")
		assertTrue(differs(beforeLattice, shape(range.min)) < 1e-4f, "undo did not take back the lattice drag")
		val history = workspace.history().nodes.size
		withContext(Dispatchers.Main) {
			val puppet = vm.state.value.previewModel!!.rig.puppet
			val viewport = io.github.psd2live.core.CanvasViewport(1.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
			val stale = canvas.temporaryWarpScreen(viewport).last()
			canvas.press(stale, viewport, shift = false, alt = false)
			canvas.release()
			val laid = canvas.temporaryWarpScreen(viewport)
			assertTrue((laid.last() - stale).getDistance() > 5f, "the lattice was not laid again over the undone shape")
			val first = laid.first()
			canvas.press(first, viewport, shift = false, alt = false)
			canvas.move(first + androidx.compose.ui.geometry.Offset(-6f, 0f), viewport, shift = false)
			canvas.release()
		}
		consistent("drag after undo")
		assertEquals(history + 1, workspace.history().nodes.size, "the re-laying press must not commit, the drag once")
		val again = shape(range.min)
		val near = (0 until again.size / 2).maxBy { kotlin.math.hypot(again[it * 2] - beforeLattice[it * 2], again[it * 2 + 1] - beforeLattice[it * 2 + 1]) }
		assertTrue(kotlin.math.abs(again[near * 2] - beforeLattice[near * 2]) <= 6.5f && kotlin.math.abs(again[near * 2 + 1] - beforeLattice[near * 2 + 1]) < 1e-2f,
			"the drag must start from the undone shape, not the first drag's")

		// Between keys a lattice press first snaps the pose onto the keys, so no key is added.
		val keysBefore = (vm.state.value.previewModel!!.rig.puppet.deformers.single { it.id == warp.id } as org.umamo.runtime.model.Deformer.Warp)
			.geometryGrid!!.axes.single { it.parameterId == angle }.keys.toList()
		withContext(Dispatchers.Main) { vm.setParameterValue(angle, range.max / 2f) }
		settle()
		withContext(Dispatchers.Main) {
			val puppet = vm.state.value.previewModel!!.rig.puppet
			val viewport = io.github.psd2live.core.CanvasViewport(1.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
			val point = canvas.temporaryWarpScreen(viewport).first()
			canvas.press(point, viewport, shift = false, alt = false)
			canvas.move(point + androidx.compose.ui.geometry.Offset(4f, 0f), viewport, shift = false)
			canvas.release()
		}
		consistent("lattice press between keys")
		val keysAfter = (vm.state.value.previewModel!!.rig.puppet.deformers.single { it.id == warp.id } as org.umamo.runtime.model.Deformer.Warp)
			.geometryGrid!!.axes.single { it.parameterId == angle }.keys.toList()
		assertEquals(keysBefore, keysAfter, "a lattice drag between keys added a key")

		// Shift+T's action ends it, and leaving Deform drops it.
		withContext(Dispatchers.Main) {
			canvas.endTemporaryWarp(); assertNull(canvas.temporaryWarp)
			canvas.beginTemporaryWarp(); assertNotNull(canvas.activeTemporaryWarp())
			canvas.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.SELECT); assertNull(canvas.temporaryWarp)
		}
	}

	@Test fun theVertexAndBezierLevelsCanBeOnTogether() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val warp = vm.state.value.previewModel!!.rig.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().first { it.rows >= 3 && it.columns >= 3 }
		val canvas = withContext(Dispatchers.Main) {
			vm.selectDeformer(warp.id.raw)
			io.github.psd2live.ui.CanvasEditor(vm, vm.state.value.activeWorkspace.id, "bezier-test").also {
				it.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.DEFORM
				it.setEditLevel(2)
				it.activateTool(io.github.psd2live.ui.CanvasTool.SELECT)
			}
		}
		settle()
		val puppet = vm.state.value.previewModel!!.rig.puppet
		val viewport = io.github.psd2live.core.CanvasViewport(4.0, 0.0, 0.0, puppet.canvasWidth, puppet.canvasHeight)
		fun points() = canvas.target()!!.let { canvas.screen(it.geometry.points, it, viewport) }
		// A lattice point clear of every anchor and handle, and a handle's place on screen.
		val (point, handle) = withContext(Dispatchers.Main) {
			canvas.ensureBezierState()
			val t = canvas.target()!!
			val state = assertNotNull(canvas.bezierState)
			val controls = (state.anchors.values.map { it.x to it.y } + state.handles.values.map { it.x to it.y })
				.map { (x, y) -> canvas.screen(floatArrayOf(x, y), t, viewport)[0] }
			val shown = points()
			val (key, h) = state.handles.entries.first()
			shown.indices.first { i -> controls.all { (it - shown[i]).getDistance() > 20f } } to (key to canvas.screen(floatArrayOf(h.x, h.y), t, viewport)[0])
		}

		// Bezier alone, as before: the lattice points are not picked, and the last level on stays on.
		withContext(Dispatchers.Main) {
			canvas.move(points()[point], viewport, shift = false)
			assertNull(canvas.hoveredVertex, "a lattice point was picked with only the Bezier level on")
			canvas.toggleEditLevel(2)
			assertEquals(setOf(2), canvas.editLevels, "the last level on must stay on")
		}

		// Both on: a lattice point and a Bezier handle are both picked.
		withContext(Dispatchers.Main) {
			canvas.toggleEditLevel(1)
			assertEquals(setOf(1, 2), canvas.editLevels)
			canvas.move(points()[point], viewport, shift = false)
			assertEquals(point, canvas.hoveredVertex, "the lattice point was not picked with both levels on")
			canvas.move(handle.second, viewport, shift = false)
			assertEquals(handle.first, canvas.hoveredBezierHandle, "the Bezier handle was not picked with both levels on")
		}

		// The lattice point drags with both on.
		val before = points()[point]
		withContext(Dispatchers.Main) {
			canvas.move(before, viewport, shift = false)
			canvas.press(before, viewport, shift = false, alt = false)
			canvas.move(before + androidx.compose.ui.geometry.Offset(30f, 0f), viewport, shift = false)
			canvas.release()
		}
		consistent("dragged a lattice point with both levels on")
		val after = points()[point]
		assertTrue(after.x - before.x > 20f, "the lattice point did not move with both levels on: $before -> $after")
	}

	@Test fun settledPresetFieldChangesThePosedGeometryOnlyOnce() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		call("parameter_update", req("state" to state(), "parameter_id" to "ParamAngleX", "name" to "Authored turn")).data()
		consistent("authored parameter")
		val before = vm.state.value.previewModel!!
		val face = before.rig.layerIdByDrawableId.entries.single { it.value == workspace.snapshot().layers.single { it.sourceName == "face" }.id }.key
		val motion = Oracles.motion(before.rig.puppet, face, mapOf("ParamAngleX" to 30f))
		assertTrue(motion > 0.1, "fixture must turn its head")
		vm.beginEditorField("setHeadStrength")
		vm.setHeadStrength(0f)
		withTimeout(20_000) { vm.state.first { it.previewModel?.config?.headTurnStrength == 0f } }
		assertTrue(Oracles.motion(vm.state.value.previewModel!!.rig.puppet, face, mapOf("ParamAngleX" to 30f)) < motion * 0.1,
			"the open field preview must show the new geometry before settling")
		assertEquals(before.config.rigEdits, vm.state.value.rigEdits, "preview checkpoints must stay transient")
		vm.setHeadStrength(2f)
		withTimeout(20_000) { vm.state.first { it.previewModel?.config?.headTurnStrength == 2f } }
		assertTrue(Oracles.motion(vm.state.value.previewModel!!.rig.puppet, face, mapOf("ParamAngleX" to 30f)) > motion * 1.5,
			"a later field sample must still use the committed generation baseline")
		vm.setHeadStrength(0f)
		withTimeout(20_000) { vm.state.first { it.previewModel?.config?.headTurnStrength == 0f } }
		vm.endEditorField("setHeadStrength")
		consistent("settled preset field")
		assertTrue(Oracles.motion(vm.state.value.previewModel!!.rig.puppet, face, mapOf("ParamAngleX" to 30f)) < motion * 0.1,
			"turning head strength off must change the actual geometry")
		val head = workspace.snapshot().historyHeadNodeId
		repeat(3) {
			vm.beginEditorField("setHeadStrength")
			vm.endEditorField("setHeadStrength")
			consistent("unchanged field focus")
		}
		assertEquals(head, workspace.snapshot().historyHeadNodeId, "unchanged focus must not create edits")
	}

	@Test fun queuedPresetFieldsKeepGeneratedCheckpointsAndSave() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		call("parameter_update", req("state" to state(), "parameter_id" to "ParamAngleX", "name" to "Authored turn")).data()
		consistent("authored parameter")
		val original = vm.state.value.rigEdits.authoringJournal.toList()
		val root = workspace.snapshot().historyHeadNodeId!!
		vm.setHeadStrength(1.5f)
		vm.setBodyStrength(1.7f)
		vm.setHeadStrength(2f)
		consistent("queued preset fields")
		assertEquals(2f, vm.state.value.previewModel!!.config.headTurnStrength)
		assertEquals(1.7f, vm.state.value.previewModel!!.config.bodyStrength)
		assertEquals(original, vm.state.value.rigEdits.authoringJournal.take(original.size))
		assertEquals("Authored turn", vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamAngleX" }.name)
		val shown = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		val head = workspace.snapshot().historyHeadNodeId!!
		workspace.checkoutHistory(root, MutationAuthor.USER)
		consistent("before preset fields")
		workspace.checkoutHistory(head, MutationAuthor.USER)
		consistent("replayed preset fields")
		assertEquals(shown, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
		val archive = temp.resolve("preset-fields.psd2live")
		vm.saveProjectNow(archive)
		workspace.open(archive)
		consistent("reopened preset fields")
		assertEquals(shown, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
		Studio.run(temp, io.github.psd2live.ui.state.WorkspaceStateCodec.document(vm.state.value), checks = emptySet()) {
			exportMatchesEditor(this)
		}
	}

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
				add(buildJsonObject { put("operation", "rig_generate"); put("request", buildJsonObject {}) })
			}
		}).data()
		assertEquals(1, workspace.history().nodes.size - history.nodes.size, "a batch is one history node: $classified")
		call("skeleton_auto", req("state" to state())).data()
		consistent("skeleton")
		assertNotNull(vm.state.value.rigEdits.skeleton, "the editor does not show the agent's skeleton")

		// A dry run names the objects the commit then makes.
		val sleeve = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == layers.getValue("sleeve_r") }.key
		val warp = buildJsonArray { add(buildJsonObject { put("operation", "canvas_warp"); put("request", req("id" to "CuffWarp", "name" to "Cuff", "meshes" to listOf(sleeve))) }) }
		val preview = call("workspace_preview_edits", buildJsonObject { put("state", state()); put("edits", warp) }).data()
		val applied = call("workspace_apply_edits", buildJsonObject { put("request_id", "warp-1"); put("state", state()); put("edits", warp) }).data()
		assertNotNull(preview["changed"], "the dry run must succeed")
		assertNotNull(applied["changed"], "the commit must succeed")
		// Commit changes also include explicit request IDs; compare the created object handles.
		val created = applied.getValue("changed").jsonArray.filter { it.jsonPrimitive.content.startsWith("warp:") }
		assertEquals(preview["changed"], JsonArray(created), "the dry run and the commit made different objects")
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
		withContext(Dispatchers.Main) {
			vm.undoHistory()
			assertTrue(vm.state.value.workspaceEditBusy, "undo must be tracked while drafts and history settle")
		}
		consistent("undo")
		withContext(Dispatchers.Main) {
			vm.redoHistory()
			assertTrue(vm.state.value.workspaceEditBusy, "redo must be tracked while drafts and history settle")
		}
		consistent("redo")

		val archive = temp.resolve("figure.psd2live")
		val saved = vm.saveProjectNow(archive)
		assertFalse(vm.state.value.projectDirty, "a saved project is dirty")
		val nodes = workspace.history().nodes.size
		vm.saveProjectNow(archive)
		assertEquals(nodes, workspace.history().nodes.size, "saving an unchanged project added a revision")
		val shown = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		workspace.open(archive)
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

	@Test fun undoAndRedoKeepThePoseTheUserSet() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val angle = ParameterId("ParamAngleX")
		vm.setParameterValue(angle, -10f)
		consistent("posed")
		val before = workspace.snapshot().historyHeadNodeId
		vm.setFeatureDisplacementEnabled(true)
		consistent("edited at the pose")
		val edited = workspace.snapshot().historyHeadNodeId
		assertNotEquals(before, edited, "the edit made no history node")
		withContext(Dispatchers.Main) { vm.undoHistory() }
		consistent("undo")
		assertEquals(before, workspace.snapshot().historyHeadNodeId)
		assertEquals(-10f, vm.state.value.parameterValues[angle], "undo moved the slider")
		withContext(Dispatchers.Main) { vm.redoHistory() }
		consistent("redo")
		assertEquals(edited, workspace.snapshot().historyHeadNodeId)
		assertEquals(-10f, vm.state.value.parameterValues[angle], "redo moved the slider")
	}

	@Test fun anAgentEditDuringAnOpenFieldLandsAndTheFieldCommitsOverIt() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		fun setting(key: String) = workspace.captureQueries().projectSettings().getValue(key).jsonPrimitive.int
		val nodes = workspace.history().nodes.size
		withContext(Dispatchers.Main) { vm.beginEditorField("texturePadding"); vm.setTexturePadding(5) }
		// The open field is only a draft: the agent's commit lands rather than being refused, and the draft stays shown.
		call("settings_update", req("state" to state(), "changes" to mapOf("alphaThreshold" to 40))).data()
		assertEquals(40, vm.state.value.alphaThreshold, "the editor does not show the agent's edit")
		assertEquals(5, vm.state.value.texturePadding, "the agent's commit dropped the open field")
		assertNotEquals(5, setting("texturePadding"), "the draft reached the runtime before the field closed")
		withContext(Dispatchers.Main) { vm.endEditorField("texturePadding") }
		consistent("field committed over the agent's edit")
		assertEquals(5, setting("texturePadding"))
		assertEquals(40, setting("alphaThreshold"))
		assertEquals(nodes + 2, workspace.history().nodes.size)

		// Both changing the same setting: the committed one wins and the editor drops its draft of it.
		withContext(Dispatchers.Main) { vm.beginEditorField("texturePadding"); vm.setTexturePadding(9) }
		call("settings_update", req("state" to state(), "changes" to mapOf("texturePadding" to 11))).data()
		assertEquals(11, vm.state.value.texturePadding)
		withContext(Dispatchers.Main) { vm.endEditorField("texturePadding") }
		settle()
		assertEquals(11, setting("texturePadding"))
		assertEquals(nodes + 3, workspace.history().nodes.size, "the dropped draft still committed")
		consistent("conflicting field dropped")
	}

	@Test fun savingRecordsTheRuntimeAndNotTheEditorsCopy() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val nodes = workspace.history().nodes.size
		val padding = workspace.captureQueries().projectSettings().getValue("texturePadding").jsonPrimitive.int
		// A value only the editor's state holds, never submitted as a draft, is not the project's.
		vm.setStateForTest(vm.state.value.copy(texturePadding = padding + 3))
		val archive = temp.resolve("figure.psd2live")
		vm.saveProjectNow(archive)
		assertEquals(nodes, workspace.history().nodes.size, "saving wrote the editor's copy back as a revision")
		assertTrue(vm.state.value.projectDirty, "a value the save left out counts as saved")
		assertEquals(padding, workspace.captureQueries().projectSettings().getValue("texturePadding").jsonPrimitive.int)
		workspace.open(archive)
		consistent("reopened")
		assertEquals(padding, vm.state.value.texturePadding, "the archive holds the editor's copy")
	}

	/** A split whose pieces the user deletes leaves the canvas's visible set, so a View that follows the canvas still renders. */
	@Test fun aViewFollowingTheCanvasRendersAfterALayerIsDeleted() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val source = vm.state.value.previewModel!!.analysis.source
		val layer = source.layers.first().id.raw
		val (w, h) = source.widthPx to source.heightPx
		// The pieces of a split, deleted one by one, as a user takes a split back.
		call("source_split_polygon", req("state" to state(), "layer_id" to layer, "names" to listOf("Left", "Right"),
			"piece_ids" to listOf("piece_l", "piece_r"), "polygon" to listOf(listOf(0, 0), listOf(w / 2, 0), listOf(w / 2, h), listOf(0, h)))).data()
		consistent("split")
		for (piece in listOf("piece_l", "piece_r")) {
			call("layer_delete", req("state" to state(), "layer_id" to piece)).data()
			consistent("deleted $piece")
		}
		val gone = setOf(layer, "piece_l", "piece_r")
		assertTrue((vm.state.value.effectiveVisibleLayerIds intersect gone).isEmpty(), "the canvas still shows ${vm.state.value.effectiveVisibleLayerIds intersect gone}")
		val view = call("view_render_model", req("viewport" to mapOf("mode" to "canvas_rect", "left" to 0, "top" to 0, "width" to 64, "height" to 64)))
		assertTrue((view.data().getValue("includedLayerIds").jsonArray.map { it.jsonPrimitive.content } intersect gone).isEmpty(), "rendered a deleted layer: $view")
	}

}
