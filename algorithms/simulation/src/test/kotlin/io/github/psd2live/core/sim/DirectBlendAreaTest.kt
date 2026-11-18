package io.github.psd2live.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DirectBlendAreaTest {
    @Test fun findsInteriorFlipBetweenSafeSampledPoses() {
        // For a triangle whose edges are (-30 + 100u, 40 - 100v) and (-20 + 100v, -30 + 100u).
        val sampled = listOf(0f, .58f, 1f)
        assertTrue(sampled.all { u -> sampled.all { v -> .17f - .6f * u - .6f * v + u * u + v * v > 0f } })

        val result = DirectBlendArea.minimum(.17f, -.6f, -.6f, 1f, 0f, 1f)
        assertEquals(.30f, result.u, 1e-6f)
        assertEquals(.30f, result.v, 1e-6f)
        assertEquals(-.01f, result.value, 1e-6f)
    }

    @Test fun checksEveryEdgeAndAccountsForCrossTerm() {
        val box = DirectBlendArea.Minimum()
        fun check(c10: Float, c01: Float, u: Float, v: Float) {
            assertSame(box, DirectBlendArea.minimum(0f, c10, c01, 1f, .4f, 1f, box))
            assertEquals(u, box.u, 1e-6f)
            assertEquals(v, box.v, 1e-6f)
            assertEquals(c10 * u + c01 * v + u * u + .4f * u * v + v * v, box.value, 1e-6f)
        }
        check(1f, -.6f, 0f, .3f)
        check(-3f, -1f, 1f, .3f)
        check(-.6f, 1f, .3f, 0f)
        check(-1f, -3f, .3f, 1f)
    }

    @Test fun findsCornerOfSaddleAndEdgeOfSingularValley() {
        val saddle = DirectBlendArea.minimum(2f, 1f, -3f, -2f, 4f, -1f)
        assertEquals(0f, saddle.u)
        assertEquals(1f, saddle.v)
        assertEquals(-2f, saddle.value)
        val valley = DirectBlendArea.minimum(0f, -.4f, .4f, 1f, -2f, 1f)
        assertEquals(.2f, valley.u - valley.v, 1e-6f)
        assertEquals(-.04f, valley.value, 1e-6f)
    }
}
