package io.github.psd2live.core.modifier

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.prefs.Preferences
import kotlin.test.*

object ParameterTreeTestWorker {
    val algorithm = object : ReadingModifierAlgorithmPlugin {
        override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit) =
            ModifierModelChanges(parameterTree = ModifierFieldChange(input.parameterTree,
                listOf(ParameterNode.Param(if (instance.config["invalid"]?.jsonPrimitive?.boolean == true) "missing" else "b"),
                    ParameterNode.Param("a"))))
    }

    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(("tree-test/order" to 1) to algorithm))
}

class ModifierEvaluationConsistencyTest {
    private val input = RigIR(Canvas(10f, 10f), listOf(
        Parameter("a", "A", -1f, 1f, 0f), Parameter("b", "B", -1f, 1f, 0f)),
        meshes = listOf(Mesh("target", "Target", null, geometry = null, offsets = null)))
    private fun instance(type: String = "probe") = RigModifierInstance(type, type, 1, listOf(ModifierTarget("mesh", "target")))
    private fun evaluator(plugin: ModifierAlgorithmPlugin, writes: Set<String> = setOf("hierarchy")) =
        RigModifierEvaluator(RigModifierRegistry(listOf(RigModifierDefinition("probe", 1, plugin = plugin, changeWrites = writes))))

