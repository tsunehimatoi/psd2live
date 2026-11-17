package io.github.psd2live.ui.views

import io.github.psd2live.application.WorkspaceInitialHair
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspaceModelPresetEntry
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.RigSourceModifiers
import io.github.psd2live.ui.state.ModifierConflictAnalysis
import io.github.psd2live.ui.state.ModifierConflictSeverity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModifierPresetMenuModelTest {
    @Test fun `menu controls survive reopening and restart`() {
        val node = java.util.prefs.Preferences.userRoot().node("io.github.psd2live.tests/menu-${java.util.UUID.randomUUID()}")
        try {
            val selection = ModifierPresetSelection(ModifierPresetCategory.LEGACY,
                ModifierSimulationMode.FULL_SIM, ModifierCountTier.MINIMAL, "saved")
            ModifierPresetSelectionStore(node).save(selection)
            assertEquals(selection, ModifierPresetSelectionStore(node).load())
            node.put("selection", "invalid")
            assertEquals(null, ModifierPresetSelectionStore(node).load())
        } finally { node.removeNode(); java.util.prefs.Preferences.userRoot().flush() }
    }

    @Test fun `imported baseline remains selectable as custom`() {
        val imported = WorkspaceModelPresetEntry("imported", "Imported", baseline = "legacy", richness = "minimal")
        val entries = bundled + imported
        assertTrue(ModifierPresetCategory.CUSTOM in ModifierPresetMenuModel.availableCategories(entries))
        assertEquals(imported, ModifierPresetMenuModel.resolvePresetEntry(ModifierPresetCategory.CUSTOM,
            ModifierSimulationMode.NONE, ModifierCountTier.FULL, imported.id, entries))
    }

    private val bundled = listOf(
        WorkspaceModelPresetEntry(id = "modern", name = "Stylized", baseline = "modern"),
        WorkspaceModelPresetEntry(
            id = "modern-dynamic",
            name = "Stylized Full",
            baseline = "modern",
            choices = WorkspaceInitialPreset(frontHair = WorkspaceInitialHair.SIMULATION, backHair = WorkspaceInitialHair.SIMULATION),
        ),
        WorkspaceModelPresetEntry(
            id = "modern-hair",
            name = "Stylized Hair",
            baseline = "modern",
            choices = WorkspaceInitialPreset(clothing = false),
        ),
        WorkspaceModelPresetEntry(
            id = "modern-light",
            name = "Stylized Light",
            baseline = "modern",
            choices = WorkspaceInitialPreset(
                motionBasic = false,
                motionSkeleton = false,
                frontHair = WorkspaceInitialHair.OFF,
                backHair = WorkspaceInitialHair.OFF,
                clothing = false,
                eyeJelly = false,
                mouthOutline = false,
            ),
        ),
        WorkspaceModelPresetEntry(id = "legacy", name = "Classic", baseline = "legacy"),
        WorkspaceModelPresetEntry(
            id = "legacy-dynamic",
            name = "Classic Full",
            baseline = "legacy",
            choices = WorkspaceInitialPreset(frontHair = WorkspaceInitialHair.SIMULATION, backHair = WorkspaceInitialHair.SIMULATION),
        ),
        WorkspaceModelPresetEntry(
            id = "legacy-hair",
            name = "Classic Hair",
            baseline = "legacy",
            choices = WorkspaceInitialPreset(clothing = false),
        ),
        WorkspaceModelPresetEntry(
            id = "legacy-light",
            name = "Classic Light",
            baseline = "legacy",
            choices = WorkspaceInitialPreset(
                motionBasic = false,
                motionSkeleton = false,
                frontHair = WorkspaceInitialHair.OFF,
                backHair = WorkspaceInitialHair.OFF,
                clothing = false,
                eyeJelly = false,
                mouthOutline = false,
            ),
        ),
        WorkspaceModelPresetEntry(id = "empty", name = "Empty", baseline = "empty"),
    )

    @Test
    fun `availableCategories includes custom only when stack presets exist`() {
        assertEquals(
            listOf(ModifierPresetCategory.MODERN, ModifierPresetCategory.LEGACY),
            ModifierPresetMenuModel.availableCategories(bundled),
        )
        val withCustom = bundled + WorkspaceModelPresetEntry(id = "user-1", name = "My Rig", stack = JsonObject(emptyMap()))
        assertEquals(
            listOf(ModifierPresetCategory.MODERN, ModifierPresetCategory.LEGACY, ModifierPresetCategory.CUSTOM),
            ModifierPresetMenuModel.availableCategories(withCustom),
        )
    }

    @Test
    fun `initialSelection detects active preset group from stack`() {
        val emptyStack = RigModifierStack(instances = emptyList())
        val emptySel = ModifierPresetMenuModel.initialSelection(emptyStack, bundled)
        assertEquals(ModifierPresetCategory.MODERN, emptySel.category)
        assertEquals(ModifierCountTier.EMPTY, emptySel.countTier)

        val target = listOf(ModifierTarget("mesh", "mesh-1"))
        val legacyStack = RigModifierStack(
            instances = listOf(RigModifierInstance(id = "m1", type = "body/legacy", version = 1, targets = target, presetGroup = "legacy")),
        )
        val legacySel = ModifierPresetMenuModel.initialSelection(legacyStack, bundled)
        assertEquals(ModifierPresetCategory.LEGACY, legacySel.category)
        assertEquals(ModifierSimulationMode.CLOTHING_SIM, legacySel.simulation)
        assertEquals(ModifierCountTier.STANDARD, legacySel.countTier)

        val fallbackStack = RigModifierStack(
            instances = listOf(RigModifierInstance(id = "m1", type = "swing", version = 1, targets = target, presetGroup = "converted")),
        )
        val fallbackSel = ModifierPresetMenuModel.initialSelection(fallbackStack, bundled)
        assertEquals(ModifierPresetCategory.MODERN, fallbackSel.category)
        assertEquals(ModifierSimulationMode.CLOTHING_SIM, fallbackSel.simulation)
    }

    @Test
    fun `resolvePresetEntry maps category, simulation mode, and empty count tier`() {
        assertEquals(
            "empty",
            ModifierPresetMenuModel.resolvePresetEntry(
                ModifierPresetCategory.MODERN,
                ModifierSimulationMode.CLOTHING_SIM,
                ModifierCountTier.EMPTY,
                null,
                bundled,
            )?.id,
        )
        assertEquals(
            "legacy-hair",
            ModifierPresetMenuModel.resolvePresetEntry(
                ModifierPresetCategory.LEGACY,
                ModifierSimulationMode.CLASSIC_PHYSICS,
                ModifierCountTier.STANDARD,
                null,
                bundled,
            )?.id,
        )
        assertEquals(
            "modern-dynamic",
            ModifierPresetMenuModel.resolvePresetEntry(
                ModifierPresetCategory.MODERN,
                ModifierSimulationMode.FULL_SIM,
                ModifierCountTier.FULL,
                null,
                bundled,
            )?.id,
        )
    }

    @Test
    fun `estimateParameters separates ordinary and physics counts across tiers`() {
        val empty = ModifierPresetMenuModel.estimateParameters(bundled.first { it.id == "empty" }, ModifierCountTier.EMPTY, null)
        assertEquals(0, empty.ordinary)
        assertEquals(0, empty.physics)

        val noPhysicsBase = ModifierPresetMenuModel.estimateParameters(bundled.first { it.id == "modern-light" }, ModifierCountTier.BASE, null)
        assertEquals(15, noPhysicsBase.ordinary)
        assertEquals(0, noPhysicsBase.physics)

        val classicStd = ModifierPresetMenuModel.estimateParameters(bundled.first { it.id == "modern-hair" }, ModifierCountTier.STANDARD, null)
        val fullExt = ModifierPresetMenuModel.estimateParameters(bundled.first { it.id == "modern-dynamic" }, ModifierCountTier.FULL, null)
        assertTrue(classicStd.ordinary > noPhysicsBase.ordinary)
        assertTrue(classicStd.physics > 0)
        assertTrue(fullExt.ordinary >= classicStd.ordinary)
        assertTrue(fullExt.physics >= classicStd.physics)
    }

    @Test
    fun `hair simulation with disabled scaffold hair sway has no conflict`() {
        val target = listOf(ModifierTarget("mesh", "mesh-1"))
        val noSwayScaffold = RigModifierInstance(
            id = "scaffold",
            type = RigSourceModifiers.SCAFFOLD,
            version = 1,
            targets = target,
            config = buildJsonObject {
                put("frontHairSway", JsonPrimitive(false))
                put("backHairSway", JsonPrimitive(false))
            },
        )
        val hairPart = RigModifierInstance(id = "hair", type = RigSourceModifiers.HAIR, version = 1, targets = target)
        val frontSim = RigModifierInstance(
            id = "sim-front",
            type = "simulation",
            version = 1,
            targets = target,
            config = buildJsonObject { put("id", JsonPrimitive("preset_front_hair")) },
        )
        val noConflictStack = RigModifierStack(instances = listOf(noSwayScaffold, hairPart, frontSim))
        val noConflicts = ModifierConflictAnalysis.stackConflicts(noConflictStack, emptyMap())
        assertEquals(ModifierConflictSeverity.NONE, noConflicts["hair"]?.severity)
        assertEquals(ModifierConflictSeverity.NONE, noConflicts["sim-front"]?.severity)

        val swayScaffold = noSwayScaffold.copy(
            config = buildJsonObject {
                put("frontHairSway", JsonPrimitive(true))
                put("backHairSway", JsonPrimitive(false))
            },
        )
        val conflictStack = RigModifierStack(instances = listOf(swayScaffold, hairPart, frontSim))
        val conflicts = ModifierConflictAnalysis.stackConflicts(conflictStack, emptyMap())
        assertEquals(ModifierConflictSeverity.SOFT, conflicts["hair"]?.severity)
        assertEquals(ModifierConflictSeverity.SOFT, conflicts["sim-front"]?.severity)
    }

    @Test
    fun `count tiers expose five ordered levels`() {
        assertEquals(
            listOf("empty", "base", "minimal", "standard", "full"),
            ModifierCountTier.entries.map { it.id },
        )
    }
}
