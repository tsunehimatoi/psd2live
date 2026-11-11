package io.github.psd2live.application

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class WorkspaceBuiltinPluginsTest {
    @TempDir lateinit var directory: Path

    @Test fun bundledEntriesAreIndividuallyManagedAndKeepLegacyGroupCompatibility() {
        val preferences = Preferences.userRoot().node("psd2live-test/plugins/${UUID.randomUUID()}")
        try {
            val store = ModifierPluginStore(directory, preferences)
            val entries = BuiltinModifierPlugins.entries
            assertEquals(entries.size, entries.map { it.reference.id }.distinct().size)
            val selected = entries.first { it.definition.type == "motion" }
            val identity = buildJsonObject { put("id", selected.reference.id); put("digest", selected.reference.digest) }
            val result = WorkspacePluginManagement.execute("plugin_disable", identity, store)
            validateOperationSchema(result, WorkspacePluginSchemas.result)
            assertFalse(store.builtinEnabled(selected.reference.id))
            assertTrue(entries.filter { it != selected }.all { store.builtinEnabled(it.reference.id) })
            assertFalse(ModifierPluginStore(directory, preferences).builtinEnabled(selected.reference.id))
            store.setBuiltinsEnabled(false)
            WorkspacePluginManagement.execute("plugin_enable", identity, store)
            assertTrue(store.builtinEnabled(selected.reference.id))
            assertTrue(entries.filter { it != selected }.none { store.builtinEnabled(it.reference.id) })
            assertFailsWith<IllegalArgumentException> { WorkspacePluginManagement.execute("plugin_uninstall", identity, store) }
            store.setBuiltinsEnabled(true)
            assertTrue(entries.all { store.builtinEnabled(it.reference.id) })
        } finally { preferences.removeNode() }
    }

    @Test fun newInstancesPinEntrypointsWhileLegacyReadsAndOutputIdsStayUnchanged() {
        val targets = listOf(ModifierTarget("mesh", "body"))
        val registry = RigModifierAlgorithms.registry
        assertEquals(
            ModifierPluginReference("builtin.626f64792f6c6567616379.v1", "1.0.0",
                "21e4a61f2450856341987aaa0ead648fa77d2df646192e2a92fb57018f6c7b7d"),
            BuiltinModifierPlugins.reference("body/legacy", 1),
        )
        for (entry in BuiltinModifierPlugins.entries) {
            val definition = entry.definition
            val stack = RigModifierStack().add(definition.type, definition.version, targets)
            val pinned = stack.instances.single()
            assertEquals(entry.reference, pinned.plugin)
            assertEquals(stack, RigModifierStack.fromJson(stack.toJson()))
            val legacy = pinned.copy(plugin = null)
            assertEquals(registry.definition(legacy), registry.definition(pinned))
            assertNull(RigModifierStack.fromJson(RigModifierStack(instances = listOf(legacy)).toJson()).instances.single().plugin)
            assertEquals(legacy.outputId("对象:é"), pinned.outputId("对象:é"))
            assertFailsWith<IllegalArgumentException> { registry.definition(pinned.copy(plugin = entry.reference.copy(digest = "0".repeat(64)))) }
        }
        assertEquals("modifier:m1:e5afb9e8b1a13ac3a9", RigModifierInstance("m1", "motion", 1, targets).outputId("对象:é"))
    }

    @Test fun openMouthCheekLidV9IsAnExplicitIndependentPackage() {
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("mouth.cheek-lid", 9))
        assertEquals("builtin.6d6f7574682e636865656b2d6c6964.v9", artifact.reference.id)
        assertEquals(artifact.reference.id, artifact.manifest.id)
        assertEquals(artifact.manifest.version, artifact.reference.version)
        val entry = BuiltinModifierPlugins.entries.single { it.definition.type == "mouth.cheek-lid" && it.definition.version == 9 }
        assertEquals(artifact.reference, entry.reference)
        assertEquals(artifact.reference, BuiltinModifierPlugins.reference("mouth.cheek-lid", 9))
        val definition = entry.definition
        assertTrue(definition.explicitVersion)
        assertTrue(definition.upstreamInputs)
        assertTrue(definition.optionalUpstream.intersect(setOf("face/fitted", "brow/fitted")).isEmpty())
        val required = setOf("rig.scaffold", "mouth/fitted", "eye/fitted", "face/fitted", "brow/fitted")
        assertEquals(required, definition.requires)
        val instance = RigModifierInstance("cheek", "mouth.cheek-lid", 9, listOf(ModifierTarget("mesh", "lid")))
        assertEquals(emptyList(), definition.parameters(instance))
        val declaration = artifact.manifest.algorithms.single()
        assertEquals(9, declaration.version)
        assertEquals(2, declaration.contextVersion)
        assertEquals(required, declaration.requires)
        assertEquals(setOf("mesh", "parameter", "canvas", "deformer"), declaration.reads)
        assertEquals(setOf("mesh", "deformer"), declaration.writes)
        assertEquals(emptyList(), declaration.parameters)
        assertEquals(1, BuiltinModifierPlugins.definitions(null)
            .filter { it.type == "mouth.cheek-lid" && !it.explicitVersion }.maxOf { it.version })
    }

    @Test fun openMouthCheekLidV9MissingFaceOrBrowSkipsCapture() {
        val targets = listOf(ModifierTarget("mesh", "lid"))
        val authored = io.github.psd2live.format.model.RigIR(io.github.psd2live.format.model.Canvas(1f, 1f), emptyList(),
            meshes = listOf(io.github.psd2live.format.model.Mesh("lid", "Lid", null, geometry = null, offsets = null)))
        var captures = 0
        val definition = RigMouthCheekLidModifier.openShellDefinition(null).copy(
            validate = { _, _ -> },
            authorContext = {
                captures++
                error("Reached cheek-lid v9 source capture")
            })
        val versions = mapOf("rig.scaffold" to 2, "mouth/fitted" to 3, "eye/fitted" to 5, "face/fitted" to 3, "brow/fitted" to 4)
        val providers = versions.entries.mapIndexed { index, (type, version) ->
            RigModifierInstance("provider$index", type, version, targets)
        }
        val providerIds = providers.associate { it.type to it.id }
        val instance = RigModifierInstance("cheek", "mouth.cheek-lid", 9, targets, config = buildJsonObject {
            put("scaffold", providerIds.getValue("rig.scaffold"))
            put("base", providerIds.getValue("mouth/fitted"))
            putJsonObject("dependencies") { put("eye/fitted", providerIds.getValue("eye/fitted")) }
        })
        // Keep the real v9 dependencies and provider selection; stub geometry validation and
        // producer execution to test suspension before source capture without an artwork fixture.
        val producerDefinitions = providers.map { provider -> RigModifierDefinition(provider.type, provider.version,
            plugin = ModifierAlgorithmPlugin { rig, _, _ -> rig }) }
        val evaluator = RigModifierEvaluator(RigModifierRegistry(producerDefinitions + definition))
        val stack = RigModifierStack(instances = providers + instance)
        for (type in listOf("face/fitted", "brow/fitted")) {
            val id = providerIds.getValue(type)
            for (unavailable in listOf(stack.remove(id), stack.update(id) { it.copy(enabled = false) })) {
                val result = evaluator.evaluate(authored, unavailable)
                val consumer = result.entries.single { it.instance.id == instance.id }
                assertEquals(ModifierStatus.MISSING_DEPENDENCY, consumer.status)
                assertEquals(setOf(type), consumer.missingDependencies)
                assertEquals(authored, result.rig)
                assertEquals(0, captures, "Unavailable $type must suspend v9 before source capture")
            }
        }
        // A complete chain must reach the capture sentinel, proving this is not a disabled consumer.
        val reached = assertFailsWith<IllegalStateException> { evaluator.evaluate(authored, stack) }
        assertEquals("Reached cheek-lid v9 source capture", reached.message)
        assertEquals(1, captures)
    }

    @Test fun installedPackagesLockBundledDependenciesAndResumeAfterReenable() {
        val preferences = Preferences.userRoot().node("psd2live-test/plugins/${UUID.randomUUID()}")
        try {
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            val bundled = BuiltinModifierPlugins.reference("motion", 1)!!
            val source = Files.createDirectory(directory.resolve("package"))
            val bytes = byteArrayOf(1, 2, 3)
            Files.write(source.resolve("worker"), bytes)
            val manifest = ModifierPluginManifest(id = "consumer", version = "1.0.0", name = "Consumer",
                commands = mapOf(ModifierPluginStore.platform to listOf("worker")), files = mapOf("worker" to ModifierPluginStore.hash(bytes)),
                dependencies = listOf(ModifierPluginDependency(bundled.id, ModifierPluginVersionRange(exact = bundled.version))),
                algorithms = listOf(ModifierPluginAlgorithm("consumer/entry", name = "Entry",
                    configSchema = buildJsonObject { put("type", "object") }, defaults = JsonObject(emptyMap()))))
            Files.writeString(source.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
            val installed = store.install(source.toString())
            assertEquals(listOf(bundled), store.resolution(installed.reference).dependencies)
            assertEquals(bundled.digest, installed.reference.dependencyDigests["${bundled.id}@${bundled.version}"])
            store.setBuiltinEnabled(bundled, false)
            assertFalse(store.resolution(installed.reference).available)
            store.setBuiltinEnabled(bundled, true)
            assertTrue(ModifierPluginStore(store.root, preferences).resolution(installed.reference).available)
        } finally { preferences.removeNode() }
    }
}
