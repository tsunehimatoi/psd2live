package example

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Compiles and evaluates without the application, editor, persistence or engine on the classpath. */
class AlgorithmApiTest {
    @Test fun versionRangesUseNumericAndPrereleasePrecedence() {
        val range = ModifierPluginVersionRange(minimum = "1.2.0", maximum = "2.0.0")
        assertTrue(range.accepts("1.10.0"))
        assertFalse(range.accepts("1.2.0-rc.1"))
        assertFalse(range.accepts("2.0.0"))
        assertTrue(ModifierPluginVersionRange(exact = "1.2.3").accepts("1.2.3+build"))
        assertFailsWith<IllegalArgumentException> { ModifierPluginVersion.parse("1.02.0") }
    }

    @Test fun independentImplementationUsesStableIdsAndPreservesInput() {
        val algorithm = ModifierAlgorithmPlugin { input, instance, checkpoint ->
            checkpoint()
            input.copy(parameters = input.parameters + Parameter(instance.outputId("gain"), "Gain", 0f, 1f, 0f))
        }
        val instance = RigModifierInstance("example1", "example.gain", 1, listOf(ModifierTarget("mesh", "mesh1")))
        val input = RigIR(Canvas(64f, 64f), emptyList())
        var checkpoints = 0
        val result = algorithm.evaluate(input, instance) { checkpoints++ }
        assertEquals(1, checkpoints)
        assertTrue(input.parameters.isEmpty())
        assertEquals("modifier:example1:6761696e", result.parameters.single().id)
        assertEquals("gain", instance.localOutputName(result.parameters.single().id))
        assertEquals(instance, Json.decodeFromString<RigModifierInstance>(Json.encodeToString(instance)))
    }

    @Test fun legacyRecordsAndPinnedPackageReferencesKeepTheirWireFormat() {
        val wire = """{"id":"m1","type":"swing","version":1,"targets":[{"kind":"mesh","id":"mesh1"}]}"""
        val legacy = Json.decodeFromString<RigModifierInstance>(wire)
        assertNull(legacy.plugin)
        assertEquals(wire, Json.encodeToString(legacy))
        val pinned = legacy.copy(plugin = ModifierPluginReference("example", "1.2.3", "content",
            mapOf("dependency" to "locked")), outputAliases = mapOf("gain" to "authored-id"))
        val restored = Json.decodeFromString<RigModifierInstance>(Json.encodeToString(pinned))
        assertEquals(pinned, restored)
        assertEquals("authored-id", restored.outputId("gain"))
    }
}
