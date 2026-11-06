package example

import io.github.psd2live.algorithms.binding.MouthFit
import io.github.psd2live.algorithms.binding.MouthOptions
import io.github.psd2live.algorithms.binding.MouthRig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FittedMouthKernelTest {
    @Test
    fun fittedMouthEvaluatesWithTheModelBundledInBinding() {
        val upper = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            -0.8f * u * (1f - u)
        }
        val lower = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            1.2f * u * (1f - u)
        }
        val fit = MouthFit(0f, 0f, 1f, 0f, 100f, upper, lower)
        val rig = MouthRig(fit, MouthOptions())

        val (closedUpper, closedLower) = rig.edges(rig.pose(0f, 0f), 0.5f)
        val (openUpper, openLower) = rig.edges(rig.pose(0f, 1f), 0.5f)

        assertEquals(closedUpper, closedLower, 1e-5f)
        assertTrue(openLower - openUpper > 0.4f)
    }
}
