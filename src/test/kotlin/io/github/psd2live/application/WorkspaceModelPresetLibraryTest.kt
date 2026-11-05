package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class WorkspaceModelPresetLibraryTest {
    @TempDir lateinit var temp: Path

    @Test fun defaultsRestoreFromJsonAndUserPresetsRoundTrip() {
        val node = Preferences.userRoot().node("io.github.psd2live.tests/presets-${UUID.randomUUID()}")
        try {
            val library = WorkspaceModelPresetLibrary(node)
            assertTrue(library.snapshot().size >= 6)
            val exported = library.exportJson("modern")
            library.delete("modern")
            assertEquals(exported, library.exportJson("modern"))
            val imported = library.importJson(exported)
            assertNotEquals("modern", imported.id)
            assertEquals("modern", imported.baseline)
            assertNull(imported.nameKey)
            assertEquals(imported, WorkspaceModelPresetLibrary(node).get(imported.id))
            library.delete(imported.id)
            assertFalse(library.snapshot().any { it.id == imported.id })
            val before = library.snapshot()
            assertFails { library.importJson("{}") }
            assertEquals(before, library.snapshot())
        } finally { node.removeNode(); Preferences.userRoot().flush() }
    }

    @Test fun templateRebindsGeneratedReferencesWithoutCascadingAcrossRepeatedSwitches(): Unit = runBlocking {
        val source = SyntheticProject(temp.resolve("source"), 1, 12, 1).tree.head().snapshot
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8,
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = source.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val model = WorkspacePreviewBuilder().build(document)
        val target = ModifierTarget("mesh", model.rig.puppet.drawables.single().id.raw)
        var template = RigModifierStack().add("swing", 1, listOf(target),
            RigSwingEdit.single("sway", "Sway", SwingKind.LATERAL, listOf(target.id), listOf("mode")).toJson(), id = "m1")
        val output = template.instances.single().outputId("swing:lateral:0")
        template = template.add("motion", 1, listOf(target), MotionClips.toJson(MotionClip("clip", "Motion", duration = 1f,
            curves = listOf(MotionCurve(output, listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))))), id = "m2")
        // A source binding captures its scaffold by instance ID, not by output object ID.
        val scaffold = RigModifierStack().add(RigSourceModifiers.SCAFFOLD, 1, listOf(target), id = "m1")
        val bindings = scaffold.add("body/legacy", 1, listOf(target), buildJsonObject { put("scaffold", "m1") }, id = "m2")
        val rebound = WorkspacePresetSwitch.instantiate(WorkspaceModelPresetEntry("binding", "Binding", stack = bindings.toJson()),
            RigModifierStack(nextId = 2, retiredIds = setOf("m1")), model)
        assertEquals(rebound.instances.first().id, rebound.instances.last().config.getValue("scaffold").jsonPrimitive.content)
        val preset = WorkspaceModelPresetEntry("saved", "Saved", stack = template.toJson())
        val cleared = RigModifierStack(nextId = 2, retiredIds = setOf("m1"))
        val first = WorkspacePresetSwitch.instantiate(preset, cleared, model)
        assertEquals("m2", first.instances.first().id)
        assertEquals("m3", first.instances.last().id)
        assertEquals(first.instances.first().outputId("swing:lateral:0"),
            MotionClips.fromJson(first.instances.last().config).curves.single().parameterId)
        val removed = first.instances.fold(first) { stack, entry -> stack.remove(entry.id) }
        val second = WorkspacePresetSwitch.instantiate(preset, removed, model)
        assertTrue(second.instances.none { fresh -> first.instances.any { it.id == fresh.id } })
        assertEquals(second.instances.first().outputId("swing:lateral:0"),
            MotionClips.fromJson(second.instances.last().config).curves.single().parameterId)
    }
}
