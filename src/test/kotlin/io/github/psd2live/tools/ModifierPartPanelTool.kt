package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.scenario.Characters
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.views.ModifierStackPanel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** Real modifier panel and workspace, synthetic mouth artwork; includes narrow panels and translated labels. */
@EnabledIfEnvironmentVariable(named = "PSD2LIVE_TOOLS", matches = "1")
@OptIn(ExperimentalComposeUiApi::class)
class ModifierPartPanelTool {
    @TempDir lateinit var temp: Path

    @Test fun mouthPanelAtNarrowAndWideSizes() = runBlocking {
        val previous = I18n.currentLanguage
        try {
            I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
                DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    workspace.importPsd(Characters.mouthPsd(temp).toString(), true, WorkspaceInitialModifiers.RECOMMENDED)
                    assertEquals(I18n.text("model.parameter.mouthOpen"), vm.state.value.previewModel!!.rig.puppet.parameters.single { it.id.raw == "ParamMouthOpenY" }.name)
                    val stack = RigModifierStack.fromJson(requireNotNull(vm.state.value.rigEdits.modifierStack))
                    val mouth = stack.instances.single { it.type == "mouth" }
                    val outline = ModifierPartEditing.child(mouth, "mouth.outline")
                    val config = ModifierPartConfig.withFlag(ModifierPartEditing.withChildConfig(mouth, outline.type, buildJsonObject {
                        outline.config.forEach { (key, value) -> put(key, value) }
                        put("color", 0x654321); putJsonObject("values") { put("thickness", 3.25f) }
                        putJsonArray("targets") { outline.targets.forEach { target -> addJsonObject { put("kind", target.kind); put("id", target.id) } } }
                    }), "outline", true)
                    // Only the presentation order changes; the algorithm execution order stays intact.
                    val nodes = listOf(ModifierPanelNode(mouth.id)) + stack.instances.filter { it.id != mouth.id }.map { ModifierPanelNode(it.id) }
                    workspace.applyDocumentEdits(workspace.stateToken()!!, "Prepare panel", listOf(
                        WorkspaceDocumentOperation("modifier_update", buildJsonObject { put("id", mouth.id); put("type", mouth.type); put("config", config) }),
                        WorkspaceDocumentOperation("modifier_organize", buildJsonObject { put("nodes", RigModifierOrganization.encode(nodes)) }),
                    ), MutationAuthor.USER)
                    withContext(Dispatchers.Main) {
                        for (language in AppLanguage.entries) for (colors in listOf(ToolColors.Dark, ToolColors.Light))
                            for (width in listOf(420, 760)) for (scale in listOf(1f, 1.5f)) {
                            I18n.setLanguage(language, persist = false)
                            val scene = ImageComposeScene((width * scale).toInt(), (720 * scale).toInt(), density = Density(1f),
                                coroutineContext = currentCoroutineContext()) {
                                val state by vm.state.collectAsState()
                                CompactToolTheme(colors, uiScale = scale, fontScale = 1.1f) {
                                    Box(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                                        ModifierStackPanel(state, vm)
                                    }
                                }
                            }
                            try {
                                var time = 0L
                                suspend fun frames() { repeat(8) { time += 16_000_000; scene.render(time).close(); yield() } }
                                suspend fun click(x: Float, y: Float) {
                                    val point = Offset(x * scale, y * scale)
                                    scene.sendPointerEvent(PointerEventType.Move, point)
                                    scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                                    frames()
                                }
                                frames()
                                click(100f, 88f)
                                val name = "${language.tag}-${if (colors.isDark) "dark" else "light"}-$width-${(scale * 100).toInt()}"
                                val file = File("build/tools/modifier-part-panel/$name.png")
                                file.parentFile.mkdirs()
                                scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                                if (language.tag == "en" && colors.isDark && width == 760 && scale == 1f) {
                                    fun numberPixels(): IntArray = scene.render(time + 16_000_000).use { rendered ->
                                        val bitmap = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(requireNotNull(rendered.encodeToData()).bytes))
                                        bitmap.getRGB(687, 225, 65, 17, null, 0, 65)
                                    }
                                    val initialNumber = numberPixels()
                                    val before = workspace.history()
                                    scene.sendPointerEvent(PointerEventType.Press, Offset(500f, 234f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                                    frames()
                                    repeat(10) { index ->
                                        scene.sendPointerEvent(PointerEventType.Move, Offset(510f + index * 10f, 234f), buttons = PointerButtons(isPrimaryPressed = true))
                                        frames()
                                    }
                                    assertEquals(before.nodes.size, workspace.history().nodes.size, "Drag samples stay local")
                                    scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 234f), button = PointerButton.Primary)
                                    frames()
                                    withTimeout(20_000) { vm.state.first { state ->
                                        val part = RigModifierStack.fromJson(state.rigEdits.modifierStack!!).instances.single { it.id == mouth.id }
                                        ModifierPartEditing.child(part, "mouth.outline").config["values"]!!.jsonObject["thickness"]!!.jsonPrimitive.float > 4f && !state.workspaceEditBusy
                                    } }
                                    assertEquals(before.nodes.size + 1, workspace.history().nodes.size, "One released gesture creates one history node")
                                    // Undo before another rendered frame: the original scalar value may never have changed in composition.
                                    workspace.checkoutHistory(before.headNodeId, MutationAuthor.USER)
                                    frames()
                                    assertContentEquals(initialNumber, numberPixels(), "Undo must replace the released local draft even within one frame")
                                    click(400f, 179f)
                                    scene.render(time + 16_000_000).use { File(file.parentFile, "en-dark-targets.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
                                    click(400f, 179f)
                                    click(400f, 634f)
                                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 600f), scrollDelta = Offset(0f, 8f))
                                    frames()
                                    scene.render(time + 16_000_000).use { File(file.parentFile, "en-dark-physics.png").writeBytes(requireNotNull(it.encodeToData()).bytes) }
                                }
                            } finally { scene.close() }
                        }
                    }
                }
            }
        } finally { I18n.setLanguage(previous, persist = false) }
    }
}
