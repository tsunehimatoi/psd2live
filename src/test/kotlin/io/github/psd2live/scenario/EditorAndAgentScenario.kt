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

	@Test fun clearingTheMaterialEditorKeepsItsSessionAndUndoHistory() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		withContext(Dispatchers.Main) {
			val layers = vm.state.value.previewModel!!.analysis.source.layers
			vm.selectLayer(layers.first().id.raw)
			val materialId = assertNotNull(workspace.captureQueries().layoutState()).layout.layerMaterials.getValue(layers.last().id.raw)
			val canvas = io.github.psd2live.ui.CanvasEditor(vm, vm.state.value.activeWorkspace.id, "material-window")
			canvas.hierarchyMode = io.github.psd2live.ui.EditHierarchyMode.PAINT
			val session = assertNotNull(canvas.startPaintSession("material:$materialId", forceReload = true))
			val original = session.workingImage.getRGB(0, 0, session.docWidth, session.docHeight, null, 0, session.docWidth)
			canvas.clearCurrentLayerPaint()
			assertSame(session, canvas.paintSession)
			assertEquals("material:$materialId", session.layerId)
			assertTrue(session.isDirty)
			assertTrue(session.workingImage.getRGB(0, 0, session.docWidth, session.docHeight, null, 0, session.docWidth).all { it ushr 24 == 0 })
			canvas.undoPaint()
			assertContentEquals(original, session.workingImage.getRGB(0, 0, session.docWidth, session.docHeight, null, 0, session.docWidth))
			canvas.closePaintSession()
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

	@Test fun aLipOutlineRefusesPaintWithAHintAndAParameterUnbindsFromTheSelection() = editor {
		workspace.importPsd(Characters.mouthPsd(temp).toString(), true)
		consistent("imported mouth")
		val model = vm.state.value.previewModel!!
		val (lip, lipLayer) = model.rig.layerIdByDrawableId.entries.first { it.value.contains("mouth-lip") }
		// The outline is drawn from the mouth layer on every build: no pixels of its own to paint.
		val refused = assertFailsWith<IllegalArgumentException> { workspace.beginPaintSession(state(), lipLayer) }
		assertEquals(GENERATED_LIP_PAINT, refused.message)
		withContext(Dispatchers.Main) {
			assertNull(vm.beginPaintSession(lip))
			assertEquals(io.github.psd2live.i18n.tr("editor.paint.generatedLip"), vm.state.value.errorMessage)
			vm.clearErrorMessage()
		}
		val drawable = model.rig.puppet.drawables.first { mesh -> mesh.geometryGrid?.axes.orEmpty().isNotEmpty() }
		val parameter = drawable.geometryGrid!!.axes.first().parameterId
		fun bound() = vm.state.value.previewModel!!.rig.puppet.drawables.single { it.id == drawable.id }.let { mesh ->
			mesh.geometryGrid?.axes.orEmpty().any { it.parameterId == parameter } ||
				mesh.channelGrids.gridsByChannel.values.any { grid -> grid.axes.any { it.parameterId == parameter } }
		}
		withContext(Dispatchers.Main) {
			vm.selectLayer(model.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw)
			vm.unbindParameterFromSelection(parameter)
		}
		consistent("unbound ${parameter.raw}")
		assertFalse(bound(), "the selected mesh must no longer follow ${parameter.raw}")
		withContext(Dispatchers.Main) { vm.undoHistory() }
		consistent("undo unbind")
		assertTrue(bound(), "undo must bind the mesh again")
	}

	@Test fun mouthPresetAndThicknessChangePreviewAndSurviveReopening() = editor {
		workspace.importPsd(Characters.mouthPsd(temp).toString(), true)
		consistent("imported mouth")
		vm.setMouthOutlineEnabled(true)
		consistent("mouth outline")
		val initial = vm.state.value.previewModel!!
		assertTrue(initial.analysis.layers.any { it.source is io.github.psd2live.core.MouthLipLayer })
		fun mouthPositions() = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(vm.state.value.previewModel!!.rig.puppet,
			mapOf(ParameterId("ParamMouthOpenY") to 0.7f, ParameterId("ParamMouthForm") to 0f)).worldPositions
		val before = mouthPositions()
		vm.setMouthShapeCurve("flat", io.github.psd2live.core.MouthCurve.preset("flat"))
		consistent("mouth shape")
		val shaped = mouthPositions()
		assertTrue(before.any { (id, vertices) -> shaped[id]?.let { next -> vertices.size != next.size || vertices.indices.any { kotlin.math.abs(vertices[it] - next[it]) > 0.1f } } == true },
			"the mouth preset must change the actual posed geometry")
		val original = vm.state.value.rigEdits.authoringJournal.toList()
		val initialHash = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		vm.beginEditorField("setMouthThickness")
		vm.setMouthThickness(6f)
		withTimeout(20_000) { vm.state.first { it.previewModel?.config?.mouthThickness == 6f } }
		assertNotEquals(initialHash, Oracles.hash(vm.state.value.previewModel!!.rig.puppet), "thickness must change the field preview")
		assertEquals(original, vm.state.value.rigEdits.authoringJournal)
		vm.endEditorField("setMouthThickness")
		consistent("mouth thickness")
		val head = workspace.snapshot().historyHeadNodeId
		repeat(3) {
			vm.beginEditorField("setMouthThickness"); vm.endEditorField("setMouthThickness")
			consistent("unchanged mouth field")
		}
		assertEquals(head, workspace.snapshot().historyHeadNodeId)
		val shown = Oracles.hash(vm.state.value.previewModel!!.rig.puppet)
		val archive = temp.resolve("mouth-edit.psd2live")
		vm.saveProjectNow(archive)
		workspace.open(archive)
		consistent("reopened mouth")
		assertEquals(shown, Oracles.hash(vm.state.value.previewModel!!.rig.puppet))
		Studio.run(temp, io.github.psd2live.ui.state.WorkspaceStateCodec.document(vm.state.value), checks = emptySet()) {
			exportMatchesEditor(this)
		}
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

	@Test fun presetSwitchAndFieldInOneGestureKeepTheNormalizedJournal() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		vm.setParameterValue(ParameterId("ParamAngleX"), 10f)
		consistent("authored pose")
		vm.beginEditorGesture()
		vm.setMeshOnly(true)
		vm.setMeshOnly(false)
		vm.setHeadStrength(2f)
		vm.endEditorGesture()
		consistent("preset gesture")
		assertEquals(2f, vm.state.value.previewModel!!.config.headTurnStrength)
		assertEquals(0f, vm.state.value.parameterValues[ParameterId("ParamAngleX")], "off then on must still release the authored pose")
		vm.saveProjectNow(temp.resolve("preset-gesture.psd2live"))
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

	@Test fun aSliderDragReachesRecoveryOnRelease() = editor {
		workspace.importPsd(Characters.figurePsd(temp).toString(), true)
		consistent("imported")
		val angle = ParameterId("ParamAngleX")
		val store = io.github.psd2live.project.WorkspaceStore(temp.resolve("store"))
		val file = store.projectRoot(vm.state.value.projectId!!).resolve("recovery.json")
		suspend fun written(): java.nio.file.attribute.FileTime? {
			workspace.flushProjectPersistence()
			return if (java.nio.file.Files.isRegularFile(file)) java.nio.file.Files.getLastModifiedTime(file) else null
		}
		val before = written()
		withContext(Dispatchers.Main) {
			vm.beginParameterScrub()
			for (value in listOf(5f, 10f, 15f)) vm.setParameterValueFromPanel(angle, value)
		}
		settle()
		assertEquals(15f, vm.state.value.parameterValues[angle], "the canvases do not show the drag")
		// Each sample used to encode the whole document and rewrite the checkpoint.
		assertEquals(before, written(), "a sample of the drag rewrote the recovery checkpoint")
		withContext(Dispatchers.Main) { vm.endParameterScrub() }
		consistent("released")
		written()
		val recovered = assertNotNull(store.loadRecoveryCheckpoint(vm.state.value.projectId!!), "nothing to recover after the drag")
		assertEquals(15f, recovered.presentation["parameterValues"]?.jsonObject?.get(angle.raw)?.jsonPrimitive?.float, "recovery misses the released pose")
	}
}
