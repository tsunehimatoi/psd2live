package example

import io.github.psd2live.core.modifier.ModifierAlgorithmStages
import java.util.concurrent.CancellationException
import kotlin.test.*

class ModifierAlgorithmStagesTest {
    private fun observing(sink: ((String, Long) -> Unit)?, block: () -> Unit) {
        val previous = ModifierAlgorithmStages.observer
        ModifierAlgorithmStages.observer = sink
        try { block() } finally { ModifierAlgorithmStages.observer = previous }
    }

    @Test fun disabledStagePreservesIdentityAndRunsOnce() = observing(null) {
        val value = Any()
        var calls = 0
        assertSame(value, ModifierAlgorithmStages.stage("disabled") { calls++; value })
        assertEquals(1, calls)
    }

    @Test fun nestedStagesReportCompletionOrderAndNonLocalReturn() {
        val completed = mutableListOf<String>()
        val durations = mutableListOf<Long>()
        observing({ name, elapsed -> durations += elapsed; completed += name }) {
            fun run(): Int = ModifierAlgorithmStages.stage("outer") {
                ModifierAlgorithmStages.stage("inner") { return 7 }
            }
            assertEquals(7, run())
            assertEquals(listOf("inner", "outer"), completed)
            assertTrue(durations.all { it >= 0 })
        }
    }

    @Test fun brokenObserverPreservesResultFailureAndCancellation() = observing({ _, _ -> error("observer") }) {
        val value = Any()
        assertSame(value, ModifierAlgorithmStages.stage("success") { value })
        val failure = IllegalArgumentException("algorithm")
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            ModifierAlgorithmStages.stage("failure") { throw failure }
        })
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            ModifierAlgorithmStages.stage("cancel") { throw cancellation }
        })
    }
}
