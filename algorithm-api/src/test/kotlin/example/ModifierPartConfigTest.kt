package example

import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierPartConfigTest {
    @Test fun unsupportedPersistedChildVersionsAreRejectedBeforeAdaptation() {
        val part = RigModifierInstance("eye", "eye", 1, listOf(ModifierTarget("mesh", "target")), config = buildJsonObject {
            put("scaffold", "structure")
            putJsonObject("subConfigs") { putJsonObject("eye.wide") { put("version", 99) } }
        })
        assertFailsWith<IllegalArgumentException> { ModifierPartConfig.child(part, "eye.wide", version = 2) }
    }

    @Test fun legacyLidReadsWithoutMutatingAndCanonicalFalseWins() {
        val old = buildJsonObject { put("lid", true); putJsonObject("subConfigs") { put("kept", 7) } }
        assertTrue(ModifierPartConfig.enabled(old, ModifierPartConfig.LID_COUPLING))
        val updated = ModifierPartConfig.withFlag(old, ModifierPartConfig.LID_COUPLING, false)
        assertFalse(ModifierPartConfig.enabled(updated, ModifierPartConfig.LID_COUPLING))
        assertFalse("lid" in updated)
        assertEquals(old["subConfigs"], updated["subConfigs"])
        assertTrue("lid" in old)
        assertFalse(ModifierPartConfig.enabled(JsonObject(old + ("lidCoupling" to JsonPrimitive(false))), "lidCoupling"))
        assertEquals(updated, Json.parseToJsonElement(updated.toString()))
    }
}
