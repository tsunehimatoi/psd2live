package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.MouthSkinField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MouthSkinFieldTest {
    @Test
    fun skinPaintInsideTheApertureFollowsItsOwnLipWithoutExtrapolation() {
        val count = 9
        fun rim(upper: Boolean) = FloatArray(count * 2) { index ->
            val u = (index / 2).toFloat() / (count - 1)
            if (index % 2 == 0) 2f * u - 1f
            else (if (upper) -.5f else .5f) * kotlin.math.sin(Math.PI.toFloat() * u)
        }
        val upper = rim(true); val lower = rim(false)
        val field = MouthSkinField(upper, lower, Bounds(-2f, -1.5f, 2f, 1.5f))
        for (open in listOf(0f, .5f, 1f)) {
            fun closed(points: FloatArray) = points.mapIndexed { i, value -> if (i % 2 == 0) value else value * open }.toFloatArray()
            val posed = field.pose(closed(upper), closed(lower)) { _, _ -> 0f to 0f }
            for (x in listOf(-.8f, -.4f, 0f, .4f, .8f)) for (top in listOf(true, false)) {
                val delta = assertNotNull(posed.displacement(x, 0f, top))
                assertEquals(0f, delta.first, 1e-5f)
                val towardSeam = delta.second * if (top) 1f else -1f
                assertTrue(towardSeam >= -1e-5f && towardSeam <= .5f * (1f - open) + 1e-5f,
                    "Paint inside the aperture must stay within its lip's boundary displacement")
                if (open < 1f) assertTrue(towardSeam > .01f, "The paint must actually follow closure")
            }
        }
    }

    @Test
    fun coincidentClosedContoursKeepRestAndFollowTheirBoundary() {
        val contour = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f)
        val field = MouthSkinField(contour, contour, Bounds(-2f, -1f, 2f, 1f))
        val rest = field.pose(contour, contour) { _, _ -> 0f to 0f }
        val moved = contour.mapIndexed { i, value -> value + if (i % 2 == 1) .3f else 0f }.toFloatArray()
        val posed = field.pose(moved, moved) { _, _ -> 0f to .3f }
        for (upper in listOf(true, false)) for (x in listOf(-.7f, 0f, .7f)) {
            // Off-line queries exercise a collapsed nearest-boundary triangle.
            val neutral = assertNotNull(rest.displacement(x, .1f, upper))
            assertEquals(0f, neutral.first, 1e-5f)
            assertEquals(0f, neutral.second, 1e-5f)
            val delta = assertNotNull(posed.displacement(x, .1f, upper))
            assertEquals(0f, delta.first, 1e-5f)
            assertEquals(.3f, delta.second, 1e-5f)
        }
    }

    @Test
    fun repeatedContourVerticesPreserveUniformTranslation() {
        val upper = floatArrayOf(-1f, 0f, -1f, 0f, 0f, -.5f, 1f, 0f)
        val lower = floatArrayOf(-1f, 0f, -1f, 0f, 0f, .5f, 1f, 0f)
        val field = MouthSkinField(upper, lower, Bounds(-2f, -1f, 2f, 1f))
        fun moved(points: FloatArray) = points.mapIndexed { i, value -> value + if (i % 2 == 0) .2f else .3f }.toFloatArray()
        val pose = field.pose(moved(upper), moved(lower)) { _, _ -> .2f to .3f }
        for (side in listOf(true, false, null)) for (x in listOf(-1f, -.5f, 0f, .5f, 1f)) {
            val delta = assertNotNull(pose.displacement(x, 0f, side))
            assertTrue(delta.first.isFinite() && delta.second.isFinite())
            assertEquals(.2f, delta.first, 1e-4f)
            assertEquals(.3f, delta.second, 1e-4f)
        }
    }

    @Test
    fun upperAndLowerLipQueriesStayWithTheirOwnRimNearOverlappingCorners() {
        val count = 17
        val upper = FloatArray(count * 2) { i ->
            val u = (i / 2).toFloat() / (count - 1)
            if (i % 2 == 0) 2f * u - 1f else -0.4f * kotlin.math.sin(Math.PI.toFloat() * u)
        }
        val lower = FloatArray(count * 2) { i ->
            val u = (i / 2).toFloat() / (count - 1)
            if (i % 2 == 0) 2f * u - 1f else 0.6f * kotlin.math.sin(Math.PI.toFloat() * u)
        }
        val field = MouthSkinField(upper, lower, Bounds(-2f, -1.5f, 2f, 1.5f))
        val openU = upper.copyOf()
        val openL = lower.copyOf()
        for (i in 0 until count) {
            openU[i * 2 + 1] -= 0.25f * kotlin.math.sin(Math.PI.toFloat() * i / (count - 1))
            openL[i * 2 + 1] += 0.25f * kotlin.math.sin(Math.PI.toFloat() * i / (count - 1))
        }
        val posed = field.pose(openU, openL) { _, _ -> 0f to 0f }
        // Even if an UPPER_LIP vertex overlaps below the lower rim inside the corners, it must move UP with upper.
        val dUp = assertNotNull(posed.displacement(0f, 0.7f, true))
        assertTrue(dUp.second <= 1e-5f, "UPPER_LIP must not be pulled down by LOWER_LIP: dy=${dUp.second}")
        val dLo = assertNotNull(posed.displacement(0f, -0.5f, false))
        assertTrue(dLo.second >= -1e-5f, "LOWER_LIP must not be pulled up by UPPER_LIP: dy=${dLo.second}")
    }

    @Test
    fun coincidentClosedSeamBuilds2DCageAndFadesSmoothlyToOuterBoundary() {
        val count = 17
        val seam = FloatArray(count * 2) { i ->
            val u = (i / 2).toFloat() / (count - 1)
            if (i % 2 == 0) 2f * u - 1f else 0f
        }
        val openU = seam.copyOf()
        val openL = seam.copyOf()
        for (i in 0 until count) {
            val hump = kotlin.math.sin(Math.PI.toFloat() * i / (count - 1))
            openU[i * 2 + 1] -= 0.4f * hump
            openL[i * 2 + 1] += 0.6f * hump
        }
        val field = MouthSkinField(seam, seam, Bounds(-2f, -1.5f, 2f, 1.5f))
        val posed = field.pose(openU, openL) { _, _ -> 0f to 0f }
        val nearRim = assertNotNull(posed.displacement(0f, 0.05f, false))
        val midChin = assertNotNull(posed.displacement(0f, 0.75f, false))
        val nearBottom = assertNotNull(posed.displacement(0f, 1.45f, false))
        assertTrue(nearRim.second > 0.50f, "Near seam must follow lower rim opening: ${nearRim.second}")
        assertTrue(midChin.second in 0.1f..nearRim.second, "Mid lower lip must interpolate smoothly: ${midChin.second}")
        assertTrue(nearBottom.second < 0.1f, "Outer lower lip must fade smoothly to 0: ${nearBottom.second}")
    }
}
