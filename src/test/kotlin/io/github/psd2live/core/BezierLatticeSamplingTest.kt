package io.github.psd2live.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BezierLatticeSamplingTest {
    @Test
    fun initFromLatticeSamplesExactNormalizedPosition() {
        val rows = 5
        val cols = 5
        val lattice = FloatArray((rows + 1) * (cols + 1) * 2)
        for (r in 0..rows) for (c in 0..cols) {
            val i = (r * (cols + 1) + c) * 2
            lattice[i] = c.toFloat()
            lattice[i + 1] = r.toFloat()
        }
        val state = BezierDeformerState(2, 2)
        state.initFromLattice(lattice, rows, cols)
        assertEquals(2.5f, state.anchors[1 to 0]!!.y, 1e-5f)
        assertEquals(2.5f, state.anchors[1 to 1]!!.y, 1e-5f)
        assertEquals(2.5f, state.anchors[1 to 1]!!.x, 1e-5f)
    }

    @Test
    fun identityRectangleRoundTripsWithoutUpwardBias() {
        val rows = 5
        val cols = 7
        val x = -40f
        val y = 10f
        val w = 80f
        val h = 60f
        val lattice = FloatArray((rows + 1) * (cols + 1) * 2)
        for (r in 0..rows) for (c in 0..cols) {
            val i = (r * (cols + 1) + c) * 2
            lattice[i] = x + c * w / cols
            lattice[i + 1] = y + r * h / rows
        }
        val state = BezierDeformerState(2, 2)
        state.initFromLattice(lattice, rows, cols)
        val out = state.evaluateLattice(rows, cols)
        var maxErr = 0f
        for (i in lattice.indices) maxErr = maxOf(maxErr, abs(lattice[i] - out[i]))
        assertTrue(maxErr < 0.05f, "identity round-trip err=$maxErr")
    }
}
