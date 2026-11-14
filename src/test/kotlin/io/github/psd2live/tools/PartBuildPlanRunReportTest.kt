package io.github.psd2live.tools

import kotlinx.serialization.json.*
import kotlin.test.*

class PartBuildPlanRunReportTest {
    private val source = PartBuildPlanSource("a".repeat(40), false, false)
    private val formal = PartBuildPlanRunOptions(true, 30, 5, source.revision)
    private fun sample(cache: Boolean, elapsed: Double) = buildJsonObject {
        put("cache", cache); put("elapsed_ms", elapsed)
    }
    private fun filled(): PartBuildPlanRunReport = PartBuildPlanRunReport(formal, source).apply {
        validateStart()
        repeat(5) { warmupCompleted() }
        repeat(30) { append(sample(true, it + 1.0)); append(sample(false, it + 101.0)) }
    }

    @Test fun formalModeRejectsInsufficientCountsAndImplicitBaseline() {
        for (options in listOf(formal.copy(samples = 29), formal.copy(warmups = 4),
            formal.copy(baseline = null), formal.copy(baseline = "abcd1234"))) {
            assertFailsWith<IllegalArgumentException> { options.validate() }
        }
        formal.validate()
    }

    @Test fun formalModeRejectsDirtyUntrackedAndWrongSourceBeforeBuilding() {
        for (invalid in listOf(source.copy(trackedDirty = true), source.copy(untracked = true),
            source.copy(revision = "b".repeat(40)))) {
            assertFailsWith<IllegalStateException> { PartBuildPlanRunReport(formal, invalid).validateStart() }
        }
    }

    @Test fun onlyExplicitCompletionPublishesSeparateCachePercentilesAndExcludesWarmups() {
        val report = filled()
        assertFalse("latency_by_cache_mode" in report.json())
        report.complete(source)
        val result = report.json()
        assertTrue(result.getValue("complete").jsonPrimitive.boolean)
        assertTrue(result.getValue("performance_acceptance").jsonPrimitive.boolean)
        assertEquals(5, result.getValue("warmup_pairs_completed").jsonPrimitive.int)
        assertEquals(60, result.getValue("measurements").jsonArray.size)
        val on = result.getValue("latency_by_cache_mode").jsonObject.getValue("on").jsonObject
        val off = result.getValue("latency_by_cache_mode").jsonObject.getValue("off").jsonObject
        assertEquals(15.5, on.getValue("median_ms").jsonPrimitive.double)
        assertEquals(29.0, on.getValue("p95_ms").jsonPrimitive.double)
        assertEquals(30.0, on.getValue("max_ms").jsonPrimitive.double)
        assertEquals(115.5, off.getValue("median_ms").jsonPrimitive.double)
        assertEquals(129.0, off.getValue("p95_ms").jsonPrimitive.double)
        assertEquals(130.0, off.getValue("max_ms").jsonPrimitive.double)
    }

    @Test fun baselineChangesAndIncompletePairsCannotBecomeSuccessfulReports() {
        for (changed in listOf(source.copy(revision = "b".repeat(40)), source.copy(trackedDirty = true))) {
            val report = filled()
            val failure = assertFailsWith<IllegalStateException> { report.complete(changed) }
            report.failed(failure)
            assertFalse(report.json().getValue("performance_acceptance").jsonPrimitive.boolean)
            assertFalse("latency_by_cache_mode" in report.json())
        }
        val report = PartBuildPlanRunReport(formal, source)
        repeat(5) { report.warmupCompleted() }
        repeat(30) { report.append(sample(true, 1.0)) }
        assertFailsWith<IllegalStateException> { report.complete(source) }
        assertFalse("latency_by_cache_mode" in report.json())
    }

    @Test fun failureAfterCompletionInvalidatesPercentilesButPreservesEvidence() {
        val report = filled()
        report.complete(source)
        report.failed(IllegalStateException("final output failed"))
        val result = report.json()
        assertEquals("failed", result.getValue("status").jsonPrimitive.content)
        assertFalse(result.getValue("complete").jsonPrimitive.boolean)
        assertFalse(result.getValue("performance_acceptance").jsonPrimitive.boolean)
        assertFalse("latency_by_cache_mode" in result)
        assertEquals(60, result.getValue("measurements").jsonArray.size)
        assertEquals("final output failed", result.getValue("failure").jsonObject.getValue("message").jsonPrimitive.content)
    }

    @Test fun smokeRecordsDirtySourceAndHardwareWithoutClaimingFormalAcceptance() {
        val dirty = source.copy(trackedDirty = true)
        val report = PartBuildPlanRunReport(PartBuildPlanRunOptions(false, 1, 0, null), dirty)
        report.validateStart()
        report.append(sample(true, 2.0)); report.append(sample(false, 4.0))
        report.complete(dirty)
        val result = report.json()
        assertTrue(result.getValue("tracked_source_dirty").jsonPrimitive.boolean)
        assertFalse(result.getValue("performance_acceptance").jsonPrimitive.boolean)
        assertEquals("correctness_smoke", result.getValue("run_mode").jsonPrimitive.content)
        assertTrue(result.getValue("hardware").jsonObject.keys.containsAll(setOf("jdk", "cpu", "logical_processors", "max_heap_bytes")))
    }
}
