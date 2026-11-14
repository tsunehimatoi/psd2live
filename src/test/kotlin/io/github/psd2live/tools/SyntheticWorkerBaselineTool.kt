package io.github.psd2live.tools

import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in real protocol-2 worker microbenchmark. This does NOT count as workspace edit acceptance. */
class SyntheticWorkerBaselineTool {
    @Test fun profile() {
        requireTools()
        val smoke = setting("PSD2LIVE_BASELINE_SMOKE", "0") == "1"
        val samples = if (smoke) 1 else setting("PSD2LIVE_BASELINE_SAMPLES", "30").toInt().also { require(it >= 30) }
        val edits = if (smoke) 3 else setting("PSD2LIVE_BASELINE_EDITS", "1000").toInt().also { require(it >= 1000) }
        val report = SyntheticBaselineReport(if (smoke) "synthetic-worker-smoke" else "synthetic-worker", "worker microbenchmark; no workspace history, target compilation or first presentation")
        report.metadata("acceptance_run", (!smoke).toString())
        val wasRecording = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try {
            for ((size, meshes, vertices) in listOf(Triple("small", 4, 64), Triple("medium", 16, 256), Triple("large", 64, 1024))) {
                if (smoke && size != "small") continue
                report.context("$size/setup")
                val root = Files.createTempDirectory("synthetic-worker-$size-")
                val preferences = Preferences.userRoot().node("psd2live-tools/baseline/${UUID.randomUUID()}")
                val store = ModifierPluginStore(root.resolve("installed"), preferences)
                try {
                    ModifierPluginWorkers.geometryBudget(store, 2L * 1024 * 1024)
                    val entry = store.install(createSyntheticPluginZip(root, "shared.one", "shared.one/op", 1).toString())
                    val algorithm = entry.manifest.algorithms.single()
                    val input = syntheticRig(meshes, vertices)
                    val instance = RigModifierInstance("benchmark", algorithm.type, 1,
                        input.meshes.map { ModifierTarget("mesh", it.id) }, plugin = entry.reference)
                    report.metadata("$size plugin", "${entry.reference.id}@${entry.reference.version}:${entry.reference.digest}")
                    var session = ModifierPluginWorkers.openSession(store)
                    try {
                        fun execute(label: String, ordinal: Int, rig: RigIR) {
                            report.context("$size/$label/$ordinal")
                            val sample = RigBuildProfile.measure(RigBuildProfile.Operation("$size-$label-$ordinal", "$ordinal",
                                "${ordinal + 1}", "$size/$label", meshes, meshes.toLong() * vertices)) {
                                val result = ModifierPluginWorkers.evaluate(entry, algorithm, rig, instance, {}, store, session = session)
                                assertEquals(.25f, result.meshes.first().opacity)
                                assertEquals(rig.meshes.map { it.geometry }, result.meshes.map { it.geometry })
                                val memory = ModifierPluginWorkers.cacheSnapshots(store).single()
                                assertTrue(memory.geometry.retainedBytes <= memory.geometry.budgetBytes)
                                RigBuildProfile.gauge("geometry entries", memory.geometry.entries.toLong())
                                RigBuildProfile.gauge("geometry evictions cumulative", memory.geometry.evictions)
                                RigBuildProfile.gauge("worker pid", memory.pid)
                            }
                            report.append(sample)
                        }
                        execute("cold-worker", 0, input)
                        repeat(5) { execute("warmup", it, input) }
                        repeat(samples) { execute("warm-identical", it, input) }
                        repeat(samples) { index ->
                            val first = input.meshes.first()
                            execute("single-field", index, input.copy(meshes = listOf(first.copy(drawOrder = index.toFloat())) + input.meshes.drop(1)))
                        }
                        repeat(edits) { index ->
                            val first = input.meshes.first()
                            val geometry = first.geometry!!
                            val positions = geometry.positions.toArray().apply { this[0] += (index + 1) * .0001f }
                            execute("geometry-edit", index, input.copy(meshes = listOf(first.copy(
                                geometry = geometry.copy(positions = Floats.wrap(positions)))) + input.meshes.drop(1)))
                        }
                        repeat(5) { cycle ->
                            report.context("$size/release-before-reopen/$cycle")
                            session.close()
                            session.awaitReleased()
                            ModifierPluginWorkers.awaitRetirements(store)
                            assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
                            session = ModifierPluginWorkers.openSession(store)
                            execute("reopen-worker", cycle, input)
                        }
                    } finally { session.close(); session.awaitReleased(); ModifierPluginWorkers.awaitRetirements(store) }
                    assertTrue(ModifierPluginWorkers.cacheSnapshots(store).isEmpty())
                } finally { preferences.removeNode(); ModifierPluginStore.deleteTree(root) }
            }
            report.complete()
        } catch (failure: Throwable) {
            report.failed(failure)
            throw failure
        } finally { RigBuildProfile.recording = wasRecording; report.close() }
    }

