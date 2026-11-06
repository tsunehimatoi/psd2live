package example

import io.github.psd2live.core.modifier.*
import kotlin.test.*

class DerivedOutputTest {
    @Test fun scopesDerivedNamesToTheExactInstanceKindAndCanonicalSuffix() {
        val instance = RigModifierInstance("one", "swing", 1, listOf(ModifierTarget("mesh", "body")),
            outputAliases = mapOf("swing" to "kept"))
        val rule = ModifierPluginDerivedOutput("deformer", "swing", "Warp_Swing_", numbered = true)
        assertTrue(rule.matches(instance, "deformer", "Warp_Swing_kept"))
        assertTrue(rule.matches(instance, "deformer", "Warp_Swing_kept_10"))
        for (id in listOf("Warp_Swing_other", "Warp_Swing_kept_1", "Warp_Swing_kept_02", "Warp_Swing_kept_10001", "Warp_Swing_kept_extra"))
            assertFalse(rule.matches(instance, "deformer", id))
        assertFalse(rule.matches(instance, "physics", "Warp_Swing_kept"))
        assertFalse(rule.matches(instance.copy(outputAliases = emptyMap()), "deformer", "Warp_Swing_kept"))
    }
}