    @Test fun readCacheNormalizesParameterOrderAndPreservesContributions() {
        var calls = 0
        val plugin = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                calls++
                return ParameterTreeTestWorker.algorithm.changes(input, instance, checkpoint)
            }
        }
        val engine = evaluator(plugin)
        val stack = RigModifierStack(listOf(instance()))
        val cache = RigModifierEvaluationCache()
        val cold = engine.evaluate(input, stack, cache)
        val otherCanvas = input.copy(canvas = Canvas(20f, 10f))
        val hot = engine.evaluate(otherCanvas, stack, cache)
        assertEquals(1, calls, "The second result must replay tracked changes")
        assertEquals(listOf("b", "a"), hot.rig.parameters.map { it.id })
        assertEquals(cold.copy(rig = cold.rig.copy(canvas = otherCanvas.canvas)), hot)
        assertEquals(hot, engine.evaluate(otherCanvas, stack))
        assertEquals(2, calls)
    }

    @Test fun readAheadUsesTheSameNormalizationAsSerialExecution() {
        val caller = Thread.currentThread()
        val threads = ConcurrentLinkedQueue<Thread>()
        val physics = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit) =
                ModifierModelChanges(physicsFps = ModifierFieldChange(input.physics.fps, 120f))
        }
        val motion = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                threads += Thread.currentThread()
                return ParameterTreeTestWorker.algorithm.changes(input, instance, checkpoint)
            }
        }
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(
            RigModifierDefinition("physics", 1, plugin = physics, changeWrites = setOf("physics")),
            RigModifierDefinition("motion", 1, plugin = motion, changeWrites = setOf("hierarchy")))))
        val result = engine.evaluate(input, RigModifierStack(listOf(instance("physics"), instance("motion"))))
        assertEquals(1, threads.size)
        if (java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() >= 2) assertNotEquals(caller, threads.single())
        val serial = engine.evaluate(input.copy(physics = Physics(fps = 120f)), RigModifierStack(listOf(instance("motion"))))
        assertEquals(serial.rig, result.rig)
        assertEquals(serial.entries.single(), result.entries.last())
        assertEquals(listOf("b", "a"), result.rig.parameters.map { it.id })
        val cache = RigModifierEvaluationCache()
        engine.evaluate(input, RigModifierStack(), cache)
        val published = cache.snapshot
        val invalid = instance("motion").copy(config = buildJsonObject { put("invalid", true) })
        assertFailsWith<IllegalArgumentException> {
            engine.evaluate(input, RigModifierStack(listOf(instance("physics"), invalid)), cache)
        }
        assertSame(published, cache.snapshot)
        if (java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() >= 2) assertNotEquals(caller, threads.last())
    }

    @Test fun cachedReadsRejectDanglingReferencesWithoutPublishingOrReexecuting() {
        var calls = 0
        val target = Mesh("target", "Target", null, geometry = null, offsets = null)
        val mask = target.copy(id = "mask")
        val authored = input.copy(meshes = listOf(target, mask))
        val plugin = object : ReadingModifierAlgorithmPlugin {
            override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
                calls++
                val before = requireNotNull(input.mesh("target"))
                return ModifierModelChanges(meshes = ModifierObjectChanges(updated = listOf(
                    ModifierObjectUpdate(before, before.copy(maskedBy = listOf("mask"))))))
            }
        }
        val engine = evaluator(plugin, setOf("mesh"))
        val stack = RigModifierStack(listOf(instance()))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(authored, stack, cache)
        val published = cache.snapshot
        val missingMask = authored.copy(meshes = listOf(target))
        assertFailsWith<IllegalArgumentException> { engine.evaluate(missingMask, stack, cache) }
        assertEquals(1, calls, "Reference validation must run even on a valid read-cache hit")
        assertSame(published, cache.snapshot)
        assertFailsWith<IllegalArgumentException> { engine.evaluate(missingMask, stack) }
        assertEquals(2, calls)
        assertEquals(emptyList(), target.maskedBy)
    }

    @Test fun dependencyCacheRevalidatesReferencesAgainstActualUpstream() {
        var calls = 0
        val upstream = RigModifierDefinition("upstream", 1, plugin = ModifierAlgorithmPlugin { rig, entry, _ ->
            if (entry.config["remove"]?.jsonPrimitive?.boolean == true) rig.copy(parameters = rig.parameters.filter { it.id != "a" }) else rig
        })
        val downstream = RigModifierDefinition("probe", 1, cacheReads = setOf("mesh"), plugin = ModifierAlgorithmPlugin { rig, _, _ ->
            calls++
            rig.copy(parameterTree = listOf(ParameterNode.Param("a")))
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(upstream, downstream)))
        fun stack(remove: Boolean) = RigModifierStack(listOf(instance("upstream").copy(config = buildJsonObject { put("remove", remove) }), instance()))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(input, stack(false), cache)
        val published = cache.snapshot
        assertFailsWith<IllegalArgumentException> { engine.evaluate(input, stack(true), cache) }
        assertEquals(1, calls)
        assertSame(published, cache.snapshot)
        assertFailsWith<IllegalArgumentException> { engine.evaluate(input, stack(true)) }
        assertEquals(2, calls)
    }

    @Test fun dependencyCacheDoesNotReplayDerivedParameterOrderOverUpstreamAdditions() {
        var calls = 0
        val upstream = RigModifierDefinition("upstream", 1, plugin = ModifierAlgorithmPlugin { rig, entry, _ ->
            if (entry.config["add"]?.jsonPrimitive?.boolean == true)
                rig.copy(parameters = rig.parameters + Parameter("c", "C", -1f, 1f, 0f)) else rig
        })
        val downstream = RigModifierDefinition("probe", 1, cacheReads = setOf("mesh"), plugin = ModifierAlgorithmPlugin { rig, _, _ ->
            calls++
            rig.copy(parameterTree = listOf(ParameterNode.Param("b"), ParameterNode.Param("a")))
        })
        val engine = RigModifierEvaluator(RigModifierRegistry(listOf(upstream, downstream)))
        fun stack(add: Boolean) = RigModifierStack(listOf(instance("upstream").copy(config = buildJsonObject { put("add", add) }), instance()))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(input, stack(false), cache)
        val hot = engine.evaluate(input, stack(true), cache)
        assertEquals(1, calls)
        assertEquals(listOf("b", "a", "c"), hot.rig.parameters.map { it.id })
        assertEquals(hot, engine.evaluate(input, stack(true)))
        assertEquals(2, calls)
    }

    @Test fun invalidTreeAndOverrideNeverReplaceSuccessfulSnapshot() {
        val engine = evaluator(ParameterTreeTestWorker.algorithm)
        val stack = RigModifierStack(listOf(instance()))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(input, stack, cache)
        val published = cache.snapshot
        val invalid = stack.copy(instances = listOf(instance().copy(config = buildJsonObject { put("invalid", true) })))
        assertFailsWith<IllegalArgumentException> { engine.evaluate(input, invalid, cache) }
        assertSame(published, cache.snapshot)
        val overrideEngine = RigModifierEvaluator(RigModifierRegistry(listOf(
            RigModifierDefinition("probe", 1, plugin = ParameterTreeTestWorker.algorithm, changeWrites = setOf("hierarchy"))))) { rig, _, _ ->
            ModifierOverrideResult(rig.copy(parameterTree = listOf(ParameterNode.Param("missing"))))
        }
        val edited = stack.copy(overrides = mapOf("probe" to listOf(buildJsonObject { put("op", "invalid") })))
        assertFailsWith<IllegalArgumentException> { overrideEngine.evaluate(input, edited, cache) }
        assertSame(published, cache.snapshot)
    }

    @Test fun cancellationAtEveryCachedReplayCheckpointKeepsThePublishedSnapshot() {
        val engine = evaluator(ParameterTreeTestWorker.algorithm)
        val stack = RigModifierStack(listOf(instance()))
        val cache = RigModifierEvaluationCache()
        engine.evaluate(input, stack, cache)
        val published = cache.snapshot
        val changed = input.copy(canvas = Canvas(20f, 10f))
        var total = 0
        engine.evaluate(changed, stack, RigModifierEvaluationCache().also { it.snapshot = published }) { total++ }
        assertTrue(total > 0)
        for (cancelAt in 1..total) {
            var checkpoints = 0
            assertFailsWith<java.util.concurrent.CancellationException> {
                engine.evaluate(changed, stack, cache) {
                    if (++checkpoints == cancelAt) throw java.util.concurrent.CancellationException("cancel")
                }
            }
            assertSame(published, cache.snapshot)
        }
    }

    @Test fun protocolTwoWorkerAndInProcessResultsHaveIdenticalOrderAndValidation() {
        val directory = Files.createTempDirectory("tree-worker-")
        val prefs = Preferences.userRoot().node("tree-worker/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), prefs)
        var reference: ModifierPluginReference? = null
        try {
            val pack = Files.createDirectory(directory.resolve("package"))
            val jarManifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] = ParameterTreeTestWorker::class.java.name
                mainAttributes[Attributes.Name.CLASS_PATH] = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
                    .filter { it.isNotBlank() }.joinToString(" ") { Path.of(it).toUri().toASCIIString() }
            }
            val jar = pack.resolve("worker.jar")
            JarOutputStream(Files.newOutputStream(jar), jarManifest).close()
            val algorithm = ModifierPluginAlgorithm("tree-test/order", name = "Order", reads = setOf("parameter", "hierarchy"),
                writes = setOf("hierarchy"), configSchema = buildJsonObject { }, defaults = buildJsonObject { })
            val manifest = ModifierPluginManifest(protocol = 2, id = "tree-test", name = "Tree", version = "1",
                commands = mapOf("jvm" to listOf("\$JAVA", "-jar", "worker.jar")), files = mapOf("worker.jar" to ModifierPluginStore.hash(jar)),
                algorithms = listOf(algorithm))
            Files.writeString(pack.resolve("plugin.json"), Json.encodeToString(ModifierPluginManifest.serializer(), manifest))
            val installed = store.install(pack.toString())
            reference = installed.reference
            val worker = object : ContextualModifierAlgorithmPlugin {
                override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit) =
                    ModifierPluginWorkers.evaluate(installed, algorithm, input, instance.copy(type = algorithm.type, plugin = installed.reference),
                        checkpoint, store, context = context)
            }
            val stack = RigModifierStack(listOf(instance()))
            val cache = RigModifierEvaluationCache()
            val remote = evaluator(worker)
            assertEquals(evaluator(ParameterTreeTestWorker.algorithm).evaluate(input, stack), remote.evaluate(input, stack, cache))
            val published = cache.snapshot
            val invalid = stack.copy(instances = listOf(instance().copy(config = buildJsonObject { put("invalid", true) })))
            assertFailsWith<IllegalArgumentException> { remote.evaluate(input, invalid, cache) }
            assertSame(published, cache.snapshot)
        } finally {
            reference?.let(ModifierPluginWorkers::stop)
            prefs.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
