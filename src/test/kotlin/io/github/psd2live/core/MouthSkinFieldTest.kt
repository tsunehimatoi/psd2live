package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthSkinField
import kotlin.math.*
import kotlin.test.*

class MouthSkinFieldTest {
    private val upper = curve(-1f)
    private val lower = curve(1f)
    private fun curve(side: Float) = FloatArray(130) { i ->
        val u = (i / 2) / 64f
        if (i % 2 == 0) 100f * u else side * 25f * sin(PI.toFloat() * u)
    }
    private fun field() = MouthSkinField(upper, lower, Bounds(-30f, -65f, 130f, 65f))

    @Test fun theOriginalDrawingIsTheIdentityOnBothLipSheetsAndTheFace() {
        val posed = field().pose(upper, lower) { _, _ -> 0f to 0f }
        for (x in -20..120 step 7) for (y in -60..60 step 7) for (side in listOf(null, true, false)) {
            val d = posed.displacement(x.toFloat(), y.toFloat(), side) ?: continue
            assertEquals(0f, d.first, 2e-4f)
            assertEquals(0f, d.second, 2e-4f)
        }
    }

    @Test fun aSharedTranslationDoesNotChangeTheShapeOrSplitTheLayers() {
        fun moved(c: FloatArray) = FloatArray(c.size) { i -> c[i] + if (i % 2 == 0) 8f else -3f }
        val posed = field().pose(moved(upper), moved(lower)) { _, _ -> 8f to -3f }
        for (x in -20..120 step 9) for (y in -60..60 step 9) for (side in listOf(null, true, false)) {
            val d = posed.displacement(x.toFloat(), y.toFloat(), side) ?: continue
            assertEquals(8f, d.first, 2e-4f)
            assertEquals(-3f, d.second, 2e-4f)
        }
    }

    @Test fun theTargetRimIsExactWhileOverlappingSkinKeepsItsThickness() {
        val u1 = upper.copyOf(); val l1 = lower.copyOf()
        for (i in 0..64) { u1[i * 2 + 1] *= 0.1f; l1[i * 2 + 1] *= 0.1f }
        val posed = field().pose(u1, l1) { _, _ -> 0f to 0f }
        for (i in 1 until 64) {
            for ((rest, target, side) in listOf(Triple(upper, u1, true), Triple(lower, l1, false))) {
                val d = assertNotNull(posed.displacement(rest[i * 2], rest[i * 2 + 1], side))
                assertEquals(target[i * 2], rest[i * 2] + d.first, 1e-4f)
                assertEquals(target[i * 2 + 1], rest[i * 2 + 1] + d.second, 1e-4f)
            }
        }
        val rim = assertNotNull(posed.displacement(50f, -25f, true))
        val inside = assertNotNull(posed.displacement(50f, -24f, true))
        assertTrue(-24f + inside.second > -25f + rim.second)
    }
}
