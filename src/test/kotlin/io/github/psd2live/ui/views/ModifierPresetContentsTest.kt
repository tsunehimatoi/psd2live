package io.github.psd2live.ui.views

import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModifierPresetContentsTest {
    @Test
    fun groupsCapturedPackagesWithoutLosingDisabledOrMissingInstances() {
        val packageRef = ModifierPluginReference("test.package", "1.0.0", "1".repeat(64))
        val otherArtifact = packageRef.copy(digest = "2".repeat(64))
        val target = listOf(ModifierTarget("mesh", "mesh"))
        val first = RigModifierInstance("m1", "test.first", 1, target, plugin = packageRef)
        val disabled = first.copy(id = "m2", type = "test.second", enabled = false)
        val missing = first.copy(id = "m3", plugin = otherArtifact)
        val builtin = RigModifierInstance("m4", "swing", 1, target)
        val unresolved = first.copy(id = "m5", plugin = null)
        val stack = RigModifierStack(instances = listOf(first, disabled, missing, builtin, unresolved))
        val statuses = mapOf(first.id to ModifierStatus.ACTIVE, disabled.id to ModifierStatus.DISABLED,
            missing.id to ModifierStatus.MISSING_PLUGIN)

        val groups = groupPresetModifiers(stack, statuses)
        val builtinReference = assertNotNull(BuiltinModifierPlugins.reference(builtin.type, builtin.version))
        assertEquals(listOf(packageRef, otherArtifact, builtinReference, null), groups.keys.toList())
        assertEquals(listOf(PresetModifierRow(first, ModifierStatus.ACTIVE), PresetModifierRow(disabled, ModifierStatus.DISABLED)),
            groups.getValue(packageRef))
        assertEquals(listOf(PresetModifierRow(missing, ModifierStatus.MISSING_PLUGIN)), groups.getValue(otherArtifact))
        assertEquals(listOf(builtin), groups.getValue(builtinReference).map { it.instance })
        assertEquals(listOf(unresolved), groups.getValue(null).map { it.instance })
        assertTrue(groupPresetModifiers(RigModifierStack(), emptyMap()).isEmpty())
    }
}
