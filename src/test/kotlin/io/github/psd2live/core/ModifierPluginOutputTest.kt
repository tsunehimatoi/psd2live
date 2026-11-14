package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import kotlin.test.*

class ModifierPluginOutputTest {
    @Test fun dynamicSharedParametersRequireExactHostApproval() {
        val rig = RigIR(Canvas(1f, 1f), emptyList())
        val parameter = Parameter("Expression", "Expression", 0f, 1f, 0f)
        val output = rig.copy(parameters = listOf(parameter))
        val instance = RigModifierInstance("m1", "test/expression", 1, listOf(ModifierTarget("mesh", "body")))
        val algorithm = ModifierPluginAlgorithm(instance.type, name = "Expression", writes = setOf("parameter"),
            configSchema = JsonObject(emptyMap()), defaults = JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> { ModifierPluginOutput.validate(rig, output, instance, algorithm) }
        assertEquals(output, ModifierPluginOutput.validate(rig, output, instance, algorithm, listOf(parameter)))
        assertFailsWith<IllegalArgumentException> {
            ModifierPluginOutput.validate(rig, output.copy(parameters = listOf(parameter.copy(max = 2f))), instance, algorithm, listOf(parameter))
        }
        assertFailsWith<IllegalArgumentException> {
            ModifierPluginOutput.validate(rig, output.copy(parameters = listOf(parameter, parameter.copy(id = "Unexpected"))), instance, algorithm, listOf(parameter))
        }
    }

    @Test fun vertexWeightsUseAuthoringDataAndCannotEscapeDeclaredWrites() {
        val rig = RigIR(Canvas(100f, 100f), emptyList(), meshes = listOf(Mesh("cloth", "Cloth", null,
            geometry = MeshGeometry(Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)))
        val instance = RigModifierInstance("weights", "test/weights", 1, listOf(ModifierTarget("mesh", "cloth")))
        val algorithm = ModifierPluginAlgorithm(instance.type, name = "Weights", writes = setOf("*"), configSchema = JsonObject(emptyMap()), defaults = JsonObject(emptyMap()))
        val commands = Json.parseToJsonElement("""[{"op":"vertex_group_put","target":"mesh:cloth","name":"Pins","kind":"pin","weights":[0,0.5,1]}]""").jsonArray
        val result = ModifierPluginOutput.apply(rig, instance, algorithm, commands) {}
        assertContentEquals(floatArrayOf(0f, .5f, 1f), PuppetIr.toPuppet(result).vertexGroups.single().weights)
        assertTrue(ModifierPluginOutput.sidecars(rig, result).any { it.kind == "authoring" })
        assertFails { ModifierPluginOutput.apply(rig, instance, algorithm.copy(writes = setOf("mesh")), commands) {} }
        assertTrue(PuppetIr.toPuppet(rig).vertexGroups.isEmpty())
        val malformed = Json.parseToJsonElement("""[{"op":"vertex_group_put","target":"mesh:cloth","name":"Pins","kind":"pin","weights":[0]}]""").jsonArray
        assertFails { ModifierPluginOutput.apply(rig, instance, algorithm, malformed) {} }
    }
}
