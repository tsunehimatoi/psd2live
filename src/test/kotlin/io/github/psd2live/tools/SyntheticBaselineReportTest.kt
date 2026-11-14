package io.github.psd2live.tools

import io.github.psd2live.core.RigBuildProfile
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SyntheticBaselineReportTest {
    private fun directory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("baseline-report-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun summary(directory: File) = Json.parseToJsonElement(directory.resolve("summary.json").readText()).jsonObject
    private fun sample() = RigBuildProfile.Measurement(Unit,
        RigBuildProfile.Operation("task", "revision", "candidate", "edit", 1, 3), 12.0,
        emptyMap(), emptyMap(), RigBuildProfile.Memory(1, 2, 3), RigBuildProfile.Memory(4, 5, 6))

    @Test fun defaultIncompleteReportPreservesSamplesWithoutPercentiles() = directory { directory ->
        SyntheticBaselineReport("test", "unit test", directory).use { report ->
            report.metadata("acceptance_run", "true")
            report.append(sample())
        }
        val result = summary(directory)
        assertFalse(result.getValue("complete").jsonPrimitive.boolean)
        assertEquals(1, result.getValue("observed_samples").jsonPrimitive.int)
        assertFalse("scenarios" in result)
        assertEquals(1, directory.resolve("samples.jsonl").readLines().size)
    }

    @Test fun successfulRunPublishesSummaryOnlyAfterExplicitCompletion() = directory { directory ->
        SyntheticBaselineReport("test", "unit test", directory).use { report ->
            report.append(sample())
            report.complete()
        }
        val result = summary(directory)
        assertTrue(result.getValue("complete").jsonPrimitive.boolean)
        assertEquals(12.0, result.getValue("scenarios").jsonObject.getValue("edit").jsonObject.getValue("p95_ms").jsonPrimitive.double)
        assertFalse("failure" in result)
    }

    @Test fun releaseFailureKeepsContextAndInvalidatesCompletion() = directory { directory ->
        SyntheticBaselineReport("test", "unit test", directory).use { report ->
            report.append(sample())
            report.complete()
            report.context("large/final-release")
            report.failed(IllegalStateException("worker still alive"))
        }
        val result = summary(directory)
        assertFalse(result.getValue("complete").jsonPrimitive.boolean)
        assertFalse("scenarios" in result)
        val failure = result.getValue("failure").jsonObject
        assertEquals("large/final-release", failure.getValue("context").jsonPrimitive.content)
        assertEquals("worker still alive", failure.getValue("message").jsonPrimitive.content)
        assertTrue(failure.getValue("stack_trace").jsonPrimitive.content.contains("IllegalStateException"))
        assertEquals(1, directory.resolve("samples.jsonl").readLines().size)
    }

    @Test fun newRunImmediatelyInvalidatesPreviousSuccessfulSummary() = directory { directory ->
        SyntheticBaselineReport("test", "unit test", directory).use { it.append(sample()); it.complete() }
        assertTrue(summary(directory).getValue("complete").jsonPrimitive.boolean)
        SyntheticBaselineReport("test", "unit test", directory).use {
            val result = summary(directory)
            assertFalse(result.getValue("complete").jsonPrimitive.boolean)
            assertFalse("scenarios" in result)
        }
    }

    @Test fun osMemoryUsesOneBatchAndSeparateOutputWithoutChangingLatency() = directory { directory ->
        val host = ProcessHandle.current().pid()
        val worker = host + 100_000
        val requests = mutableListOf<Set<Long>>()
        SyntheticBaselineReport("test", "unit test", directory, memoryProvider = { pids ->
            requests += pids
            pids.map { SyntheticProcessMemory.Sample(it, true, "controlled", 1234, 5678, "private-committed") }
        }).use { report ->
            report.append(sample())
            report.memory("after-warmup", setOf(worker))
            report.complete()
        }
        assertEquals(listOf(setOf(host, worker)), requests)
        val memory = Json.parseToJsonElement(directory.resolve("memory.jsonl").readLines().single()).jsonObject
        assertEquals("after-warmup", memory.getValue("phase").jsonPrimitive.content)
        val processes = memory.getValue("processes").jsonArray.map { it.jsonObject }
        assertEquals(setOf("host-jvm", "worker"), processes.map { it.getValue("role").jsonPrimitive.content }.toSet())
        assertTrue(processes.all { it.getValue("resident_bytes").jsonPrimitive.long == 1234L })
        val latency = Json.parseToJsonElement(directory.resolve("samples.jsonl").readLines().single()).jsonObject
        assertEquals(12.0, latency.getValue("elapsed_ms").jsonPrimitive.double)
        assertEquals(1, summary(directory).getValue("observed_samples").jsonPrimitive.int)
    }

    @Test fun unavailableMemoryIsExplicitAndExitedWorkersCanBeVerified() = directory { directory ->
        val host = ProcessHandle.current().pid()
        SyntheticBaselineReport("test", "unit test", directory, memoryProvider = { pids ->
            pids.map { SyntheticProcessMemory.Sample(it, it == host, "unavailable", unavailable = "unsupported OS") }
        }).use { report -> report.memory("released", setOf(host + 100_000), expectWorkersExited = true) }
        val row = Json.parseToJsonElement(directory.resolve("memory.jsonl").readLines().single()).jsonObject
        assertTrue(row.getValue("expect_workers_exited").jsonPrimitive.boolean)
        row.getValue("processes").jsonArray.forEach {
            assertEquals(JsonNull, it.jsonObject.getValue("resident_bytes"))
            assertEquals("unsupported OS", it.jsonObject.getValue("unavailable").jsonPrimitive.content)
        }
    }

    @Test fun liveWorkerAfterReleaseLeavesDiagnosticAndPreventsSuccessfulReport() = directory { directory ->
        val host = ProcessHandle.current().pid()
        SyntheticBaselineReport("test", "unit test", directory, memoryProvider = { pids ->
            pids.map { SyntheticProcessMemory.Sample(it, true, "controlled", 100) }
        }).use { report ->
            report.append(sample())
            val failure = assertFailsWith<IllegalStateException> {
                report.memory("released", setOf(host + 100_000), expectWorkersExited = true)
            }
            report.failed(failure)
            assertFailsWith<IllegalStateException> { report.complete() }
        }
        assertEquals(1, directory.resolve("memory.jsonl").readLines().size)
        val result = summary(directory)
        assertFalse(result.getValue("complete").jsonPrimitive.boolean)
        assertFalse("scenarios" in result)
        assertEquals("released", result.getValue("failure").jsonObject.getValue("context").jsonPrimitive.content)
    }
}
