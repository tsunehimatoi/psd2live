package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.hypot

class FeatureDisplacementTest {
    @Test fun `face socket only indents the left edge near the eyes for negative X`() {
        val eyeY = 0.38f
        fun p(u: Float, v: Float, x: Float) = RigBuilder.faceContourPoint(u, v, x, 1f, eyeY)
        assertEquals(0.018f, p(0f, eyeY, -45f).first, 1e-6f)
        assertEquals(0.009f, p(0f, eyeY, -22.5f).first, 1e-6f)
        for (r in 0..32) for (c in 0..32) {
            val u = c / 32f
            val v = r / 32f
            for (x in listOf(0f, 45f)) assertEquals(u to v, p(u, v, x))
            val deformed = p(u, v, -45f)
            assertEquals(v, deformed.second)
            if (u >= 0.35f || kotlin.math.abs(v - eyeY) >= 0.18f) assertEquals(u, deformed.first)
            if (c < 32) assertTrue(deformed.first < p((c + 1) / 32f, v, -45f).first)
        }
    }

    @Test fun `diagonals rotate three degrees in the requested direction without scaling`() {
        for (aspect in listOf(0.6f, 1f, 1.5f)) {
            for ((yaw, pitch, expectedDegrees) in listOf(
                Triple(-45f, 30f, 3f), Triple(45f, -30f, 3f),
                Triple(45f, 30f, -3f), Triple(-45f, -30f, -3f),
                Triple(0f, 30f, 0f), Triple(-45f, 0f, 0f),
                Triple(-22.5f, 15f, 0.75f),
            )) {
                fun p(u: Float) = RigBuilder.featureDisplacementPoint(u, 0.5f, yaw, pitch, 1f, aspect)
                val left = p(0f)
                val right = p(1f)
                val dx = (right.first - left.first) * aspect
                val dy = right.second - left.second
                assertEquals(expectedDegrees, atan2(dy, dx) * 180f / PI.toFloat(), 1e-4f)
                assertEquals((1f - 0.15f * kotlin.math.abs(yaw / 45f)) * aspect, hypot(dx, dy), 1e-5f)
                val center = p(0.5f)
                assertEquals(0.5f + yaw / 45f * 0.08f, center.first, 1e-6f)
                assertEquals(0.5f + (pitch / 30f).coerceAtLeast(0f) * 0.04f - pitch / 30f * 0.07f, center.second, 1e-6f)
            }
        }
    }

    private fun point(u: Float, v: Float, x: Float, y: Float, strength: Float = 1f) =
        RigBuilder.featureDisplacementPoint(u, v, x, y, strength)

    @Test fun `neutral is identity and either yaw narrows every row by fifteen percent`() {
        for (r in 0..8) for (c in 0..8) {
            val u = c / 8f
            val v = r / 8f
            assertEquals(u, point(u, v, 0f, 0f).first, 1e-6f)
            assertEquals(v, point(u, v, 0f, 0f).second, 1e-6f)
            assertEquals(u, point(u, v, -45f, -30f, 0f).first, 1e-6f)
            for (yaw in listOf(-45f, 45f)) {
                assertEquals(0.85f, point(1f, v, yaw, 0f).first - point(0f, v, yaw, 0f).first, 1e-6f)
            }
        }
    }

    @Test fun `diagonal look superimposes parenthesis and U curves across the lattice`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(point(t, 0.5f, -45f, -30f).first < point(t, 0f, -45f, -30f).first)
            assertTrue(point(0.5f, t, -45f, -30f).second > point(0f, t, -45f, -30f).second)
        }
        val center = point(0.5f, 0.5f, -45f, -30f)
        assertTrue(center.first < 0.5f && center.second > 0.5f)
        for (r in 0..8) for (c in 0..8) {
            val u = c / 8f
            val v = r / 8f
            val left = point(u, v, -45f, -30f)
            val right = point(1f - u, v, 45f, -30f)
            assertEquals(1f - left.first, right.first, 1e-6f)
            assertEquals(left.second, right.second, 1e-6f)
            val upLeft = point(u, v, -45f, 30f)
            val upRight = point(1f - u, v, 45f, 30f)
            assertEquals(1f - upLeft.first, upRight.first, 1e-6f)
            assertEquals(upLeft.second, upRight.second, 1e-6f)
        }
    }

    @Test fun `up compresses overall and upper half while down compresses lower half`() {
        for (u in listOf(0f, 0.25f, 0.5f, 1f)) {
            fun height(a: Float, b: Float, pitch: Float) =
                point(u, b, 0f, pitch).second - point(u, a, 0f, pitch).second
            assertEquals(0.36f, height(0f, 0.5f, 30f), 1e-6f)
            assertEquals(0.46f, height(0.5f, 1f, 30f), 1e-6f)
            assertEquals(0.5f, height(0f, 0.5f, -30f), 1e-6f)
            assertEquals(0.4f, height(0.5f, 1f, -30f), 1e-6f)
            for (pitch in listOf(-30f, -15f, 0f, 15f, 30f)) {
                for (row in 0 until 32) {
                    assertTrue(height(row / 32f, (row + 1) / 32f, pitch) > 0f, "rows must not fold")
                }
            }
        }
    }
}
