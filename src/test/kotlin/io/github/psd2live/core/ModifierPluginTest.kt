package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class ModifierPluginTest {
    private fun fixture(action: (Path, ModifierPluginStore, Preferences) -> Unit) {
        val root = Files.createTempDirectory("plugin-test-")
        val preferences = Preferences.userRoot().node("psd2live-test/plugins/${UUID.randomUUID()}")
        try { action(root, ModifierPluginStore(root.resolve("installed"), preferences), preferences) }
        finally { preferences.removeNode(); ModifierPluginStore.deleteTree(root) }
    }

    private fun packageAt(directory: Path): Path {
        Files.createDirectories(directory)
        val source = directory.resolve("Example.java")
        Files.writeString(source, """
            import java.io.*;
            public class Example {
                public static void main(String[] args) throws Exception {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    int sequence = 0;
                    while (reader.readLine() != null) {
                        System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"commands\":[{\"op\":\"mesh_opacity\",\"id\":\"body\",\"value\":0.5}]}");
                    }
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()))
        val jar = directory.resolve("example.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); output.write("Manifest-Version: 1.0\r\nMain-Class: Example\r\n\r\n".toByteArray()); output.closeEntry()
            output.putNextEntry(JarEntry("Example.class")); output.write(Files.readAllBytes(directory.resolve("Example.class"))); output.closeEntry()
        }
        Files.delete(source); Files.delete(directory.resolve("Example.class"))
        val schema = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())); put("additionalProperties", false) }
        val manifest = ModifierPluginManifest(id = "test-plugin", version = "1", name = "Test",
            commands = mapOf("jvm" to listOf("\$JAVA", "-jar", "example.jar")),
            files = mapOf("example.jar" to ModifierPluginStore.hash(Files.readAllBytes(jar))),
            algorithms = listOf(ModifierPluginAlgorithm("test-plugin/fade", name = "Fade", configSchema = schema, defaults = JsonObject(emptyMap()))))
        Files.writeString(directory.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
        return directory
    }

    @Test fun installationReplayAndWorkerReusePreserveIdentity() = fixture { root, store, preferences ->
        val path = packageAt(root.resolve("package"))
        val entry = store.install(path.toString())
        assertEquals(entry.reference, store.install(path.toString()).reference)
        assertEquals(entry.reference, ModifierPluginStore(store.root, preferences).snapshot().single().reference)
        val instance = RigModifierInstance("m1", "test-plugin/fade", 1, listOf(ModifierTarget("mesh", "body")), plugin = entry.reference)
        val stack = RigModifierStack(instances = listOf(instance))
        assertEquals(stack, RigModifierStack.fromJson(stack.toJson()))
        val rig = RigIR(Canvas(100f, 100f), emptyList(), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
        try {
            repeat(2) {
                val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), rig, instance, {}, store)
                assertEquals(.5f, result.meshes.single().opacity)
                assertEquals(1f, rig.meshes.single().opacity)
            }
            store.setEnabled(entry.reference, false)
            assertFailsWith<IllegalArgumentException> { store.requireAvailable(entry.reference) }
            store.setEnabled(entry.reference, true)
            store.uninstall(entry.reference)
            assertTrue(store.snapshot().isEmpty())
            assertEquals(stack, RigModifierStack.fromJson(stack.toJson()))
            assertEquals(instance.plugin, store.install(path.toString()).reference)
        } finally { ModifierPluginWorkers.stop(entry.reference) }
    }

    @Test fun invalidPackagesNeverPublishPartialInstalls() = fixture { root, store, _ ->
        val path = packageAt(root.resolve("package"))
        Files.write(path.resolve("example.jar"), byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> { store.install(path.toString()) }
        assertTrue(store.snapshot().isEmpty())
        val zip = root.resolve("escape.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            output.putNextEntry(ZipEntry("../escaped")); output.write(1); output.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { store.install(zip.toString()) }
        assertFalse(Files.exists(root.resolve("escaped")))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun corruptedInstallationDoesNotPreventOpeningAndCanBeReinstalled() = fixture { root, store, preferences ->
        val source = packageAt(root.resolve("package"))
        val installed = store.install(source.toString())
        Files.write(installed.directory.resolve("example.jar"), byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { store.verify(installed.reference) }
        val reopened = ModifierPluginStore(store.root, preferences)
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(reopened.problems().isNotEmpty())
        val restored = reopened.install(source.toString())
        assertEquals(installed.reference, restored.reference)
        reopened.verify(restored.reference)
    }

    @Test fun pinnedPackageCannotResolveToAnotherBuild() {
        val reference = ModifierPluginReference("test-plugin", "1", "a".repeat(64))
        val definition = RigModifierDefinition("test-plugin/fade", 1, evaluate = { rig, _, _ -> rig }, pluginReference = reference)
        val registry = RigModifierRegistry(listOf(definition))
        val instance = RigModifierInstance("m1", definition.type, 1, listOf(ModifierTarget("mesh", "body")), plugin = reference.copy(digest = "b".repeat(64)))
        assertFailsWith<IllegalArgumentException> { registry.definition(instance) }
    }

    @Test fun componentCacheReusesIndependentAlgorithmWithoutErasingUpstreamChanges() {
        var calls = 0
        val first = RigModifierDefinition("input", 1, evaluate = { rig, instance, _ ->
            rig.copy(clips = listOf(Clip("clip", instance.config.getValue("name").jsonPrimitive.content, "Idle", "clip", 1f, 30f, false, null, null, emptyList(), emptyList())))
        })
        val second = RigModifierDefinition("part", 1, cacheReads = setOf("part"), evaluate = { rig, _, _ ->
            calls++; rig.copy(parts = rig.parts.map { it.copy(drawOrder = it.drawOrder + 1) })
        })
        val rig = RigIR(Canvas(1f, 1f), emptyList(), parts = listOf(Part("body", "Body", emptyList())))
        val targets = listOf(ModifierTarget("part", "body"))
        val initial = RigModifierStack().add("input", 1, targets, buildJsonObject { put("name", "A") }).add("part", 1, targets)
        val evaluator = RigModifierEvaluator(RigModifierRegistry(listOf(first, second)))
        val cache = RigModifierEvaluationCache()
        evaluator.evaluate(rig, initial, cache)
        val changed = initial.update("m1") { it.copy(config = buildJsonObject { put("name", "B") }) }
        val result = evaluator.evaluate(rig, changed, cache)
        assertEquals(1, calls)
        assertEquals("B", result.rig.clips.single().name)
        assertEquals(evaluator.evaluate(rig, changed).rig, result.rig)
    }
}
