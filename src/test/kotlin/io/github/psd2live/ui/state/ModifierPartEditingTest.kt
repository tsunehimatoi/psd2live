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
        assertTrue(ModifierPartEditing.hasOwnTargets(part, child.type))
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
            val part = RigModifierInstance("part", type, 1, listOf(ModifierTarget("mesh", "target")),
                config = buildJsonObject { put("scaffold", "structure"); put("physicsEnabled", false) },
                outputAliases = mapOf("physics:shared" to "parent.physics"))
            for ((flag, feature) in features) {
                assertNotNull(RigLegacyBindings.find(feature), feature)
                val child = ModifierPartEditing.child(part, feature)
                assertEquals(ModifierPartConfig.child(part, feature), child)
                assertEquals("part:${if (type == "mouth") flag else feature}", child.id)
                assertEquals(1, child.version)
                assertTrue(child.outputAliases.isEmpty(), "Parent aliases must not leak into $feature")
                assertEquals("structure", child.config.getValue("scaffold").jsonPrimitive.content)
                assertEquals(false, child.config.getValue("physicsEnabled").jsonPrimitive.boolean)
                assertFalse(ModifierPartEditing.hasOwnTargets(part, feature))
                for (slot in RigLegacyBindings.find(feature)!!.module?.physicsGroups.orEmpty()) {
                    assertEquals(ModifierPartConfig.child(part, feature, version = 2).outputId("physics:$slot"), child.outputId("physics:$slot"))
                }
                assertEquals(part.targets, child.targets)
            }
        }
        assertTrue("mouth.outline" in ModifierPartConfig.features.getValue("mouth").values)
    }

    @Test fun editingEffectiveValuesDoesNotFreezeInheritedConfiguration() {
        val part = part().copy(config = buildJsonObject {
            put("scaffold", "structure"); put("color", 0x112233); put("outline", true)
        })
        val child = ModifierPartEditing.child(part, "mouth.outline")
        assertEquals(part.config, ModifierPartEditing.withChildConfig(part, child.type, child.config))
        val edited = JsonObject(child.config + ("values" to buildJsonObject { put("thickness", 4f) }))
        val config = ModifierPartEditing.withChildConfig(part, child.type, edited)
        assertEquals(buildJsonObject { putJsonObject("values") { put("thickness", 4f) } }, config["subConfigs"]!!.jsonObject[child.type])
        val changedParent = part.copy(config = JsonObject(config + ("color" to JsonPrimitive(0x445566))))
        assertEquals(0x445566, ModifierPartEditing.child(changedParent, child.type).config.getValue("color").jsonPrimitive.int)
        validateOperationSchema(config, WorkspaceModifierSchemas.configuration(part.type))
    }

    @Test fun unchangedLocalDependenciesRemainLocalAndPhysicsOverridesStayEditable() {
        val part = RigModifierInstance("brow", "brow", 1, listOf(ModifierTarget("mesh", "brow")), config = buildJsonObject {
            put("scaffold", "structure"); put("physicsEnabled", false)
            putJsonObject("dependencies") { put("eye/fitted", "eye") }
            putJsonObject("subConfigs") {
                putJsonObject("brow.lid-coupling") { put("id", "stored.lid"); put("version", 1); putJsonObject("dependencies") {} }
                putJsonObject("brow.spring") { put("id", "stored.spring"); put("version", 1); putJsonObject("outputAliases") { put("physics:brow", "own.physics") } }
            }
        }, outputAliases = mapOf("physics:brow" to "parent.physics"))
        val lid = ModifierPartEditing.child(part, "brow.lid-coupling")
        val unchanged = ModifierPartEditing.withChildConfig(part, lid.type, lid.config)
        assertEquals(part.config, unchanged, "Reading effective dependencies must not persist inherited entries")
        val spring = ModifierPartEditing.child(part, "brow.spring")
        assertEquals("own.physics", spring.outputId("physics:brow"))
        val updated = ModifierPartEditing.withChildConfig(part, spring.type, JsonObject(spring.config + ("physicsEnabled" to JsonPrimitive(true))))
        val saved = updated["subConfigs"]!!.jsonObject[spring.type]!!.jsonObject
        assertEquals(JsonPrimitive(true), saved["physicsEnabled"])
        assertFalse("scaffold" in saved || "base" in saved)
        assertEquals(part.config["subConfigs"]!!.jsonObject[lid.type], updated["subConfigs"]!!.jsonObject[lid.type])
        assertEquals(spring.outputAliases, ModifierPartEditing.child(part.copy(config = updated), spring.type).outputAliases)
    }

    @Test fun unsupportedStoredFacadeVersionsAreRejectedByTheUiAdapter() {
        val part = part().copy(config = buildJsonObject {
            put("scaffold", "structure")
            putJsonObject("subConfigs") { putJsonObject("mouth.outline") { put("version", 99) } }
        })
        assertFailsWith<IllegalArgumentException> { ModifierPartEditing.child(part, "mouth.outline") }
    }

    @Test fun baseDescriptorsFollowTheSelectedMode() {
        val part = part()
        assertEquals("mouth/fitted", ModifierPartEditing.baseType(part))
        assertEquals("mouth/legacy", ModifierPartEditing.baseType(part.copy(config = JsonObject(part.config + ("mode" to JsonPrimitive("legacy"))))))
    }
}
