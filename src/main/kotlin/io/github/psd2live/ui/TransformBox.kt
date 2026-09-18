package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/**
 * The transform box, shared by every tool that edits a selection by framing it.
 *
 * Nothing here touches the editor, the viewport or Compose state — a box is a value and a drag is a
 * function of the pointer, so the whole thing is testable without a running canvas.
 */

internal enum class BoundingHandle {
    NONE, BODY, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, ROTATE
}

internal data class BoundingBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val width get() = maxX - minX
    val height get() = maxY - minY
    val rotateHandlePos get() = Offset(centerX, minY - 24f)

    fun contains(p: Offset) = p.x in minX..maxX && p.y in minY..maxY
}

/**
 * Turns a pointer position into the transform frame's own coordinates, and back.
 *
 * The box is always axis-aligned *here*, which is the whole point of the frame: an oriented selection
 * box needs no oriented-rectangle maths anywhere, only this one pair of conversions at the edges.
 */
internal fun Offset.intoTransformFrame(pivot: Offset, angleDeg: Float): Offset = rotateAbout(pivot, -angleDeg)

internal fun Offset.outOfTransformFrame(pivot: Offset, angleDeg: Float): Offset = rotateAbout(pivot, angleDeg)

/** Turns a delta about the origin, for rotating a pointer's travel into the frame. */
internal fun Offset.rotateVector(angleDeg: Float): Offset {
    if (angleDeg == 0f) return this
    val radians = angleDeg * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    return Offset(x * c - y * s, x * s + y * c)
}

internal fun Offset.rotateAbout(pivot: Offset, angleDeg: Float): Offset {
    if (angleDeg == 0f) return this
    val radians = angleDeg * PI.toFloat() / 180f
    val c = cos(radians)
    val s = sin(radians)
    val d = this - pivot
    return pivot + Offset(d.x * c - d.y * s, d.x * s + d.y * c)
}

/**
 * The transform frame: its box, the screen point it is oriented about, and that orientation.
 *
 * [bounds] is in frame coordinates — axis-aligned *inside* the frame — so every consumer of a box (the
 * overlay, the handle hit test, the drag math) needs no oriented-rectangle case at all.
 */
internal data class TransformFrame(val bounds: BoundingBox, val pivot: Offset, val angleDeg: Float)

/**
 * The centroid a selection is framed and turned about.
 *
 * Shared by [frameOf] and the numeric Precise Transform panel, so the box and the panel cannot
 * disagree about the centre: they are the same function, not two that happen to agree.
 *
 * A centroid rather than the hull's centre, because a rotation leaves the centroid where it is — an
 * oriented frame turns in place instead of swinging as its bounding hull changes shape.
 */
internal fun selectionPivot(points: List<Offset>): Offset =
    Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

/**
 * The box hugging [indices] of [points], axis-aligned inside a frame turned by [angleDeg] about their
 * centroid. Null when nothing is selected, which is the only guard the overlay and the hit test need:
 * no selection draws no box and grabs no handle.
 */
internal fun frameOf(points: List<Offset>, indices: Set<Int>, angleDeg: Float): TransformFrame? {
    val chosen = indices.filter { it in points.indices }.map { points[it] }
    if (chosen.isEmpty()) return null
    val pivot = selectionPivot(chosen)
    val local = chosen.map { it.intoTransformFrame(pivot, angleDeg) }
    return TransformFrame(
        BoundingBox(local.minOf { it.x }, local.minOf { it.y }, local.maxOf { it.x }, local.maxOf { it.y }),
        pivot,
        angleDeg,
    )
}

private fun hitBoundingHandle(pos: Offset, bounds: BoundingBox): BoundingHandle {
    if ((pos - bounds.rotateHandlePos).getDistance() <= 9f) return BoundingHandle.ROTATE
    if ((pos - Offset(bounds.minX, bounds.minY)).getDistance() <= 8f) return BoundingHandle.TOP_LEFT
    if ((pos - Offset(bounds.maxX, bounds.minY)).getDistance() <= 8f) return BoundingHandle.TOP_RIGHT
    if ((pos - Offset(bounds.minX, bounds.maxY)).getDistance() <= 8f) return BoundingHandle.BOTTOM_LEFT
    if ((pos - Offset(bounds.maxX, bounds.maxY)).getDistance() <= 8f) return BoundingHandle.BOTTOM_RIGHT
    if (bounds.width >= 20f) {
        if ((pos - Offset(bounds.centerX, bounds.minY)).getDistance() <= 7f) return BoundingHandle.TOP
        if ((pos - Offset(bounds.centerX, bounds.maxY)).getDistance() <= 7f) return BoundingHandle.BOTTOM
    }
    if (bounds.height >= 20f) {
        if ((pos - Offset(bounds.minX, bounds.centerY)).getDistance() <= 7f) return BoundingHandle.LEFT
        if ((pos - Offset(bounds.maxX, bounds.centerY)).getDistance() <= 7f) return BoundingHandle.RIGHT
    }
    return BoundingHandle.NONE
}

