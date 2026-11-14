package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.test.*

class ModifierPluginDependenciesTest {
    private fun entry(id: String, version: String = "1.0.0", dependencies: List<ModifierPluginDependency> = emptyList(), conflicts: List<ModifierPluginDependency> = emptyList()) = InstalledModifierPlugin(
        ModifierPluginManifest(id = id, version = version, name = id, commands = emptyMap(), files = emptyMap(), algorithms = emptyList(), dependencies = dependencies, conflicts = conflicts),
        ModifierPluginReference(id, version, "$id-$version"), Path.of("."), true)

    @Test fun versionPrecedenceAndBoundsAreNumericAndRejectMalformedVersions() {
        fun v(s: String) = ModifierPluginVersion.parse(s)
        assertTrue(v("2.10.0") > v("2.9.0"))
        assertTrue(v("2.0.0") > v("2.0.0-rc.9"))
        assertTrue(v("2.0.0-rc.10") > v("2.0.0-rc.9"))
        assertEquals(v("1.0.0+a"), v("1.0.0+b"))
        val range = ModifierPluginVersionRange(minimum = "1.2.0", maximum = "2.0.0")
        range.validate(); assertTrue(range.accepts("1.9.0")); assertFalse(range.accepts("2.0.0"))
        listOf("1.2", "01.2.3", "1.2.3-01", "1.2.3-rc..1", "1.2.3+").forEach { assertFails { v(it) } }
        assertFails { ModifierPluginVersionRange(exact = "1.0.0", minimum = "1.0.0").validate() }
        assertFails { ModifierPluginVersionRange(minimum = "2.0.0", maximum = "1.0.0").validate() }
    }

    @Test fun unlockedBundledDependenciesUseCurrentContentsWhileHistoricalLocksStayExact() {
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("physics", 1))
        val current = InstalledModifierPlugin(artifact.manifest, artifact.reference, Path.of("."), true)
        val historical = current.copy(reference = current.reference.copy(digest = "f".repeat(64)))
        val consumer = entry("consumer", dependencies = listOf(ModifierPluginDependency(current.reference.id)))
        val packages = listOf(consumer, historical, current)
        assertEquals(listOf(current.reference), ModifierPluginDependencies.resolve(consumer.reference, packages).dependencies)
        val locked = consumer.copy(dependencyLock = listOf(historical.reference))
        assertEquals(listOf(historical.reference), ModifierPluginDependencies.resolve(locked.reference, listOf(locked, current, historical)).dependencies)
        assertFalse(ModifierPluginDependencies.resolve(locked.reference, listOf(locked, current)).available)
        assertFalse(ModifierPluginDependencies.resolve(consumer.reference, listOf(consumer, historical)).available)
    }

    @Test fun lockedDependenciesDoNotUpgradeAndRecoverOnlyWithOriginalContents() {
        val base = entry("base")
        val dependent = entry("consumer", dependencies = listOf(ModifierPluginDependency("base"))).copy(dependencyLock = listOf(base.reference))
        val newer = entry("base", "2.0.0")
        fun resolve(vararg packages: InstalledModifierPlugin) = ModifierPluginDependencies.resolve(dependent.reference, packages.toList())
        assertEquals(listOf(base.reference), resolve(base, newer, dependent).dependencies)
        assertFalse(resolve(newer, dependent).available)
        assertFalse(resolve(base.copy(enabled = false), dependent).available)
        assertFalse(resolve(base.copy(reference = base.reference.copy(digest = "different")), dependent).available)
        assertTrue(resolve(base, dependent).available)
    }

    @Test fun conflictsAreSymmetricAndTransitiveCyclesAreUnavailable() {
        val a = entry("a", conflicts = listOf(ModifierPluginDependency("b", ModifierPluginVersionRange(maximum = "2.0.0"))))
        val b = entry("b")
        assertFalse(ModifierPluginDependencies.resolve(a.reference, listOf(a, b)).available)
        assertFalse(ModifierPluginDependencies.resolve(b.reference, listOf(a, b)).available)
        assertTrue(ModifierPluginDependencies.resolve(a.reference, listOf(a, entry("b", "2.0.0"))).available)
        val c = entry("c", dependencies = listOf(ModifierPluginDependency("d")))
        val d = entry("d", dependencies = listOf(ModifierPluginDependency("c")))
        assertTrue(ModifierPluginDependencies.resolve(c.reference, listOf(c, d)).issues.any { "cycle" in it })
    }
    @Test fun installationLocksTheDependencyClosureAndRejectsConflictsAtomically() {
        val root = java.nio.file.Files.createTempDirectory("plugin-dependencies-")
        val preferences = java.util.prefs.Preferences.userRoot().node("psd2live-test/plugins/${java.util.UUID.randomUUID()}")
        try {
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            fun pack(id: String, version: String = "1.0.0", dependencies: List<ModifierPluginDependency> = emptyList(), conflicts: List<ModifierPluginDependency> = emptyList()): String {
                val directory = java.nio.file.Files.createDirectory(root.resolve("$id-$version"))
                java.nio.file.Files.write(directory.resolve("worker"), byteArrayOf(1, 2, 3))
                val schema = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").let { it as JsonObject }
                val manifest = ModifierPluginManifest(id = id, version = version, name = id,
                    commands = mapOf(ModifierPluginStore.platform to listOf("worker")), files = mapOf("worker" to ModifierPluginStore.hash(byteArrayOf(1, 2, 3))),
                    algorithms = listOf(ModifierPluginAlgorithm("$id/algorithm", name = id, configSchema = schema, defaults = JsonObject(emptyMap()))), dependencies = dependencies, conflicts = conflicts)
                java.nio.file.Files.writeString(directory.resolve("plugin.json"), kotlinx.serialization.json.Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
                return directory.toString()
            }
            val basePath = pack("base")
            val base = store.install(basePath)
            val consumer = store.install(pack("consumer", dependencies = listOf(ModifierPluginDependency("base"))))
            assertEquals(mapOf("base@1.0.0" to base.reference.digest), consumer.reference.dependencyDigests)
            store.install(pack("base", "2.0.0"))
            assertEquals(listOf(base.reference), store.resolution(consumer.reference).dependencies)
            store.uninstall(base.reference)
            assertFalse(store.resolution(consumer.reference).available)
            assertEquals(consumer.reference, ModifierPluginStore(store.root, preferences).snapshot().single { it.reference.id == "consumer" }.reference)
            store.install(basePath)
            assertTrue(store.resolution(consumer.reference).available)
            val before = store.snapshot()
            assertFails { store.install(pack("conflicting", conflicts = listOf(ModifierPluginDependency("consumer")))) }
            assertEquals(before, store.snapshot())
            store.setEnabled(base.reference, false)
            assertFalse(store.resolution(consumer.reference).available)
            store.setEnabled(base.reference, true)
            assertTrue(store.resolution(consumer.reference).available)
        } finally { preferences.removeNode(); ModifierPluginStore.deleteTree(root) }
    }

}