    private fun syntheticRig(meshes: Int, vertices: Int): RigIR = RigIR(Canvas(100f, 100f), emptyList(), meshes = List(meshes) { mesh ->
        val side = kotlin.math.sqrt(vertices.toDouble()).toInt()
        val positions = FloatArray(vertices * 2) { index -> if (index % 2 == 0) (index / 2 % side).toFloat() else (index / 2 / side).toFloat() }
        val indices = buildList { for (y in 0 until side - 1) for (x in 0 until side - 1) {
            val a = y * side + x
            addAll(listOf(a, a + 1, a + side, a + 1, a + side + 1, a + side))
        } }.toIntArray()
        Mesh("mesh-$mesh", "Mesh $mesh", null, geometry = MeshGeometry(Floats.wrap(positions),
            Floats.wrap(FloatArray(vertices * 2) { positions[it] / side }), Ints.wrap(indices)), offsets = null)
    })
}

/** Streams samples without retaining their return values; summary keeps only elapsed times. */
internal class SyntheticBaselineReport(name: String, scope: String,
                                       private val directory: java.io.File = output(name)) : AutoCloseable {
    private val samples = directory.resolve("samples.jsonl").bufferedWriter()
    private val times = linkedMapOf<String, MutableList<Double>>()
    private var complete = false
    private var closed = false
    private var context = "initialization"
    private var failure: JsonObject? = null
    private val metadata = linkedMapOf("scope" to scope, "jdk" to System.getProperty("java.runtime.version"),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        "processors" to Runtime.getRuntime().availableProcessors().toString(),
        "cpu" to (System.getenv("PROCESSOR_IDENTIFIER") ?: "unavailable"),
        "max_heap_bytes" to Runtime.getRuntime().maxMemory().toString(),
        "cold_definition" to "new worker/session in the same JVM; not cold OS page cache or JVM startup",
        "memory_scope" to "JVM heap/nonheap and explicit retained cache accounting; not process RSS or proof of no leak")
    init {
        // Invalidate an earlier successful report even if this run exits before close().
        writeSummary()
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
            check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { "Git metadata timed out" }
            return process.inputStream.bufferedReader().use { it.readText() }.trim()
        }
        metadata["source_revision"] = git("rev-parse", "HEAD")
        metadata["tracked_source_dirty"] = git("status", "--porcelain", "--untracked-files=no").isNotBlank().toString()
    }
    fun metadata(key: String, value: String) { metadata[key] = value }
    fun context(value: String) { context = value }
    fun complete() {
        check(!closed && failure == null)
        complete = true
    }
    fun failed(error: Throwable) {
        complete = false
        failure = buildJsonObject {
            put("context", context); put("type", error.javaClass.name)
            put("message", error.message); put("stack_trace", error.stackTraceToString())
        }
    }
    fun append(sample: RigBuildProfile.Measurement<*>) {
        times.getOrPut(sample.operation.scenario) { arrayListOf() }.add(sample.elapsedMillis)
        samples.appendLine(buildJsonObject {
            put("task", sample.operation.task); put("revision", sample.operation.startRevision); put("candidate", sample.operation.candidate)
            put("scenario", sample.operation.scenario); put("meshes", sample.operation.meshes); put("vertices", sample.operation.vertices)
            put("elapsed_ms", sample.elapsedMillis)
            putJsonObject("stages") { sample.stages.forEach { (stage, value) -> putJsonObject(stage) { put("ms", value.first); put("count", value.second) } } }
            putJsonObject("gauges") { sample.gauges.forEach { (name, value) -> put(name, value) } }
            put("heap_before", sample.before.heapUsed); put("heap_after", sample.after.heapUsed)
            put("heap_committed", sample.after.heapCommitted); put("nonheap_after", sample.after.nonHeapUsed)
        }.toString())
        samples.flush()
    }
    override fun close() {
        if (closed) return
        closed = true
        try { samples.close() } catch (error: Throwable) {
            failed(error)
            try { writeSummary() } catch (reportError: Throwable) { error.addSuppressed(reportError) }
            throw error
        }
        writeSummary()
    }
    private fun writeSummary() {
        directory.resolve("summary.json").writeText(buildJsonObject {
            put("complete", complete)
            put("last_context", context)
            put("observed_samples", times.values.sumOf { it.size })
            failure?.let { put("failure", it) }
            putJsonObject("metadata") { metadata.forEach { (key, value) -> put(key, value) } }
            if (complete) putJsonObject("scenarios") { times.forEach { (name, values) ->
                val sorted = values.sorted()
                putJsonObject(name) {
                    put("samples", values.size); put("median_ms", sorted[sorted.size / 2])
                    put("p95_ms", sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1]); put("max_ms", sorted.last())
                    putJsonArray("outliers_over_3x_median_ms") { values.filter { it > sorted[sorted.size / 2] * 3 }.forEach { add(it) } }
                }
            } }
        }.toString())
    }
}
