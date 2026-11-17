package example

import io.github.psd2live.algorithms.binding.*
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouthOutlineCenterlineTest {
    private fun rig(width: Float, angle: Float): MouthRig {
        val upper = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            -.23f * sqrt(4f * u * (1f - u)) * (1f + .3f * u)
        }
        val lower = List(MouthFit.SAMPLES) { i ->
            val u = MouthFit.u(i)
            .38f * sqrt(4f * u * (1f - u)) * (1f - .2f * u)
        }
        return MouthRig(MouthFit(17f, -29f, cos(angle), sin(angle), width, upper, lower),
            MouthOptions(exactClose = true), lineWidth = .015f)
    }

    private fun source(rig: MouthRig, upper: Boolean, u: Float): Pair<Float, Float> =
        rig.fit.toCanvas(u, MouthFit.sample(if (upper) rig.fit.upper else rig.fit.lower, u))

    @Test fun closedGeneratedCentersMeetAcrossTheWholeSeamIncludingSteepCorners() {
        for (width in listOf(25f, 100f, 400f)) for (angle in listOf(-.4f, 0f, .7f)) {
            val rig = rig(width, angle)
            for (form in listOf(-1f, -.3f, 0f, .4f, 1f)) for (i in 0..200) {
                val u = i / 200f
                val a = source(rig, true, u); val b = source(rig, false, u)
                val pose = rig.pose(form, 0f)
                val p = rig.strokeCenterPoint(true, a.first, a.second, pose)
                val q = rig.strokeCenterPoint(false, b.first, b.second, pose)
                assertTrue(hypot(p.first - q.first, p.second - q.second) < width * 1e-5f,
                    "closed center gap at width=$width angle=$angle form=$form u=$u: $p / $q")
            }
        }
    }

    @Test fun centerBindingKeepsSourcePixelsAtTheDrawingAndApproachesItContinuously() {
        val rig = rig(100f, .3f)
        for (upper in listOf(false, true)) for (i in 0..100) {
            val s = source(rig, upper, i / 100f)
            // A traced raster may differ slightly from the fitted contour; it is still the source drawing.
            val x = s.first + .07f; val y = s.second - .13f
            val rest = rig.strokeCenterPoint(upper, x, y, rig.pose(0f, 1f))
            assertEquals(x, rest.first, 1e-4f)
            assertEquals(y, rest.second, 1e-4f)
            for (pose in listOf(rig.pose(0f, .99999f), rig.pose(-.00001f, 1f), rig.pose(.00001f, 1f))) {
                val near = rig.strokeCenterPoint(upper, x, y, pose)
                assertTrue(hypot(near.first - x, near.second - y) < .01f)
            }
        }
    }

    @Test fun anIndependentLipPartingControlStillOpensAnExactlyClosedBaseForm() {
        val rig = rig(100f, .3f)
        val upper = source(rig, true, .5f); val lower = source(rig, false, .5f)
        for (form in listOf(-1f, 0f, 1f)) {
            val pose = rig.pose(form, 0f).copy(press = 1f)
            val a = rig.strokeCenterPoint(true, upper.first, upper.second, pose)
            val b = rig.strokeCenterPoint(false, lower.first, lower.second, pose)
            assertTrue(hypot(a.first - b.first, a.second - b.second) > 1f,
                "parting the lips must not be removed with the closed base residual")
        }
    }
}