/**
 * The handle ring only — BODY is never reported.
 *
 * The point tools need the ring *before* their element pick and the box body *after* it, so the two
 * cannot be one call there.
 */
internal fun transformRingAt(pos: Offset, frame: TransformFrame): BoundingHandle =
    hitBoundingHandle(pos.intoTransformFrame(frame.pivot, frame.angleDeg), frame.bounds)

/**
 * The ring, then BODY for a point anywhere inside the rectangle.
 *
 * [hitBoundingHandle] answers NONE for every point inside the rectangle — it only knows the ring of
 * handles — so a body move has to be resolved here. The object tools want both answers at once, and
 * want them *before* any artwork hit test: a multi-object selection has holes in its box, and a click
 * in one of them is a move, not a re-pick.
 */
internal fun transformHandleAt(pos: Offset, frame: TransformFrame): BoundingHandle {
    val local = pos.intoTransformFrame(frame.pivot, frame.angleDeg)
    val handle = hitBoundingHandle(local, frame.bounds)
    if (handle != BoundingHandle.NONE) return handle
    return if (frame.bounds.contains(local)) BoundingHandle.BODY else BoundingHandle.NONE
}

/** What a drag did: where the box and the frame angle ended up, and where each point belongs now. */
internal sealed interface TransformDragResult {
    val bounds: BoundingBox
    val frameAngle: Float

    /** Where a point that was at [point] when the drag started should be now, in screen coordinates. */
    fun destination(point: Offset): Offset
}

/**
 * A body move. The box travels in the frame's coordinates while the points travel by the raw screen
 * delta — the asymmetry is deliberate: the artwork follows the pointer, the box follows the frame.
 */
internal data class TranslateDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val delta: Offset,
) : TransformDragResult {
    override fun destination(point: Offset) = point + delta
}

/**
 * A rotation. [bounds] is untouched: the frame carries the orientation, so the box inside it never
 * changes shape and the box turns with the pointer without resizing.
 */
internal data class RotateDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val pivot: Offset,
    private val deltaDeg: Float,
) : TransformDragResult {
    override fun destination(point: Offset) = point.rotateAbout(pivot, deltaDeg)
}

/**
 * A scale about one of the eight handles, from [pressBounds] to [bounds].
 *
 * Points are read in the frame and written back out of it, which is what makes dragging a corner of a
 * rotated box stretch along the box rather than along the screen.
 */
internal data class ScaleDrag(
    override val bounds: BoundingBox,
    override val frameAngle: Float,
    private val pressBounds: BoundingBox,
    private val pivot: Offset,
    private val pressAngle: Float,
) : TransformDragResult {
    private val w0 = pressBounds.width.coerceAtLeast(1f)
    private val h0 = pressBounds.height.coerceAtLeast(1f)

    override fun destination(point: Offset): Offset {
        val p0 = point.intoTransformFrame(pivot, pressAngle)
        val u = (p0.x - pressBounds.minX) / w0
        val v = (p0.y - pressBounds.minY) / h0
        return Offset(bounds.minX + u * bounds.width, bounds.minY + v * bounds.height)
            .outOfTransformFrame(pivot, pressAngle)
    }
}

/**
 * One live box drag.
 *
 * Everything it needs is frozen at press, so it is a pure value: the pointer and the live modifiers
 * are the only inputs, and the same instance can be re-applied on every move.
 */
