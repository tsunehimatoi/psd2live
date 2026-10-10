package io.github.psd2live.core

import io.github.psd2live.project.LayerTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/** A drag that only moved or scaled a layer is recorded as exactly that, not as a turn of a few millionths. */
class AffineFitTest {
    /** A mesh's canvas points, far from the origin as on a real canvas, carried through [map] with float noise. */
    private fun points(map: (Float, Float) -> Pair<Float, Float>, noise: Float = 2e-4f): Pair<FloatArray, FloatArray> {
        val before = ArrayList<Float>(); val after = ArrayList<Float>()
        var seed = 7
        for (y in 0..6) for (x in 0..8) {
            val px = 1830.25f + x * 37.5f; val py = 2410.75f + y * 29f
            seed = seed * 1103515245 + 12345
            val jitter = ((seed ushr 16) % 2001 - 1000) / 1000f * noise
            val (qx, qy) = map(px, py)
            before += px; before += py; after += qx + jitter; after += qy - jitter
        }
        return before.toFloatArray() to after.toFloatArray()
    }

    @Test fun aNoisyMoveIsAPlainMove() {
        val (before, after) = points({ x, y -> x + 12.5f to y - 3.25f })
        val fit = assertNotNull(AffineFit.fit(before, after))
        assertEquals(listOf(1f, 0f, 0f, 1f), fit.take(4))
        assertEquals(12.5f, fit[4], 1e-2f); assertEquals(-3.25f, fit[5], 1e-2f)
        assertTrue(LayerTransform.of(fit).isAxisAligned)
    }

    @Test fun aNoisyScaleStaysOnTheAxes() {
        val (before, after) = points({ x, y -> 1.5f * (x - 1900f) + 1900f to 0.75f * (y - 2500f) + 2500f })
        val fit = assertNotNull(AffineFit.fit(before, after))
        assertEquals(0f, fit[1]); assertEquals(0f, fit[2])
        assertEquals(1.5f, fit[0], 1e-4f); assertEquals(0.75f, fit[3], 1e-4f)
        assertTrue(LayerTransform.of(fit).isAxisAligned)
    }

    @Test fun aRealTurnIsKept() {
        val angle = Math.toRadians(3.0)
        val cos = cos(angle).toFloat(); val sin = sin(angle).toFloat()
        val (before, after) = points({ x, y -> cos * (x - 1900f) - sin * (y - 2500f) + 1900f to sin * (x - 1900f) + cos * (y - 2500f) + 2500f })
        val fit = assertNotNull(AffineFit.fit(before, after))
        assertEquals(sin, fit[1], 1e-4f)
        assertFalse(LayerTransform.of(fit).isAxisAligned)
    }

    @Test fun turningBackLeavesNoTurn() {
        fun turn(degrees: Double, px: Float, py: Float): LayerTransform {
            val r = Math.toRadians(degrees); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return LayerTransform(c, s, -s, c, px - (c * px - s * py), py - (s * px + c * py))
        }
        val there = turn(37.0, 812.5f, 1333f).after(LayerTransform(1f, 0f, 0f, 1f, 4.5f, -2f))
        val back = LayerTransform(1f, 0f, 0f, 1f, -4.5f, 2f).after(turn(-37.0, 812.5f, 1333f)).after(there)
        assertEquals(0f, back.b); assertEquals(0f, back.c)
        assertEquals(1f, back.a); assertEquals(1f, back.d)
        assertTrue(back.isAxisAligned)
    }

    @Test fun aStoredTurnOfAFewMillionthsIsNone() {
        assertTrue(LayerTransform(1f, 3e-7f, -2e-7f, 1f, 10f, 5f).isAxisAligned)
        assertFalse(LayerTransform(1f, 1e-3f, -1e-3f, 1f, 10f, 5f).isAxisAligned)
        assertFalse(LayerTransform(-1f, 0f, 0f, 1f, 10f, 5f).isAxisAligned, "a flip is not a move")
    }
}
