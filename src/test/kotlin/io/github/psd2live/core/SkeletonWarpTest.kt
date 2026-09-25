package io.github.psd2live.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

class SkeletonWarpTest {

    @Test
    fun `neutral pose evaluates to identical lattice points`() {
        val rows = 4
        val cols = 2
        val ptsPerRow = cols + 1
        val points = FloatArray((rows + 1) * ptsPerRow * 2)

        // Build a simple regular grid
        var at = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                points[at++] = c * 50f
                points[at++] = r * 100f
            }
        }

        val state = SkeletonWarpState(rows, cols)
        state.initFromLattice(points, rows, cols)

        assertEquals(3, state.joints.size) // rows 0, 2, 4 -> 3 joints
        assertEquals(2, state.bones.size)  // 2 bones

        val evaluated = state.evaluateLattice(rows, cols)
        assertEquals(points.size, evaluated.size)

        for (i in points.indices) {
            assertEquals(points[i], evaluated[i], 0.01f, "Mismatch at index $i")
        }
    }

    @Test
    fun `dragging root joint translates entire lattice`() {
        val rows = 4
        val cols = 2
        val ptsPerRow = cols + 1
        val points = FloatArray((rows + 1) * ptsPerRow * 2)

        var at = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                points[at++] = c * 50f
                points[at++] = r * 100f
            }
        }

        val state = SkeletonWarpState(rows, cols)
        state.initFromLattice(points, rows, cols)

        val root = state.joints[0]
        state.dragJoint(0, root.x + 30f, root.y - 40f)

        val evaluated = state.evaluateLattice(rows, cols)
        for (i in points.indices step 2) {
            assertEquals(points[i] + 30f, evaluated[i], 0.05f)
            assertEquals(points[i + 1] - 40f, evaluated[i + 1], 0.05f)
        }
    }

    @Test
    fun `dragging child joint rotates bone and descendants in FK`() {
        val rows = 4
        val cols = 2
        val ptsPerRow = cols + 1
        val points = FloatArray((rows + 1) * ptsPerRow * 2)

        // Vertical chain: joints at (50, 0), (50, 200), (50, 400)
        var at = 0
        for (r in 0..rows) {
            for (c in 0..cols) {
                points[at++] = c * 50f
                points[at++] = r * 100f
            }
        }

        val state = SkeletonWarpState(rows, cols)
        state.initFromLattice(points, rows, cols)

        val j0 = state.joints[0] // (50, 0)
        val j1 = state.joints[1] // (50, 200)
        val j2 = state.joints[2] // (50, 400)

        assertEquals(50f, j0.x, 0.01f)
        assertEquals(0f, j0.y, 0.01f)
        assertEquals(50f, j1.x, 0.01f)
        assertEquals(200f, j1.y, 0.01f)
        assertEquals(50f, j2.x, 0.01f)
        assertEquals(400f, j2.y, 0.01f)

        // Drag Joint 1 from (50, 200) to (250, 0) -> 90 degree clockwise rotation around J0 (50, 0)
        state.dragJoint(1, 250f, 0f)

        // Joint 1 should be at (250, 0)
        assertEquals(250f, state.joints[1].x, 0.5f)
        assertEquals(0f, state.joints[1].y, 0.5f)

        // Joint 2 should have rotated with Joint 1 (FK propagation) to (450, 0)
        assertEquals(450f, state.joints[2].x, 0.5f)
        assertEquals(0f, state.joints[2].y, 0.5f)

        val evaluated = state.evaluateLattice(rows, cols)
        assertTrue(evaluated.all { it.isFinite() })

        // Check reset
        state.resetPose()
        val resetEval = state.evaluateLattice(rows, cols)
        for (i in points.indices) {
            assertEquals(points[i], resetEval[i], 0.01f)
        }
    }
}
