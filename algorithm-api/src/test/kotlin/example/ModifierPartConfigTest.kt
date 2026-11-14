package example

import io.github.psd2live.core.modifier.ModifierPartConfig
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierPartConfigTest {
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
