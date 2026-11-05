package io.github.psd2live.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.sin

class XpbdSolverTest {
    @Test fun bendingAndChangingPinsMatchTheOriginalSolverExactly() {
        val state = SimState(4).apply { reset(floatArrayOf(0f, 20f, 20f, 20f, 0f, 0f, 20f, 0f)) }
        val pins = floatArrayOf(1f, 1f, 0.1f, 0f)
        val triangles = TriangleConstraints(intArrayOf(0, 1), intArrayOf(2, 2), intArrayOf(1, 3), floatArrayOf(0.001f, 0.001f))
        val bends = BendConstraints(intArrayOf(0), intArrayOf(1), floatArrayOf(0.001f))
        val solver = XpbdSolver(state, triangles = triangles, bends = bends, pinWeight = pins)
        repeat(120) { frame ->
            if (frame == 40) pins[1] = 0.4f
            if (frame == 80) state.invMass[2] = 0f
            for (i in 0..3) {
                state.anchorX[i] = (i % 2) * 20f + sin(frame * 0.1f) * 4f
                state.goalX[i] = state.anchorX[i]
            }
            solver.step(1f / 60f)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.ByteBuffer.allocate(32).apply {
            state.positions().forEach { putFloat(it) }
        }.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
        // Recorded with the original solver; the optimized solver was also compared at every frame.
        assertEquals("f330b9a22e1b0742777d862b4cd46474eeefb21e3133f3495f4c789f74b779fd", hash)
    }
}
