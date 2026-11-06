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
