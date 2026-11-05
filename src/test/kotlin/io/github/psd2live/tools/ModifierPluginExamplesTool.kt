package io.github.psd2live.tools

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Builds are separate; this tool installs and evaluates the six actual distributable packages. */
class ModifierPluginExamplesTool {
    @Test fun evaluatePackagedAlgorithms() {
        requireTools()
        val root = Files.createTempDirectory("plugin-examples-")
        val preferences = Preferences.userRoot().node("psd2live-test/plugins/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root, preferences)
        val report = StringBuilder()
        val rig = RigIR(Canvas(10f, 10f), emptyList(), meshes = listOf(Mesh("mesh", "Mesh", null, geometry = null, offsets = null)))
        try {
            for (language in listOf("python", "kotlin", "rust", "c", "cpp", "csharp")) {
                val entry = store.install(Path.of("build/plugins/$language.zip").toAbsolutePath().toString())
                val algorithm = entry.manifest.algorithms.single()
                val instance = RigModifierInstance("m1", algorithm.type, algorithm.version, listOf(ModifierTarget("mesh", "mesh")), config = algorithm.defaults, plugin = entry.reference)
                try {
                    val start = System.nanoTime()
                    var current = rig
                    repeat(2) { current = ModifierPluginWorkers.evaluate(entry, algorithm, current, instance, {}, store) }
                    assertEquals(.25f, current.meshes.single().opacity)
                    report.appendLine("$language: install, two evaluations, composable output passed; ${(System.nanoTime() - start) / 1_000_000} ms")
                } finally { ModifierPluginWorkers.stop(entry.reference) }
                store.uninstall(entry.reference)
            }
        } finally {
            output("plugin-examples").resolve("report.txt").writeText(report.toString())
            preferences.removeNode(); ModifierPluginStore.deleteTree(root)
        }
    }
}
