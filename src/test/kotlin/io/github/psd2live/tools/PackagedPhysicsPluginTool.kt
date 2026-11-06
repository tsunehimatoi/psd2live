package io.github.psd2live.tools

import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.MotionData
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.format.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*
import kotlinx.serialization.json.*

/** Opt-in real installation and process evaluation of independently built bundled packages. */
class PackagedPhysicsPluginTool {
    @Test fun installEvaluateReinstall() = verifyPackage("physics", PhysicsAlgorithmPlugin.definition,
        RigPhysicsEdit("draft", "Pendulum", listOf(PhysicsInput("input")), listOf(PhysicsOutput("output"))).toJson())

    @Test fun motionArchiveReplaysWithoutApplicationClasses() = verifyPackage("motion", MotionAlgorithmPlugin.definition,
        MotionData.toJson(MotionClip("draft", "Turn", curves = listOf(MotionCurve("input", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))),
            parameterRoles = mapOf("LipSync" to listOf("input")))))

    @Test fun swingArchiveReplaysWithoutApplicationClasses() = verifyPackage("swing", SwingAlgorithmPlugin.definition,
        io.github.psd2live.core.RigSwingEdit.single("draft", "Swing", io.github.psd2live.core.SwingKind.LATERAL,
            listOf("body"), listOf("angle")).toJson())

    private fun verifyPackage(type: String, definition: RigModifierDefinition, config: JsonObject) {
        requireTools()
        val root = Files.createTempDirectory("packaged-algorithm-")
        val preferences = Preferences.userRoot().node("plugin-package-test/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val built = Path.of("algorithms/$type/build/plugin").toAbsolutePath()
            val source = Files.createDirectory(root.resolve("package"))
            java.util.jar.JarFile(built.resolve("algorithm.jar").toFile()).use { jar ->
                assertFalse(jar.entries().asSequence().any { entry -> (type != "swing" && entry.name.startsWith("org/umamo/")) ||
                    listOf("application", "ui", "project", "history").any { entry.name.startsWith("io/github/psd2live/$it/") } })
            }
            Files.copy(built.resolve("algorithm.jar"), source.resolve("algorithm.jar"))
            val manifest = Json.parseToJsonElement(Files.readString(built.resolve("plugin.json"))).jsonObject
            Files.writeString(source.resolve("plugin.json"), JsonObject(manifest + ("version" to JsonPrimitive("0.9.0"))).toString())
            val entry = store.install(source.toString())
            val instance = RigModifierInstance("instance1", type, 1, listOf(ModifierTarget("mesh", "body")),
                config = config, outputAliases = mapOf(type to "retained-output"), plugin = entry.reference)
            val input = RigIR(Canvas(64f, 64f), listOf(Parameter("input", "Input", -1f, 1f, 0f),
                Parameter("output", "Output", -1f, 1f, 0f), Parameter("ParamAngleX", "Head X", -30f, 30f, 0f)),
                meshes = listOf(Mesh("body", "Body", null, geometry = MeshGeometry(
                    Floats.values(0f, 0f, 10f, 0f, 0f, 30f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)))
            if (type == "swing") {
                val model = io.github.psd2live.targets.cubism.PuppetIr.toPuppet(input)
                val wrapped = io.github.psd2live.core.SwingWrapping.wrap(model, io.github.psd2live.core.RigSwingEdit.fromJson(config))
                val replayed = wrapped.commands.fold(model) { current, command -> io.github.psd2live.core.RigAuthoringJournal.apply(current, command) }
                assertEquals(io.github.psd2live.targets.cubism.PuppetIr.toIr(replayed), io.github.psd2live.targets.cubism.PuppetIr.toIr(wrapped.model))
            }
            val expected = definition.plugin.evaluate(input, instance) {}
            val registry = RigModifierRegistry(listOf(definition) + ModifierPluginCatalog.definitions(store))
            assertEquals(entry.reference, registry.definition(instance).pluginReference)
            assertSame(definition, registry.definition(instance.copy(plugin = BuiltinModifierPlugins.reference(type, 1))))
            repeat(2) {
                assertEquals(expected, registry.definition(instance).plugin.evaluate(input, instance) {})
            }
            BundledAlgorithmArtifacts.retain(store)
            assertTrue(store.snapshot().any { it.reference == instance.plugin })
            assertTrue(store.snapshot().any { it.reference == BuiltinModifierPlugins.reference(type, 1) })
            ModifierPluginWorkers.stop(entry.reference)
            val reopened = ModifierPluginStore(store.root, preferences)
            val restored = RigModifierStack.fromJson(RigModifierStack(instances = listOf(instance)).toJson()).instances.single()
            val restoredRegistry = RigModifierRegistry(listOf(definition) + ModifierPluginCatalog.definitions(reopened))
            assertEquals(expected, restoredRegistry.definition(restored).plugin.evaluate(input, restored) {})
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
