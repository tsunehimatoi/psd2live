package io.github.psd2live.core

import java.util.concurrent.Executors
import kotlin.test.*

class RigBuildProfileTest {
    private fun recording(block: () -> Unit) {
        val enabled = RigBuildProfile.recording
        RigBuildProfile.recording = true
        try { block() } finally { RigBuildProfile.recording = enabled; RigBuildProfile.reset() }
    }
    private fun identity(id: String) = RigBuildProfile.Operation(id, "revision-7", "candidate-8", "edit", 2, 6)

    @Test fun samplesAreIsolatedAndKeepLegacyCounters() = recording {
        RigBuildProfile.reset()
        val first = RigBuildProfile.measure(identity("first")) {
            RigBuildProfile.stage("convert") { RigBuildProfile.count("bytes", 19) }
            RigBuildProfile.gauge("cache", 29)
            "value"
        }
        val second = RigBuildProfile.measure(identity("second")) { RigBuildProfile.count("bytes", 3) }
        assertEquals("value", first.value)
        assertEquals(identity("first"), first.operation)
        assertEquals(19L, first.stages.getValue("bytes").second)
        assertEquals(29L, first.gauges["cache"])
        assertEquals(3L, second.stages.getValue("bytes").second)
        assertFalse("convert" in second.stages)
        assertEquals(22L, RigBuildProfile.snapshot().getValue("bytes").second)
        assertTrue(first.after.heapUsed >= 0)
    }

    @Test fun executorPropagationDoesNotLeakToNextOperation() = recording {
        Executors.newSingleThreadExecutor().use { executor ->
            val first = RigBuildProfile.measure(identity("first")) {
                val work = RigBuildProfile.propagate { RigBuildProfile.stage("worker") { RigBuildProfile.count("sent", 8) } }
                executor.submit<Long> { work() }.get()
            }
            val second = RigBuildProfile.measure(identity("second")) {
                executor.submit<Long> { RigBuildProfile.count("unrelated", 2) }.get()
            }
            assertEquals(8L, first.stages.getValue("sent").second)
            assertEquals(1L, first.stages.getValue("worker").second)
            assertTrue(second.stages.isEmpty())
        }
    }

    @Test fun failureRestoresEnclosingContext() = recording {
        val sample = RigBuildProfile.measure(identity("outer")) {
            assertFailsWith<IllegalStateException> {
                RigBuildProfile.measure(identity("inner")) { error("candidate rejected") }
            }
            RigBuildProfile.count("after failure")
        }
        assertEquals(1L, sample.stages.getValue("after failure").second)
    }

    @Test fun coroutineBuildStagesKeepOperationIdentity() = recording {
        kotlinx.coroutines.runBlocking {
            val result = RigBuildProfile.measureSuspending(identity("suspending")) {
                kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
                    RigBuildProfile.stage("build") { RigBuildProfile.count("mesh", 2) }
                }
            }
            assertEquals(2L, result.stages.getValue("mesh").second)
            assertEquals(1L, result.stages.getValue("build").second)
        }
    }
}
