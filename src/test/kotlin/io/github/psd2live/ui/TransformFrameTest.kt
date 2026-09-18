package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transform frame's geometry.
 *
 * The box is always axis-aligned *inside* the frame, so this pair of conversions is the only place an
 * oriented box is dealt with. Getting it wrong is a silent, cumulative drift — every rotate, move and
 * scale a little further off than the last — so the round trip and the invariants are pinned here.
 */
class TransformFrameTest {

    private val pivot = Offset(120f, 80f)

    private fun assertClose(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun enteringAndLeavingTheFrameIsARoundTrip() {
        val point = Offset(200f, 30f)
        for (angle in listOf(0f, 30f, 90f, -137.5f, 180f, 359f)) {
            assertClose(point, point.intoTransformFrame(pivot, angle).outOfTransformFrame(pivot, angle))
            assertClose(point, point.outOfTransformFrame(pivot, angle).intoTransformFrame(pivot, angle))
        }
    }

    /** The pivot is the one point a rotation must leave alone, or an oriented frame would swing. */
    @Test
    fun thePivotIsFixedUnderAnyRotation() {
        assertClose(pivot, pivot.intoTransformFrame(pivot, 42f))
        assertClose(pivot, pivot.outOfTransformFrame(pivot, 42f))
    }

    /**
     * The frame turns clockwise on screen for a positive angle, matching the overlay's `rotate`, so
     * entering it turns the point the other way. A point below the pivot is the frame's own `+x` when the
     * frame is turned 90 degrees.
     */
    @Test
    fun enteringTheFrameTurnsByTheNegatedAngle() {
        assertClose(Offset(pivot.x + 10f, pivot.y), Offset(pivot.x, pivot.y + 10f).intoTransformFrame(pivot, 90f))
        assertClose(Offset(pivot.x + 10f, pivot.y), Offset(pivot.x, pivot.y - 10f).intoTransformFrame(pivot, -90f))
    }

    /** Rotation is rigid: distances from the pivot survive it, which is what a scale must not disturb. */
    @Test
    fun distancesFromThePivotSurviveTheRoundTrip() {
        val point = Offset(300f, -40f)
        val before = hypot(point.x - pivot.x, point.y - pivot.y)
        val local = point.intoTransformFrame(pivot, 63f)
        assertTrue(abs(hypot(local.x - pivot.x, local.y - pivot.y) - before) < 1e-3f)
    }

    /** A delta rotates about the origin, so it is unaffected by where the frame's pivot sits. */
    @Test
    fun aRotatedDeltaKeepsItsLengthAndItsSign() {
        val delta = Offset(10f, 0f)
        assertClose(Offset(0f, 10f), delta.rotateVector(90f))
        assertClose(delta, delta.rotateVector(0f))
        assertClose(delta, delta.rotateVector(360f))
        for (angle in listOf(17f, 90f, 200f)) {
            val turned = delta.rotateVector(angle)
            assertEquals(delta.getDistance(), turned.getDistance(), 1e-3f)
        }
    }

    /**
     * The bug the frame exists to fix: a rotate must not reshape the box.
     *
     * Turning the artwork and turning the frame are the same turn, so the box measured afterwards is the
     * box measured before — where a fresh axis-aligned hull would come back as a different rectangle and
     * read as "the rotation was reset".
     */
    @Test
    fun turningTheArtworkAndTheFrameTogetherLeavesTheBoxAlone() {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 20f), Offset(0f, 20f))
        val centre = Offset(50f, 10f)
        fun boxOf(pts: List<Offset>, angle: Float): List<Float> {
            val local = pts.map { it.intoTransformFrame(centre, angle) }
            return listOf(
                local.minOf { it.x }, local.minOf { it.y },
                local.maxOf { it.x }, local.maxOf { it.y },
            )
        }

        val before = boxOf(points, 0f)
        for (angle in listOf(90f, -37f, 180f)) {
            val turned = points.map { it.rotateAbout(centre, angle) }
            val after = boxOf(turned, angle)
            before.indices.forEach { assertEquals(before[it], after[it], 1e-3f, "axis $it at $angle degrees") }
        }
    }
}
