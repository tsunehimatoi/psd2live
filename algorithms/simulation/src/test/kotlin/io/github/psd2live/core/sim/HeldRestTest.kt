package io.github.psd2live.core.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HeldRestTest {
    @Test fun heldPosesAndMovementAfterAResetMatchUncachedRest() {
        fun solver(cache: Boolean) = XpbdSolver(
            SimState(4),
            stretch = DistanceConstraints(intArrayOf(0, 1), intArrayOf(1, 2), floatArrayOf(20f, 20f), floatArrayOf(.001f, .001f)),
            triangles = TriangleConstraints(intArrayOf(0, 1), intArrayOf(2, 2), intArrayOf(1, 3), floatArrayOf(.001f, .001f)),
            bends = BendConstraints(intArrayOf(0), intArrayOf(1), floatArrayOf(.001f)),
            pinWeight = floatArrayOf(1f, .4f, 0f, 0f),
        ).apply { fixedRestTopology = cache }
        val reference = solver(false)
        val cached = solver(true)
        val rest = floatArrayOf(0f, 20f, 20f, 20f, 0f, 0f, 20f, 0f)
        repeat(24) { frame ->
            for (solver in listOf(reference, cached)) {
                val state = solver.state
                if (frame == 0 || frame == 15) state.reset(rest)
                val displacement = when (frame) {
                    in 4..7 -> (frame - 3) * 3f
                    in 8..14 -> 12f
                    else -> 0f
                }
                state.goalX[1] = rest[2] + displacement
                state.anchorX[1] = state.goalX[1]
                solver.step(1f / 60f)
            }
            assertContentEquals(reference.state.positions(), cached.state.positions(), "frame $frame")
        }
    }
}
