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
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("\"mode\":\"crash\"")) System.exit(7);
                        if (line.contains("\"mode\":\"wait\"")) { reader.readLine(); return; }
                        if (line.contains("\"mode\":\"invalid\"")) { System.out.println("{\"protocol\":99}"); continue; }
                        if (line.contains("\"mode\":\"bad-write\"")) {
                            System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"commands\":[{\"op\":\"mesh_opacity\",\"id\":\"body\",\"value\":2}]}");
                            continue;
                        }
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

    @Test fun bundledAliasesMustBeUniqueAndMatchPackageIdentity() = fixture { root, store, _ ->
        val path = packageAt(root.resolve("package"))
        val manifestPath = path.resolve("plugin.json")
        val manifest = Json.decodeFromString(ModifierPluginManifest.serializer(), Files.readString(manifestPath))
        val id = "builtin.70687973696373.v1"
        val algorithm = manifest.algorithms.single().copy(type = "$id/pendulum", aliases = setOf("physics"))
        fun write(value: ModifierPluginManifest) = Files.writeString(manifestPath,
            Json.encodeToString(ModifierPluginManifest.serializer(), value))
        write(manifest.copy(algorithms = listOf(manifest.algorithms.single().copy(aliases = setOf("physics")))))
        assertFailsWith<IllegalArgumentException> { store.inspect(path.toString()) }
        write(manifest.copy(id = id, algorithms = listOf(algorithm, algorithm.copy(type = "$id/other"))))
        assertFailsWith<IllegalArgumentException> { store.inspect(path.toString()) }
        write(manifest.copy(id = id, algorithms = listOf(algorithm)))
        assertEquals(setOf("physics"), store.inspect(path.toString()).manifest.algorithms.single().aliases)
        assertTrue(store.snapshot().isEmpty())
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
        Files.writeString(path.resolve(".origin"), "a".repeat(40))
        assertFailsWith<IllegalArgumentException> { store.install(path.toString()) }
        assertTrue(store.snapshot().isEmpty())
        Files.delete(path.resolve(".origin"))
        val hidden = Files.createDirectories(path.resolve("nested")).resolve("plugin.json")
        Files.writeString(hidden, "{}")
        assertFailsWith<IllegalArgumentException> { store.install(path.toString()) }
        assertTrue(store.snapshot().isEmpty())
        Files.delete(hidden)
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

    @Test fun workerFailuresAndCancellationDiscardResultsAndRestartCleanly() = fixture { root, store, _ ->
        val entry = store.install(packageAt(root.resolve("package")).toString())
        val algorithm = entry.manifest.algorithms.single()
        val instance = RigModifierInstance("m1", algorithm.type, 1, listOf(ModifierTarget("mesh", "body")), plugin = entry.reference)
        val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
        fun request(mode: String) = instance.copy(config = buildJsonObject { put("mode", mode) })
        try {
            for (mode in listOf("crash", "invalid", "bad-write", "wait", "cancel")) {
                var checkpoints = 0
                val failure = assertFails {
                    ModifierPluginWorkers.evaluate(entry, algorithm, rig, request(if (mode == "cancel") "wait" else mode), {
                        if (mode == "cancel" && ++checkpoints >= 3) throw java.util.concurrent.CancellationException("Cancelled candidate")
                    }, store, timeoutMillis = if (mode == "wait") 250 else 5_000)
                }
                if (mode == "wait") assertIs<java.util.concurrent.TimeoutException>(failure)
                if (mode == "cancel") assertIs<java.util.concurrent.CancellationException>(failure)
                assertEquals(1f, rig.meshes.single().opacity, "Failed output changed the input for $mode")
                assertEquals(.5f, ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance, {}, store).meshes.single().opacity,
                    "A fresh worker must restart after $mode")
            }
        } finally { ModifierPluginWorkers.stop(entry.reference) }
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

    @Test fun queuedCancellationDoesNotKillActiveWorkerAndUninstallStopsIt() = fixture { root, store, _ ->
        val source = packageAt(root.resolve("package"))
        val entry = store.install(source.toString())
        val algorithm = entry.manifest.algorithms.single()
        val instance = RigModifierInstance("m1", algorithm.type, 1, listOf(ModifierTarget("mesh", "body")), plugin = entry.reference)
        val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
        val waiting = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val active = executor.submit<RigIR> {
                var checkpoints = 0
                ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance.copy(config = buildJsonObject { put("mode", "wait") }), {
                    if (++checkpoints >= 4) waiting.countDown()
                }, store, timeoutMillis = 10_000)
            }
            try {
                assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS))
                var checkpoints = 0
                assertFailsWith<java.util.concurrent.CancellationException> {
                    ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance, {
                        if (++checkpoints >= 3) throw java.util.concurrent.CancellationException("Cancelled queued request")
                    }, store)
                }
                assertFalse(active.isDone, "Cancelling a queued candidate killed the active worker")
                store.uninstall(entry.reference)
                assertFailsWith<java.util.concurrent.ExecutionException> { active.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue(store.snapshot().isEmpty())
                val restored = store.install(source.toString())
                assertEquals(instance.plugin, restored.reference)
                assertEquals(.5f, ModifierPluginWorkers.evaluate(restored, algorithm, rig, instance, {}, store).meshes.single().opacity)
            } finally { ModifierPluginWorkers.stop(entry.reference); active.cancel(true) }
        }
    }

    @Test fun pinnedPackageCannotResolveToAnotherBuild() {
        val reference = ModifierPluginReference("test-plugin", "1", "a".repeat(64))
        val definition = RigModifierDefinition("test-plugin/fade", 1, evaluate = { rig, _, _ -> rig }, pluginReference = reference)
        val registry = RigModifierRegistry(listOf(definition))
        val instance = RigModifierInstance("m1", definition.type, 1, listOf(ModifierTarget("mesh", "body")), plugin = reference.copy(digest = "b".repeat(64)))
        assertFailsWith<IllegalArgumentException> { registry.definition(instance) }
    }

    /** Opt-in real Git transport acceptance; regular regression does not require an installed Git daemon. */
    @Test fun gitTagsAndCommitsInstallPrebuiltPinnedPackages() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("PSD2LIVE_PLUGIN_GIT_TEST") == "1")
        fixture { root, store, _ ->
            val repository = packageAt(root.resolve("repository"))
            fun git(vararg args: String): String {
                val log = root.resolve("git-command.log")
                val process = ProcessBuilder(listOf("git", "-c", "user.name=Plugin test", "-c", "user.email=plugin@example.invalid",
                    "-c", "core.hooksPath=") + args).directory(repository.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                try {
                    assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue(), Files.readString(log))
                    return Files.readString(log).trim()
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            git("init"); git("add", "."); git("commit", "-m", "First package"); git("tag", "v1")
            val first = git("rev-parse", "HEAD")
            val manifestPath = repository.resolve("plugin.json")
            val manifest = Json.decodeFromString(ModifierPluginManifest.serializer(), Files.readString(manifestPath))
            Files.writeString(manifestPath, Json.encodeToString(ModifierPluginManifest.serializer(), manifest.copy(version = "2.0.0")))
            git("add", "."); git("commit", "-m", "Second package")
            val second = git("rev-parse", "HEAD")
            val port = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
            val daemon = ProcessBuilder("git", "daemon", "--verbose", "--export-all", "--reuseaddr", "--listen=127.0.0.1",
                "--port=$port", "--base-path=${root.toAbsolutePath()}", root.toAbsolutePath().toString()).redirectErrorStream(true).start()
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val log = daemon.inputStream.bufferedReader()
                val ready = executor.submit<Boolean> { generateSequence { log.readLine() }.any { "Ready to rumble" in it } }
                try {
                    assertTrue(ready.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    val url = "git://127.0.0.1:$port/repository"
                    val tagged = store.install(url, "v1")
                    val pinned = store.install(url, second)
                    assertEquals(first, tagged.gitCommit)
                    assertEquals(second, pinned.gitCommit)
                    assertEquals("1", tagged.reference.version)
                    assertEquals("2.0.0", pinned.reference.version)
                    assertEquals(pinned.reference, store.install(url).reference)
                    assertEquals(2, store.snapshot().size)
                    val instance = RigModifierInstance("m1", "test-plugin/fade", 1, listOf(ModifierTarget("mesh", "body")), plugin = tagged.reference)
                    val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
                    try { assertEquals(.5f, ModifierPluginWorkers.evaluate(tagged, tagged.manifest.algorithms.single(), rig, instance, {}, store).meshes.single().opacity) }
                    finally { ModifierPluginWorkers.stop(tagged.reference) }
                } finally {
                    daemon.descendants().forEach { it.destroyForcibly() }; daemon.destroyForcibly()
                    daemon.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    log.close(); ready.cancel(true)
                }
            }
        }
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
    @Test fun authorInputsInvalidateBothPrefixAndDependencyCaches() {
        var calls = 0
        var source = ModifierAlgorithmContext(resources = mapOf("opacity" to ModifierAlgorithmResource(byteArrayOf(1))))
        val plugin = object : ContextualModifierAlgorithmPlugin {
            override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR {
                calls++
                return input.copy(meshes = input.meshes.map { it.copy(opacity = context.resources.getValue("opacity").read()[0] / 10f) })
            }
        }
        val definition = RigModifierDefinition("source", 1, evaluate = plugin::evaluate, plugin = plugin,
            cacheReads = setOf("mesh"), authorContext = { source })
        val evaluator = RigModifierEvaluator(RigModifierRegistry(listOf(definition)))
        val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(Mesh("body", "Body", null, geometry = null, offsets = null)))
        val stack = RigModifierStack().add("source", 1, listOf(ModifierTarget("mesh", "body")))
        val cache = RigModifierEvaluationCache()
        assertEquals(.1f, evaluator.evaluate(rig, stack, cache).rig.meshes.single().opacity)
        evaluator.evaluate(rig, stack, cache)
        assertEquals(1, calls)
        source = ModifierAlgorithmContext(resources = mapOf("opacity" to ModifierAlgorithmResource(byteArrayOf(5))))
        assertEquals(.5f, evaluator.evaluate(rig, stack, cache).rig.meshes.single().opacity)
        assertEquals(2, calls)
        source = ModifierAlgorithmContext(resources = mapOf("opacity" to ModifierAlgorithmResource(byteArrayOf(5))))
        evaluator.evaluate(rig, stack, cache)
        assertEquals(2, calls)
    }

    @Test fun simulationFingerprintIgnoresUnrelatedMeshesButTracksCarriers() {
        val body = Mesh("body", "Body", "parent", geometry = null, offsets = null)
        val eye = Mesh("eye", "Eye", null, geometry = null, offsets = null)
        val parent = Deformer.Rotation("parent", "Parent", null, null, 0f, null)
        val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(body, eye), deformers = listOf(parent))
        val edit = io.github.psd2live.core.sim.RigSimEdit("simulation", "Simulation", io.github.psd2live.core.sim.SimKind.CLOTH, listOf("body"))
        val captured = RigModifierInput.simulationFingerprint(rig, edit)
        assertTrue(RigModifierInput.simulationMatches(rig.copy(meshes = listOf(body, eye.copy(opacity = .5f))), edit, captured))
        assertFalse(RigModifierInput.simulationMatches(rig.copy(deformers = listOf(parent.copy(baseAngle = 5f))), edit, captured))
        assertTrue(RigModifierInput.simulationMatches(rig, edit, RigModifierInput.fingerprint(rig)))
        val connected = rig.copy(glues = listOf(Glue("link", "body", "eye", emptyList())))
        assertNotEquals(RigModifierInput.simulationFingerprint(connected, edit), RigModifierInput.simulationFingerprint(connected.copy(meshes = listOf(body, eye.copy(opacity = .5f))), edit))
    }

}
