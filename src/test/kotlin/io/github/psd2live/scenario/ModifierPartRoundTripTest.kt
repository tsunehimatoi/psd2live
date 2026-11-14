package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ModifierPartRoundTripTest {
    @TempDir lateinit var temp: Path

    @Test fun editorNestedConfigurationCommitsOnceAndSurvivesUndoRedoAndReopen() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.mouthPsd(temp).toString(), true, WorkspaceInitialModifiers.RECOMMENDED)
                fun mouth() = RigModifierStack.fromJson(requireNotNull(vm.state.value.rigEdits.modifierStack)).instances.single { it.type == "mouth" }
                val original = mouth()
                val child = ModifierPartEditing.child(original, "mouth.outline")
                val stored = buildJsonObject {
                    child.config.forEach { (key, value) -> put(key, value) }
                    put("id", child.id); put("version", child.version)
                    putJsonArray("targets") { child.targets.forEach { target -> addJsonObject { put("kind", target.kind); put("id", target.id) } } }
                    putJsonObject("outputAliases") { put("retained", "legacy.alias") }
                    putJsonObject("values") { put("thickness", 3.25f) }; put("color", 0x654321)
                }
                val withSibling = original.copy(config = JsonObject(original.config + ("subConfigs" to JsonObject(
                    original.config["subConfigs"]?.jsonObject.orEmpty() + ("mouth.vowel" to buildJsonObject { put("id", "saved.vowel"); put("version", 1) })))))
                val spring = ModifierPartEditing.child(withSibling, "mouth.spring")
                val slot = requireNotNull(RigLegacyBindings.find(spring.type)?.module).physicsGroups.first()
                val group = requireNotNull(vm.state.value.previewModel).rig.modifierOutput!!.physics.groups.single { it.id == spring.outputId("physics:$slot") }
                val physics = Physics3Json.setting(group).let { it.copy(segments = it.segments.map { segment -> segment.copy(mobility = .61f) }) }.toJson()
                val springConfig = JsonObject(spring.config + ("physics" to JsonObject(spring.config["physics"]?.jsonObject.orEmpty() + (slot to physics))))
                val withPhysics = withSibling.copy(config = ModifierPartEditing.withChildConfig(withSibling, spring.type, springConfig))
                val updated = ModifierPartConfig.withFlag(ModifierPartEditing.withChildConfig(withPhysics, child.type, stored), "outline", true)
                val before = workspace.history()
                val completed = CompletableDeferred<String?>()
                vm.editModifier("modifier_update", buildJsonObject { put("id", original.id); put("type", original.type); put("config", updated) },
                    vm.modifierEditState()) { completed.complete(it) }
                assertNull(withTimeout(20_000) { completed.await() })
                assertEquals(before.nodes.size + 1, workspace.history().nodes.size)
                val head = workspace.history().headNodeId
                assertEquals(updated, mouth().config)
                assertTrue(vm.state.value.previewModel!!.rig.modifierOutput!!.physics.groups.single { it.id == group.id }.segments.all { it.mobility == .61f })
                workspace.checkoutHistory(before.headNodeId, MutationAuthor.USER)
                assertEquals(original.config, mouth().config)
                workspace.checkoutHistory(head, MutationAuthor.USER)
                assertEquals(updated, mouth().config)
                val path = temp.resolve("part.psd2live")
                vm.saveProjectNow(path)
                vm.openProjectNow(path, discardUnsaved = true)
                assertEquals(updated, mouth().config)
            }
        }
    }
}
