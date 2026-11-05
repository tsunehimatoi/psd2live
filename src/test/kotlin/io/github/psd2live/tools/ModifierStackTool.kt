package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.views.ModelPresetsSection
import io.github.psd2live.ui.views.ModifierAdditionDialog
import io.github.psd2live.ui.views.ModifierNativeValues
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.DrawableId
import kotlinx.coroutines.flow.first

/** Procedural source only. PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*ModifierStackTool'. */
class ModifierStackTool {
    @TempDir lateinit var temp: Path

    @Test fun panels() = runBlocking {
        requireTools()
        val project = SyntheticProject(temp.resolve("source"), 1, 48, 1)
        val source = project.tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 16, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val plain = WorkspacePreviewBuilder().build(source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
        val target = ModifierTarget("mesh", plain.rig.puppet.drawables.single().id.raw)
        val structure = RigModifierStack().add("rig.scaffold", 1, listOf(target), id = "structure")
        val structured = WorkspacePreviewBuilder().build(source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits.copy(modifierStack = structure.toJson())))
        val root = structured.rig.puppet.deformers.single { it.parent == null }.id.raw
        val swing = RigSwingEdit.single("template", "Swing", SwingKind.LATERAL, listOf(root), listOf("P")).let { it.copy(motions = it.motions.map { motion -> motion.copy(physics = null) }) }
        var stack = RigModifierStack().add("rig.scaffold", 1, listOf(target), id = "structure")
            .add("body/legacy", 1, listOf(target), buildJsonObject { put("scaffold", "structure") }, id = "body")
            .add("swing", 1, listOf(ModifierTarget("deformer", root)), swing.toJson(), id = "sway")
            .add("face/legacy", 1, listOf(target), buildJsonObject { put("scaffold", "structure") }, id = "face")
            .add("eye.smile", 1, listOf(target), buildJsonObject { put("scaffold", "structure"); put("base", "missing_eye") }, id = "smile")
        stack = stack.update("face") { it.copy(enabled = false) }
        val doc = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits.copy(modifierStack = stack.toJson()))
        val revision = WorkspaceRevisions.of(doc)
        project.tree.commit(project.tree.head().node.id, doc, revision, revision, "Modifiers", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val file = output("modifier-stack").toPath().resolve("modifiers.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), file)
        val originalLanguage = I18n.currentLanguage
        try {
            PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temp.resolve("workspace")).use { workspace ->
                vm.attachWorkspace(workspace); workspace.open(file)
                withContext(Dispatchers.Main) {
                    val nativeConfigurations = WorkspaceModifierPlan.nativeTypes.associateWith { type ->
                        workspace.captureQueries().modifierPlan(buildJsonObject { put("type", type)
                            putJsonArray("targets") { addJsonObject { put("kind", target.kind); put("id", target.id) } }
                        }).getValue("entries").jsonArray.single().jsonObject.getValue("config").jsonObject
                    }
                    for (language in AppLanguage.entries) for ((theme, colors) in ThemeCatalog.builtIns.map { it.id to it.colors }) for (scale in listOf(1f, 1.5f)) {
                        I18n.setLanguage(language, persist = false)
                        var inspection by mutableStateOf<String?>(null)
                        val scene = ImageComposeScene(600, 1000, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                            val state by vm.state.collectAsState()
                            CompactToolTheme(colors, uiScale = scale, fontScale = 1.1f) {
                                Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                                    val type = inspection
                                    if (type == null) {
                                        Column(Modifier.fillMaxSize().padding(Spacing.Margin).verticalScroll(rememberScrollState())) { ModelPresetsSection(state, vm) }
                                        if (vm.modifierAddition != null) ModifierAdditionDialog(state, vm)
                                    } else key(type) {
                                        Column(Modifier.fillMaxSize().padding(Spacing.Margin).verticalScroll(rememberScrollState())) {
                                            ModifierNativeValues(type, nativeConfigurations.getValue(type), state, true, vm) { _, _, done -> done(null) }
                                        }
                                    }
                                }
                            }
                        }
                        var time = 0L
                        suspend fun frames() { repeat(15) { time += 16_000_000L; scene.render(time).close(); yield() } }
                        fun save(name: String) { scene.render(time + 16_000_000L).use {
                            File(output("modifier-stack"), "${language.tag}-$theme-$scale-$name.png").writeBytes(requireNotNull(it.encodeToData()).bytes)
                        } }
                        suspend fun click(point: Offset) {
                            scene.sendPointerEvent(PointerEventType.Move, point)
                            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                            frames()
                        }
                        try {
                            frames(); save("stack")
                            if (language == AppLanguage.CHINESE && theme == "dark" && scale == 1f) {
                                val initial = vm.state.value.previewModel!!
                                val before = workspace.snapshot().state
                                val change = WorkspaceDocumentOperation("modifier_move", buildJsonObject { put("id", "sway"); put("index", 1) })
                                val preview = workspace.previewRegeneration(before, listOf(change))
                                scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 153f))
                                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 153f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                for (i in 0..12) {
                                    scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 153f - i * 4f), buttons = PointerButtons(isPrimaryPressed = true))
                                    frames()
                                    scene.render(time + 16_000_000L).use { File(output("modifier-stack"), "sorting-${i.toString().padStart(2, '0')}.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
                                }
                                scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 105f), button = PointerButton.Primary)
                                withTimeout(10_000) { vm.state.first { state ->
                                    state.rigEdits.modifierStack?.let { RigModifierStack.fromJson(it).instances.indexOfFirst { row -> row.id == "sway" } == 1 } == true && !state.workspaceEditBusy
                                } }
                                assertEquals(preview.getValue("candidate_revision").jsonPrimitive.content, workspace.captureQueries().modifierStack().getValue("revision").jsonPrimitive.content)
                                val pose = mapOf(StandardParameters.BODY_X to 8f, StandardParameters.BODY_LEAN to 4f, StandardParameters.PROPORTION to 5f,
                                    ParameterId(stack.instances.single { it.id == "sway" }.outputId("swing:lateral:0")) to 1f)
                                val evaluator = CpuDeformationEvaluator()
                                val first = evaluator.evaluate(initial.rig.puppet, pose).worldPositions.getValue(DrawableId(target.id))
                                val after = evaluator.evaluate(vm.state.value.previewModel!!.rig.puppet, pose).worldPositions.getValue(DrawableId(target.id))
                                assertTrue(first.indices.any { kotlin.math.abs(first[it] - after[it]) > .001f })
                                for (i in 13..24) { frames(); scene.render(time + 16_000_000L).use { File(output("modifier-stack"), "sorting-${i.toString().padStart(2, '0')}.png").writeBytes(requireNotNull(it.encodeToData()).bytes) } }
                                workspace.applyDocumentEdits(workspace.snapshot().state, "Restore order", listOf(WorkspaceDocumentOperation("modifier_move", buildJsonObject { put("id", "sway"); put("index", 2) })), MutationAuthor.USER)
                                frames()
                                vm.openModifierAddition()
                                val captured = vm.modifierAddition!!.targets
                                vm.selectDeformer(root)
                                withTimeout(10_000) { snapshotFlow { vm.modifierAddition }.first { it != null && !it.loading } }
                                assertEquals(captured, vm.modifierAddition!!.targets)
                                val addition = vm.modifierAddition!!
                                assertEquals(null, addition.error)
                                val candidateRevision = addition.preview!!.getValue("candidate_revision").jsonPrimitive.content
                                frames(); save("addition")
                                val plannedIds = addition.plan!!.getValue("entries").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                                vm.confirmModifierAddition()
                                withTimeout(10_000) { vm.state.first { state ->
                                    val present = state.rigEdits.modifierStack?.let { RigModifierStack.fromJson(it).instances.map { row -> row.id } }.orEmpty()
                                    plannedIds.all { it in present } && !state.workspaceEditBusy
                                } }
                                assertEquals(candidateRevision, workspace.captureQueries().modifierStack().getValue("revision").jsonPrimitive.content)
                                frames(); save("added")
                                workspace.applyDocumentEdits(workspace.snapshot().state, "Restore additions", plannedIds.map { id ->
                                    WorkspaceDocumentOperation("modifier_remove", buildJsonObject { put("id", id) })
                                }, MutationAuthor.USER)
                                frames()
                            }
                            click(Offset(52f * scale, 100f * scale)); save("expanded")
                            if (language == AppLanguage.CHINESE && theme == "dark" && scale == 1f) {
                                val head = workspace.history().headNodeId
                                val count = workspace.history().nodes.size
                                scene.sendPointerEvent(PointerEventType.Move, Offset(390f, 188f))
                                scene.sendPointerEvent(PointerEventType.Press, Offset(390f, 188f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                for (x in listOf(402f, 414f, 426f, 438f, 450f)) {
                                    scene.sendPointerEvent(PointerEventType.Move, Offset(x, 188f), buttons = PointerButtons(isPrimaryPressed = true)); frames()
                                    assertEquals(head, workspace.history().headNodeId, "a continuous slider cannot publish intermediate history")
                                }
                                scene.sendPointerEvent(PointerEventType.Release, Offset(450f, 188f), button = PointerButton.Primary)
                                withTimeout(10_000) { vm.state.first { state ->
                                    state.rigEdits.modifierStack?.let { RigModifierStack.fromJson(it).instances.single { row -> row.id == "body" }.config["strength"] != null } == true && !state.workspaceEditBusy
                                } }
                                assertEquals(count + 1, workspace.history().nodes.size)
                                frames(); save("strength")
                                workspace.applyDocumentEdits(workspace.snapshot().state, "Restore strength", listOf(WorkspaceDocumentOperation("modifier_update", buildJsonObject {
                                    put("id", "body"); put("type", "body/legacy"); putJsonObject("config") { put("scaffold", "structure") }
                                })), MutationAuthor.USER)
                                for (type in WorkspaceModifierPlan.nativeTypes) {
                                    vm.selectLayer(vm.state.value.previewModel!!.rig.ownLayerIdByDrawableId[target.id] ?: target.id)
                                    vm.openModifierAddition()
                                    if (type == "simulation") {
                                        val edit = RigSimEdit("template", "Simulation", SimKind.HAIR, listOf(target.id),
                                            inputs = listOf(PhysicsInput(StandardParameters.BODY_X.raw)),
                                            modes = 1, keys = 3, vertical = false)
                                        vm.previewModifierAddition(type, null, edit.toJson())
                                    } else vm.previewModifierAddition(type, null)
                                    withTimeout(10_000) { snapshotFlow { vm.modifierAddition }.first { it != null && !it.loading } }
                                    if (type == "swing") {
                                        frames()
                                        val unchanged = workspace.snapshot().state
                                        scene.sendPointerEvent(PointerEventType.Move, Offset(375f, 379f))
                                        scene.sendPointerEvent(PointerEventType.Press, Offset(375f, 379f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                        scene.sendPointerEvent(PointerEventType.Move, Offset(410f, 379f), buttons = PointerButtons(isPrimaryPressed = true)); frames()
                                        scene.sendPointerEvent(PointerEventType.Release, Offset(410f, 379f), button = PointerButton.Primary)
                                        withTimeout(10_000) { snapshotFlow { vm.modifierAddition }.first { it != null && !it.loading } }
                                        assertEquals(unchanged, workspace.snapshot().state, "pre-add controls must only change the isolated candidate")
                                        val planned = vm.modifierAddition!!.plan!!.getValue("entries").jsonArray.single().jsonObject.getValue("config").jsonObject
                                        assertTrue(RigSwingEdit.fromJson(planned).tilt > 0f)
                                    }
                                    val dialog = vm.modifierAddition!!
                                    assertEquals(null, dialog.error, type)
                                    assertEquals(listOf(target), dialog.targets)
                                    val candidate = dialog.preview!!.getValue("candidate_revision").jsonPrimitive.content
                                    val entry = dialog.plan!!.getValue("entries").jsonArray.single().jsonObject
                                    frames(); save("native-$type")
                                    vm.confirmModifierAddition()
                                    val addedId = entry.getValue("id").jsonPrimitive.content
                                    withTimeout(10_000) { vm.state.first { state ->
                                        state.rigEdits.modifierStack?.let { RigModifierStack.fromJson(it).instances.any { row -> row.id == addedId } } == true && !state.workspaceEditBusy
                                    } }
                                    assertEquals(candidate, workspace.captureQueries().modifierStack().getValue("revision").jsonPrimitive.content)
                                    workspace.applyDocumentEdits(workspace.snapshot().state, "Restore native addition", listOf(
                                        WorkspaceDocumentOperation("modifier_remove", buildJsonObject { put("id", addedId) })), MutationAuthor.USER)
                                    frames()
                                }
                                vm.openModifierAddition()
                                vm.previewModifierAddition("motion", null, preset = "natural:Blink")
                                val blink = withTimeout(10_000) { snapshotFlow { vm.modifierAddition }.first { it != null && !it.loading } }!!
                                assertEquals(null, blink.error)
                                val blinkEntries = blink.plan!!.getValue("entries").jsonArray
                                assertTrue(blinkEntries.any { it.jsonObject.getValue("type").jsonPrimitive.content == "eye/fitted" })
                                val config = blinkEntries.last().jsonObject.getValue("config").jsonObject
                                val stateBeforeEdit = workspace.snapshot().state
                                val editedClip = MotionClips.fromJson(config).copy(fadeIn = .2f)
                                vm.previewModifierAddition("motion", null, MotionClips.toJson(editedClip))
                                val edited = withTimeout(10_000) { snapshotFlow { vm.modifierAddition }.first { it != null && !it.loading } }!!
                                assertEquals(null, edited.error)
                                assertEquals(blinkEntries.dropLast(1), edited.plan!!.getValue("entries").jsonArray.dropLast(1))
                                assertEquals(stateBeforeEdit, workspace.snapshot().state)
                                frames(); save("natural-blink")
                                vm.dismissModifierAddition()
                            }
                            for (type in nativeConfigurations.keys) {
                                inspection = type; frames(); save("native-fields-$type")
                            }
                        } finally { scene.close() }
                    }
                }
            } }
        } finally { I18n.setLanguage(originalLanguage, persist = false) }
    }
}
