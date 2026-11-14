package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceModifierSchemas
import io.github.psd2live.application.validateOperationSchema
import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierPartEditingTest {
    private fun part() = RigModifierInstance("mouth.part", "mouth", 1, listOf(ModifierTarget("mesh", "mouth")), config = buildJsonObject {
        put("scaffold", "structure"); put("outline", true); put("vowel", false)
        putJsonObject("subConfigs") {
            putJsonObject("mouth.outline") {
                put("id", "old.outline"); put("version", 1)
                putJsonArray("targets") { addJsonObject { put("kind", "mesh"); put("id", "lip") } }
                putJsonObject("outputAliases") { put("mesh:lip", "legacy.lip") }
                put("color", 0x112233); putJsonObject("values") { put("thickness", 2.5f) }
            }
            putJsonObject("mouth.vowel") { put("id", "vowel.original"); put("version", 1) }
        }
    })

    @Test fun nestedEditsPreserveMetadataSiblingConfigurationsAndParentFlags() {
        val part = part()
        val child = ModifierPartEditing.child(part, "mouth.outline")
        assertEquals("old.outline", child.id)
        assertEquals(listOf(ModifierTarget("mesh", "lip")), child.targets)
        val config = ModifierPartEditing.withChildConfig(part, child.type, buildJsonObject { putJsonObject("values") { put("thickness", 4f) } })
        val after = part.copy(config = config)
        val changed = ModifierPartEditing.child(after, child.type)
        assertEquals(child.id, changed.id)
        assertEquals(child.version, changed.version)
        assertEquals(child.targets, changed.targets)
        assertEquals(child.outputAliases, changed.outputAliases)
        assertNull(changed.config["color"], "Auto color must be able to remove an explicit color")
        assertEquals(part.config["subConfigs"]!!.jsonObject["mouth.vowel"], config["subConfigs"]!!.jsonObject["mouth.vowel"])
        assertEquals(part.config - "subConfigs", config - "subConfigs")
        validateOperationSchema(config, WorkspaceModifierSchemas.configuration("mouth"))
    }

    @Test fun everySharedFeatureHasTheBindingDescriptorUsedByTheEditor() {
        for ((type, features) in ModifierPartConfig.features) {
            val part = RigModifierInstance("part", type, 1, listOf(ModifierTarget("mesh", "target")))
            for ((flag, feature) in features) {
                assertNotNull(RigLegacyBindings.find(feature), feature)
                val child = ModifierPartEditing.child(part, feature)
                assertEquals("part:$flag", child.id)
                assertEquals(part.targets, child.targets)
            }
        }
        assertTrue("mouth.outline" in ModifierPartConfig.features.getValue("mouth").values)
    }

    @Test fun baseDescriptorsFollowTheSelectedMode() {
        val part = part()
        assertEquals("mouth/fitted", ModifierPartEditing.baseType(part))
        assertEquals("mouth/legacy", ModifierPartEditing.baseType(part.copy(config = JsonObject(part.config + ("mode" to JsonPrimitive("legacy"))))))
    }
}