internal class TransformDrag(
    val handle: BoundingHandle,
    val bounds: BoundingBox,
    val pivot: Offset,
    val angleDeg: Float,
    val press: Offset,
) {
    fun apply(pointer: Offset, axis: String?, shift: Boolean, alt: Boolean): TransformDragResult = when (handle) {
        // The axis lock is a screen-space promise — "move horizontally" has to mean the screen's
        // horizontal whatever the frame is turned to — so it constrains the pointer delta before that
        // delta is rotated into the frame. A rotation is measured about the pivot and never sees a
        // delta at all, so the lock cannot reach it.
        BoundingHandle.ROTATE -> rotate(pointer, shift)
        BoundingHandle.BODY -> translate(axisLocked(pointer, axis))
        else -> scale(axisLocked(pointer, axis), shift, alt)
    }

    private fun axisLocked(pointer: Offset, axis: String?) = Offset(
        if (axis == "y") 0f else pointer.x - press.x,
        if (axis == "x") 0f else pointer.y - press.y,
    )

    private fun translate(screenDelta: Offset): TransformDragResult {
        val frameDelta = screenDelta.rotateVector(-angleDeg)
        return TranslateDrag(
            BoundingBox(
                bounds.minX + frameDelta.x, bounds.minY + frameDelta.y,
                bounds.maxX + frameDelta.x, bounds.maxY + frameDelta.y,
            ),
            angleDeg,
            screenDelta,
        )
    }

    private fun rotate(pointer: Offset, shift: Boolean): TransformDragResult {
        // Measured about the frame pivot in screen space, and accumulated onto the angle the frame
        // already had.
        val angle0 = atan2(press.y - pivot.y, press.x - pivot.x)
        val angle1 = atan2(pointer.y - pivot.y, pointer.x - pivot.x)
        var deltaAngle = angle1 - angle0
        if (shift) deltaAngle = (deltaAngle / (PI.toFloat() / 12)).roundToInt() * (PI.toFloat() / 12)
        val deltaDeg = Math.toDegrees(deltaAngle.toDouble()).toFloat()
        return RotateDrag(bounds, angleDeg + deltaDeg, pivot, deltaDeg)
    }

    private fun scale(screenDelta: Offset, shift: Boolean, alt: Boolean): TransformDragResult {
        val d = screenDelta.rotateVector(-angleDeg)
        val dx = d.x
        val dy = d.y

        var newMinX = bounds.minX
        var newMaxX = bounds.maxX
        var newMinY = bounds.minY
        var newMaxY = bounds.maxY

        when (handle) {
            BoundingHandle.RIGHT -> {
                newMaxX = bounds.maxX + dx
                if (alt) newMinX = bounds.minX - dx
            }
            BoundingHandle.LEFT -> {
                newMinX = bounds.minX + dx
                if (alt) newMaxX = bounds.maxX - dx
            }
            BoundingHandle.BOTTOM -> {
                newMaxY = bounds.maxY + dy
                if (alt) newMinY = bounds.minY - dy
            }
            BoundingHandle.TOP -> {
                newMinY = bounds.minY + dy
                if (alt) newMaxY = bounds.maxY - dy
            }
            BoundingHandle.BOTTOM_RIGHT -> {
                newMaxX = bounds.maxX + dx
                newMaxY = bounds.maxY + dy
                if (alt) { newMinX = bounds.minX - dx; newMinY = bounds.minY - dy }
            }
            BoundingHandle.BOTTOM_LEFT -> {
                newMinX = bounds.minX + dx
                newMaxY = bounds.maxY + dy
                if (alt) { newMaxX = bounds.maxX - dx; newMinY = bounds.minY - dy }
            }
            BoundingHandle.TOP_RIGHT -> {
                newMaxX = bounds.maxX + dx
                newMinY = bounds.minY + dy
                if (alt) { newMinX = bounds.minX - dx; newMaxY = bounds.maxY - dy }
            }
            BoundingHandle.TOP_LEFT -> {
                newMinX = bounds.minX + dx
                newMinY = bounds.minY + dy
                if (alt) { newMaxX = bounds.maxX - dx; newMaxY = bounds.maxY - dy }
            }
            else -> Unit
        }

        val w0 = bounds.width.coerceAtLeast(1f)
        val h0 = bounds.height.coerceAtLeast(1f)
        if (shift && handle.isCorner) {
            val factor = maxOf(abs(newMaxX - newMinX) / w0, abs(newMaxY - newMinY) / h0)
            val targetW = w0 * factor
            val targetH = h0 * factor
            when (handle) {
                BoundingHandle.BOTTOM_RIGHT -> { newMaxX = newMinX + targetW; newMaxY = newMinY + targetH }
                BoundingHandle.BOTTOM_LEFT -> { newMinX = newMaxX - targetW; newMaxY = newMinY + targetH }
                BoundingHandle.TOP_RIGHT -> { newMaxX = newMinX + targetW; newMinY = newMaxY - targetH }
                BoundingHandle.TOP_LEFT -> { newMinX = newMaxX - targetW; newMinY = newMaxY - targetH }
                else -> Unit
            }
        }

        // A drag that crosses its own anchor would otherwise hand the box a negative extent, which the
        // scale below turns into mirrored artwork. Clamping to a few pixels keeps the flip out of the
        // drag entirely.
        if (newMaxX - newMinX < MIN_SIZE) {
            if (handle in setOf(BoundingHandle.LEFT, BoundingHandle.TOP_LEFT, BoundingHandle.BOTTOM_LEFT)) newMinX = newMaxX - MIN_SIZE
            else newMaxX = newMinX + MIN_SIZE
        }
        if (newMaxY - newMinY < MIN_SIZE) {
            if (handle in setOf(BoundingHandle.TOP, BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT)) newMinY = newMaxY - MIN_SIZE
            else newMaxY = newMinY + MIN_SIZE
        }

        return ScaleDrag(
            BoundingBox(newMinX, newMinY, newMaxX, newMaxY),
            angleDeg,
            bounds,
            pivot,
            angleDeg,
        )
    }

    private val BoundingHandle.isCorner get() = this in setOf(
        BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT,
        BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT,
    )

    private companion object {
        const val MIN_SIZE = 4f
    }
}
