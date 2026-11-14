package io.github.psd2live.tools

import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import kotlin.math.ceil

internal data class PartBuildPlanRunOptions(val formal: Boolean, val samples: Int, val warmups: Int, val baseline: String?) {
    fun validate() {
        require(samples in 1..100 && warmups in 0..100)
        if (formal) {
            require(samples >= 30) { "Formal runs require at least 30 measured pairs" }
            require(warmups >= 5) { "Formal runs require at least 5 excluded warmup pairs" }
            require(baseline?.matches(Regex("[0-9a-f]{40}")) == true) { "Formal runs require an explicit full baseline commit" }
        }
    }
    companion object {
        fun environment(): PartBuildPlanRunOptions {
            val mode = setting("PSD2LIVE_BUILD_PLAN_FORMAL", "0")
            require(mode in setOf("0", "1"))
            val formal = mode == "1"
            return PartBuildPlanRunOptions(formal,
                setting("PSD2LIVE_BUILD_PLAN_SAMPLES", if (formal) "30" else "1").toInt(),
                setting("PSD2LIVE_BUILD_PLAN_WARMUPS", if (formal) "5" else "0").toInt(),
                System.getenv("PSD2LIVE_BUILD_PLAN_BASELINE"))
        }
    }
}

internal data class PartBuildPlanSource(val revision: String, val trackedDirty: Boolean, val untracked: Boolean) {
    companion object {
        fun capture(): PartBuildPlanSource {
            fun git(vararg arguments: String): String {
                val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
                check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { "Git metadata timed out" }
                val result = process.inputStream.bufferedReader().readText().trim()
                check(process.exitValue() == 0) { "Git metadata failed: $result" }
                return result
            }
            return PartBuildPlanSource(git("rev-parse", "HEAD"),
                git("status", "--porcelain", "--untracked-files=no").isNotEmpty(),
                git("ls-files", "--others", "--exclude-standard").isNotEmpty())
        }
    }
}

/** Incomplete and failed runs retain observations but never publish successful latency summaries. */
internal class PartBuildPlanRunReport(val options: PartBuildPlanRunOptions, private val source: PartBuildPlanSource) {
    private var complete = false
    private var failure: Throwable? = null
    private var finalSource: PartBuildPlanSource? = null
    private var warmups = 0
    private val rows = mutableListOf<JsonObject>()
    private val hardware = buildJsonObject {
        put("jdk", System.getProperty("java.runtime.version")); put("jdk_vendor", System.getProperty("java.vendor"))
        put("jvm", System.getProperty("java.vm.name"))
        put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        put("cpu", cpu)
        put("logical_processors", Runtime.getRuntime().availableProcessors())
        put("max_heap_bytes", Runtime.getRuntime().maxMemory())
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.let {
            put("physical_memory_bytes", it.totalMemorySize)
        }
    }

    fun validateStart() { options.validate(); verifySource(source) }
    private fun verifySource(value: PartBuildPlanSource) {
        if (options.formal) {
            check(value.revision == options.baseline) { "Source revision differs from the fixed baseline" }
            check(!value.trackedDirty && !value.untracked) { "Formal runs require a clean checkout, including untracked files" }
        }
    }
    fun warmupCompleted() { check(!complete); warmups++ }
    fun append(row: JsonObject) {
        check(!complete)
        val elapsed = row.getValue("elapsed_ms").jsonPrimitive.double
        require(elapsed.isFinite() && elapsed >= 0)
        rows += row
    }
    fun complete(end: PartBuildPlanSource) {
        finalSource = end
        verifySource(end)
        check(end.revision == source.revision && end.trackedDirty == source.trackedDirty && end.untracked == source.untracked) {
            "Source state changed during the run"
        }
        check(warmups == options.warmups)
        for (cache in listOf(true, false)) check(rows.count { it.getValue("cache").jsonPrimitive.boolean == cache } == options.samples)
        check(failure == null)
        complete = true
    }
    fun failed(error: Throwable) { complete = false; failure = error }
    fun json(): JsonObject = buildJsonObject {
        put("status", if (failure != null) "failed" else if (complete) "passed" else "incomplete")
        put("complete", complete); put("performance_acceptance", complete && options.formal)
        put("run_mode", if (options.formal) "formal" else "correctness_smoke")
        put("revision", source.revision); put("baseline_revision", options.baseline ?: source.revision)
        put("tracked_source_dirty", source.trackedDirty); put("untracked_files_present", source.untracked)
        finalSource?.let { put("final_revision", it.revision); put("final_tracked_source_dirty", it.trackedDirty) }
        put("hardware", hardware)
        put("samples_per_mode", options.samples); put("observed_samples", rows.size)
        put("warmup_pairs_requested", options.warmups); put("warmup_pairs_completed", warmups)
        put("warmups_excluded_from_measurements", true)
        put("scope", "modifier build caches in one JVM; no external workers; not cold OS/JVM startup or rendered first-frame latency")
        put("quantile_method", "median averages middle pair; P95 uses nearest rank")
        failure?.let { putJsonObject("failure") {
            put("type", it.javaClass.name); put("message", it.message); put("stack_trace", it.stackTraceToString())
        } }
        if (complete) putJsonObject("latency_by_cache_mode") {
            for (cache in listOf(true, false)) {
                val times = rows.filter { it.getValue("cache").jsonPrimitive.boolean == cache }
                    .map { it.getValue("elapsed_ms").jsonPrimitive.double }.sorted()
                putJsonObject(if (cache) "on" else "off") {
                    put("samples", times.size)
                    put("median_ms", (times[(times.size - 1) / 2] + times[times.size / 2]) / 2)
                    put("p95_ms", times[ceil(times.size * .95).toInt() - 1]); put("max_ms", times.last())
                }
            }
        }
        put("measurements", JsonArray(rows))
    }

    private companion object {
        val cpu: String by lazy {
            System.getenv("PROCESSOR_IDENTIFIER")?.takeIf { it.isNotBlank() }
                ?: if (System.getProperty("os.name").startsWith("Windows")) runCatching {
                    val process = ProcessBuilder("reg", "query",
                        "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString")
                        .redirectErrorStream(true).start()
                    check(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { "CPU metadata timed out" }
                    check(process.exitValue() == 0)
                    process.inputStream.bufferedReader().readLines().single { "REG_SZ" in it }.substringAfter("REG_SZ").trim()
                }.getOrDefault("unavailable") else "unavailable"
        }
    }
}
