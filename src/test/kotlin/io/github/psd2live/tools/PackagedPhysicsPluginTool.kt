package io.github.psd2live.tools

import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.format.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Opt-in real installation and process evaluation of the independently built physics package. */
class PackagedPhysicsPluginTool {
    @Test fun installEvaluateReinstall() {
        requireTools()
        val root = Files.createTempDirectory("packaged-physics-")
        val preferences = Preferences.userRoot().node("plugin-package-test/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root, preferences)
        try {
            val source = Path.of("algorithms/physics/build/plugin").toAbsolutePath()
            val entry = store.install(source.toString())
            val config = RigPhysicsEdit("draft", "Pendulum", listOf(PhysicsInput("input")), listOf(PhysicsOutput("output"))).toJson()
            val instance = RigModifierInstance("physics1", "psd2live.physics/pendulum", 1, listOf(ModifierTarget("mesh", "body")),
                config = config, outputAliases = mapOf("physics" to "retained-physics"), plugin = entry.reference)
            val input = RigIR(Canvas(64f, 64f), listOf(Parameter("input", "Input", -1f, 1f, 0f),
                Parameter("output", "Output", -1f, 1f, 0f)), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
            val expected = PhysicsAlgorithmPlugin.definition.plugin.evaluate(input, instance) {}
            repeat(2) {
                assertEquals(expected, ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance, {}, store))
            }
            store.uninstall(entry.reference)
            assertFailsWith<IllegalArgumentException> { store.requireAvailable(entry.reference) }
            val reinstalled = store.install(source.toString())
            assertEquals(entry.reference, reinstalled.reference)
            assertEquals(expected, ModifierPluginWorkers.evaluate(reinstalled, reinstalled.manifest.algorithms.single(), input, instance, {}, store))
            store.uninstall(reinstalled.reference)
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
