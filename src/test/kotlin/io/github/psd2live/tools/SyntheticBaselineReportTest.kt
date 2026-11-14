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
}
