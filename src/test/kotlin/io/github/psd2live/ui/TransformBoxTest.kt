package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared transform box: how a selection becomes a box, what a press on the box grabs, and where a
 * drag puts the points it frames.
 *
 * The drag maths moved here out of CanvasEditor's TRANSFORM branch, where it was unreachable from a
 * test. These are behaviour-preservation tests for that move — they were written from the semantics
 * (an anchor stays put, a size scales) rather than from the code, so where the two disagree the code
 * is what is wrong.
 */
class TransformBoxTest {

    private fun assertClose(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance,
            "expected $expected, got $actual",
        )
    }

    private fun assertBox(expected: BoundingBox, actual: BoundingBox, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.minX - actual.minX) <= tolerance && abs(expected.minY - actual.minY) <= tolerance &&
                abs(expected.maxX - actual.maxX) <= tolerance && abs(expected.maxY - actual.maxY) <= tolerance,
            "expected $expected, got $actual",
        )
    }

    // ---- the frame a selection makes ------------------------------------------------------------

    /**
     * The point tools frame exactly the selected points, not the whole mesh they belong to. Framing the
     * whole target is what TRANSFORM does, and it is what would make a box around three vertices claim
     * to be a box around the entire artwork.
     */
    @Test
    fun aFrameHugsOnlyTheSelectedPoints() {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(200f, 300f), Offset(210f, 310f), Offset(400f, 400f))
        val frame = assertNotNull(frameOf(points, setOf(2, 3), 0f))
        assertEquals(200f, frame.bounds.minX, 1e-3f)
        assertEquals(300f, frame.bounds.minY, 1e-3f)
        assertEquals(210f, frame.bounds.maxX, 1e-3f)
        assertEquals(310f, frame.bounds.maxY, 1e-3f)
    }

    /** No selection draws no box and grabs no handle — the single guard the overlay and hit test need. */
    @Test
    fun anEmptySelectionHasNoFrame() {
        assertNull(frameOf(listOf(Offset(0f, 0f), Offset(10f, 10f)), emptySet(), 0f))
    }

    /** Indices that name nothing are skipped rather than crashing, so a stale selection cannot throw. */
    @Test
    fun indicesOutsideTheGeometryAreIgnored() {
        assertNull(frameOf(listOf(Offset(1f, 1f)), setOf(4, 7), 0f))
        assertNotNull(frameOf(listOf(Offset(1f, 1f), Offset(3f, 3f)), setOf(0, 9), 0f))
    }

    /**
     * The box and the numeric panel must turn about the same point, so they share one centroid
     * function. Pinned here because the panel silently disagreeing with the box is a drift no
     * screenshot would show.
     */
    @Test
    fun theFramePivotIsTheCentroidOfTheSelection() {
        val points = listOf(Offset(0f, 0f), Offset(10f, 40f), Offset(20f, 20f))
        val frame = assertNotNull(frameOf(points, setOf(0, 1, 2), 17f))
        assertClose(Offset(10f, 20f), frame.pivot)
        assertClose(selectionPivot(points), frame.pivot)
    }

    /**
     * Turning the artwork and the frame together leaves the box alone — the same invariant
     * TransformFrameTest pins for the raw conversion, restated at the level a caller sees.
     */
    @Test
    fun aTurnedFrameKeepsTheSameBoxForTheSameSelection() {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 20f), Offset(0f, 20f))
        val pivot = selectionPivot(points)
        val before = assertNotNull(frameOf(points, setOf(0, 1, 2, 3), 0f))
        for (angle in listOf(90f, -37f, 180f)) {
            val turned = points.map { it.rotateAbout(pivot, angle) }
            assertBox(before.bounds, assertNotNull(frameOf(turned, turned.indices.toSet(), angle)).bounds)
        }
    }

    // ---- what a press grabs ---------------------------------------------------------------------

    @Test
    fun theRotateHandleIsHitAtItsOwnPosition() {
        val frame = TransformFrame(BoundingBox(0f, 0f, 100f, 50f), Offset.Zero, 0f)
        assertEquals(BoundingHandle.ROTATE, transformRingAt(frame.bounds.rotateHandlePos, frame))
    }

    /** Edge handles are dropped once the box is too small to hold them without overlapping a corner. */
    @Test
    fun theEdgeHandlesDisappearOnASmallBox() {
        // Probes are the edge midpoints, which sit clear of the corners on a box this shape — otherwise
        // the corner would answer first and the test would pass without the guard existing at all.
        val narrow = TransformFrame(BoundingBox(0f, 0f, 19f, 100f), Offset.Zero, 0f)
        assertEquals(BoundingHandle.NONE, transformRingAt(Offset(9.5f, 0f), narrow))
        val short = TransformFrame(BoundingBox(0f, 0f, 100f, 19f), Offset.Zero, 0f)
        assertEquals(BoundingHandle.NONE, transformRingAt(Offset(0f, 9.5f), short))

        // ...and come back the moment the box crosses the threshold.
        val wide = TransformFrame(BoundingBox(0f, 0f, 20f, 100f), Offset.Zero, 0f)
        assertEquals(BoundingHandle.TOP, transformRingAt(Offset(10f, 0f), wide))
        // The corners are always there, however small the box gets.
        assertEquals(BoundingHandle.TOP_LEFT, transformRingAt(Offset(0f, 0f), narrow))
    }

    /**
     * The ring exists so the point tools can test handles *before* their element pick and the body
     * *after* it — a press on a vertex inside the box has to pick that vertex, not move the box.
     */
    @Test
    fun theRingNeverReportsBodyAndTheFullTestDoes() {
        val frame = TransformFrame(BoundingBox(0f, 0f, 100f, 50f), Offset.Zero, 0f)
        val inside = Offset(50f, 25f)
        assertEquals(BoundingHandle.NONE, transformRingAt(inside, frame))
        assertEquals(BoundingHandle.BODY, transformHandleAt(inside, frame))
    }

    /** Outside the rectangle the full test reports nothing, so the tools fall through to their pick. */
    @Test
    fun aPressOutsideTheBoxIsNotABody() {
        val frame = TransformFrame(BoundingBox(0f, 0f, 100f, 50f), Offset.Zero, 0f)
        assertEquals(BoundingHandle.NONE, transformHandleAt(Offset(-40f, 25f), frame))
    }

    /** The hit test reads the press in the frame, so an oriented box is still just a rectangle. */
    @Test
    fun theHitTestReadsThePointInTheFrameNotOnScreen() {
        val frame = TransformFrame(BoundingBox(0f, 0f, 100f, 50f), Offset.Zero, 90f)
        // Frame-local (50, 25); turned 90 degrees that lands at screen (-25, 50), which is outside the
        // axis-aligned rectangle and would be missed by a hit test that forgot the frame.
        assertEquals(BoundingHandle.BODY, transformHandleAt(Offset(-25f, 50f), frame))
    }

    // ---- where a drag puts the points -----------------------------------------------------------

    private fun drag(handle: BoundingHandle, angleDeg: Float = 0f, bounds: BoundingBox = BoundingBox(0f, 0f, 100f, 50f)) =
        TransformDrag(handle, bounds, Offset(50f, 25f), angleDeg, Offset(50f, 25f))

    /**
     * The body move's deliberate asymmetry: the artwork follows the pointer, so it translates by the
     * raw screen delta, while the box translates by that delta rotated into the frame.
     */
    @Test
    fun bodyMoveTranslatesTheBoxAndEveryPointByTheScreenDelta() {
        val angle = 37f
        val delta = Offset(10f, 20f)
        val result = drag(BoundingHandle.BODY, angle).apply(Offset(60f, 45f), null, shift = false, alt = false)

        val frameDelta = delta.rotateVector(-angle)
        assertBox(BoundingBox(0f + frameDelta.x, 0f + frameDelta.y, 100f + frameDelta.x, 50f + frameDelta.y), result.bounds)
        // The point, though, travels with the pointer and never sees the frame.
        assertClose(Offset(150f, 75f), result.destination(Offset(140f, 55f)))
    }

    @Test
    fun bodyMoveKeepsTheBoxSize() {
        val result = drag(BoundingHandle.BODY, 37f).apply(Offset(120f, 90f), null, shift = false, alt = false)
        assertEquals(100f, result.bounds.width, 1e-3f)
        assertEquals(50f, result.bounds.height, 1e-3f)
    }

    /** A rotation turns the frame and leaves the box inside it alone, so the box never resizes. */
    @Test
    fun rotatingTurnsTheFrameAndLeavesTheBoxAlone() {
        val bounds = BoundingBox(0f, 0f, 100f, 50f)
        val pivot = Offset(0f, 0f)
        val d = TransformDrag(BoundingHandle.ROTATE, bounds, pivot, 0f, Offset(10f, 0f))
        val result = d.apply(Offset(0f, 10f), null, shift = false, alt = false)
        assertBox(bounds, result.bounds)
        assertEquals(90f, result.frameAngle, 1e-3f)
    }

    @Test
    fun shiftSnapsARotationToFifteenDegreeSteps() {
        val pivot = Offset.Zero
        val d = TransformDrag(BoundingHandle.ROTATE, BoundingBox(0f, 0f, 100f, 50f), pivot, 0f, Offset(10f, 0f))
        val twenty = 20.0 * kotlin.math.PI / 180.0
        val result = d.apply(Offset((10 * kotlin.math.cos(twenty)).toFloat(), (10 * kotlin.math.sin(twenty)).toFloat()), null, shift = true, alt = false)
        assertEquals(15f, result.frameAngle, 1e-3f)
    }

    /** The axis lock is a screen-space promise about travel, and a rotation is measured about the pivot. */
    @Test
    fun theAxisLockNeverConstrainsARotation() {
        val pivot = Offset(50f, 25f)
        val d = TransformDrag(BoundingHandle.ROTATE, BoundingBox(0f, 0f, 100f, 50f), pivot, 0f, Offset(60f, 25f))
        // The pointer swings a full quarter turn from the press, and the lock does not touch it.
        assertEquals(90f, d.apply(Offset(50f, 45f), "x", shift = false, alt = false).frameAngle, 1e-3f)
        assertEquals(90f, d.apply(Offset(50f, 45f), null, shift = false, alt = false).frameAngle, 1e-3f)
    }

    @Test
    fun aRotationIsRigidAboutThePivot() {
        val pivot = Offset(50f, 25f)
        val point = Offset(140f, 5f)
        val d = TransformDrag(BoundingHandle.ROTATE, BoundingBox(0f, 0f, 100f, 50f), pivot, 0f, Offset(60f, 25f))
        val turned = d.apply(Offset(100f, 25f), null, shift = false, alt = false).destination(point)
        assertEquals(hypot(point.x - pivot.x, point.y - pivot.y), hypot(turned.x - pivot.x, turned.y - pivot.y), 1e-2f)
    }

    /**
     * Each handle stretches from its own anchor. The anchor is the point a user judges the drag by —
     * if it moves, the whole gesture reads as a slide rather than a stretch.
     */
    @Test
    fun eachHandleStretchesFromItsOwnAnchor() {
        val b = BoundingBox(0f, 0f, 100f, 50f)
        val expected = mapOf(
            BoundingHandle.TOP_LEFT to BoundingBox(10f, 20f, 100f, 50f),
            BoundingHandle.TOP_RIGHT to BoundingBox(0f, 20f, 110f, 50f),
            BoundingHandle.BOTTOM_LEFT to BoundingBox(10f, 0f, 100f, 70f),
            BoundingHandle.BOTTOM_RIGHT to BoundingBox(0f, 0f, 110f, 70f),
            BoundingHandle.TOP to BoundingBox(0f, 20f, 100f, 50f),
            BoundingHandle.BOTTOM to BoundingBox(0f, 0f, 100f, 70f),
            BoundingHandle.LEFT to BoundingBox(10f, 0f, 100f, 50f),
            BoundingHandle.RIGHT to BoundingBox(0f, 0f, 110f, 50f),
        )
        expected.forEach { (handle, want) ->
            val got = drag(handle, bounds = b).apply(Offset(60f, 45f), null, shift = false, alt = false)
            assertBox(want, got.bounds)
        }
    }

    @Test
    fun sideHandlesScaleOnlyTheirOwnAxis() {
        listOf(BoundingHandle.TOP, BoundingHandle.BOTTOM).forEach { handle ->
            val got = drag(handle).apply(Offset(60f, 45f), null, shift = false, alt = false)
            assertEquals(0f, got.bounds.minX, 1e-3f)
            assertEquals(100f, got.bounds.maxX, 1e-3f)
        }
        listOf(BoundingHandle.LEFT, BoundingHandle.RIGHT).forEach { handle ->
            val got = drag(handle).apply(Offset(60f, 45f), null, shift = false, alt = false)
            assertEquals(0f, got.bounds.minY, 1e-3f)
            assertEquals(50f, got.bounds.maxY, 1e-3f)
        }
    }

    /** Shift on a corner keeps the box's aspect ratio, so artwork doesn't shear as it grows. */
    @Test
    fun cornerScaleWithShiftKeepsTheAspectRatio() {
        val got = drag(BoundingHandle.BOTTOM_RIGHT).apply(Offset(100f, 75f), null, shift = true, alt = false)
        val free = drag(BoundingHandle.BOTTOM_RIGHT).apply(Offset(100f, 75f), null, shift = false, alt = false)
        assertEquals(100f / 50f, got.bounds.width / got.bounds.height, 1e-3f)
        // Without the modifier the two axes are free, so the aspect genuinely differs.
        assertTrue(abs(got.bounds.width / got.bounds.height - free.bounds.width / free.bounds.height) > 1e-2f)
    }

    @Test
    fun altScalesSymmetricallyAboutTheBoxCentre() {
        val b = BoundingBox(0f, 0f, 100f, 50f)
        val centre = Offset(b.centerX, b.centerY)
        listOf(
            BoundingHandle.RIGHT, BoundingHandle.LEFT, BoundingHandle.TOP, BoundingHandle.BOTTOM,
            BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT, BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT,
        ).forEach { handle ->
            val got = drag(handle, bounds = b).apply(Offset(60f, 45f), null, shift = false, alt = true)
            assertClose(centre, Offset(got.bounds.centerX, got.bounds.centerY), 1e-3f)
        }
    }

    /** A drag past its own anchor clamps instead of handing back a mirrored, negative-extent box. */
    @Test
    fun aScaleNeverCollapsesTheBoxBelowTheMinimumSize() {
        val collapsed = drag(BoundingHandle.RIGHT).apply(Offset(-1000f, 25f), null, shift = false, alt = false)
        assertEquals(4f, collapsed.bounds.width, 1e-3f)
        val tall = drag(BoundingHandle.BOTTOM).apply(Offset(50f, -1000f), null, shift = false, alt = false)
        assertEquals(4f, tall.bounds.height, 1e-3f)
    }

    /**
     * The lock constrains the pointer's screen travel, not the frame's axes — so a box turned 90 degrees
     * still grows along the axis the artwork actually moves on.
     */
    @Test
    fun theAxisLockConstrainsTheDeltaNotTheFrame() {
        val b = BoundingBox(0f, 0f, 100f, 50f)
        val flat = drag(BoundingHandle.BOTTOM_RIGHT, 0f, b).apply(Offset(60f, 95f), "x", shift = false, alt = false)
        assertEquals(0f, flat.bounds.minY, 1e-3f)
        assertEquals(50f, flat.bounds.maxY, 1e-3f)

        // Turned 90 degrees the same screen-horizontal travel grows the box's *height* instead: the
        // artwork still moves horizontally on screen, which is what the user asked for.
        val turned = drag(BoundingHandle.BOTTOM_RIGHT, 90f, b).apply(Offset(60f, 95f), "x", shift = false, alt = false)
        assertTrue(abs(turned.bounds.maxY - 50f) > 1f)
    }

    /** A rotated box stretches along its own axes, not along the screen's. */
    @Test
    fun anOrientedBoxScalesAlongItsOwnAxes() {
        val frame = TransformFrame(BoundingBox(0f, 0f, 30f, 10f), Offset(0f, 0f), 90f)
        // Screen (0, 30) is frame-local (30, 0) — the far edge of the box, on its x axis.
        val point = Offset(0f, 30f)
        val d = TransformDrag(BoundingHandle.RIGHT, frame.bounds, frame.pivot, frame.angleDeg, Offset(0f, 0f))
        val result = d.apply(Offset(0f, 30f), null, shift = false, alt = false)
        // Doubling the box width doubles the point's distance along the frame's x axis, which on screen
        // runs vertically. A screen-axis scale would have moved it to (0, 30) unchanged.
        assertEquals(60f, result.destination(point).y, 1e-2f)
        assertEquals(0f, result.destination(point).x, 1e-2f)
    }

    /**
     * A single-point selection is the common case in mesh editing, and its box is degenerate: the
     * guards turn that into a no-op rather than a NaN or a divide-by-zero, but the scale handles do
     * nothing. Pinned so the behaviour is a known limit rather than a surprise.
     */
    @Test
    fun aDegenerateBoxScalesWithoutNaN() {
        val degenerate = BoundingBox(5f, 5f, 5f, 5f)
        val point = Offset(5f, 5f)
        listOf(
            drag(BoundingHandle.BOTTOM_RIGHT, 0f, degenerate).apply(Offset(25f, 25f), null, shift = false, alt = false),
            drag(BoundingHandle.RIGHT, 0f, degenerate).apply(Offset(-100f, 25f), null, shift = false, alt = false),
        ).forEach { result ->
            assertTrue(result.bounds.minX.isFinite() && result.bounds.maxX.isFinite())
            assertTrue(result.bounds.minY.isFinite() && result.bounds.maxY.isFinite())
            assertTrue(result.destination(point).x.isFinite() && result.destination(point).y.isFinite())
            assertClose(point, result.destination(point), 1e-2f)
        }
    }
}
