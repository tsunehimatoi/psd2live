package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Brush shape types supported by the canvas deformer brushes (BRUSH, SMOOTH, INFLATE).
 */
enum class BrushShape(val labelKey: String) {
    CIRCLE("editor.brushShape.circle"),
    LINE("editor.brushShape.line"),
    RECTANGLE("editor.brushShape.rectangle"),
}

/**
 * Returns true if [p] is within the influence perimeter of the brush along the stroke segment [from] -> [to].
 */
internal fun isPointInBrush(
    p: Offset,
    from: Offset,
    to: Offset,
    radius: Float,
    shape: BrushShape,
    angleDeg: Float = 0f,
    aspect: Float = 1f,
    hardness: Float = 0f,
): Boolean {
    return computeBrushWeight(p, from, to, radius, hardness, shape, angleDeg, aspect) > 0f
}

/**
 * Computes the falloff weight [0.0..1.0] for point [p] given brush shape, orientation, radius, and hardness.
 * Uses realistic brush core-and-Euclidean-falloff dynamics matching the standard circular brush.
 */
internal fun computeBrushWeight(
    p: Offset,
    from: Offset,
    to: Offset,
    radius: Float,
    hardness: Float,
    shape: BrushShape,
    angleDeg: Float = 0f,
    aspect: Float = 1f,
): Float {
    if (radius <= 0.01f) return 0f

    return when (shape) {
        BrushShape.CIRCLE -> {
            val d = distanceToSegment(p, from, to)
            if (d > radius) 0f else brushWeight(d, radius, hardness)
        }

        BrushShape.LINE -> {
            // Infinite line passing through the stroke with orientation angleDeg.
            // Normal unit vector perpendicular to the line: (-sinA, cosA).
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val signedTo = -(p.x - to.x) * sinA + (p.y - to.y) * cosA
            val signedFrom = -(p.x - from.x) * sinA + (p.y - from.y) * cosA

            // If point is between the two swept lines, distance is 0; otherwise min distance to either line
            val d = if (signedTo * signedFrom <= 0f) 0f else min(abs(signedTo), abs(signedFrom))

            if (d > radius) 0f else brushWeight(d, radius, hardness)
        }

        BrushShape.RECTANGLE -> {
            // Real brush hardness effect:
            // Solid rectangular core of dimensions [coreW, coreH] with 100% influence,
            // surrounded by an isotropic Euclidean falloff band of width [falloff] smoothly fading via cubic Hermite.
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val h = hardness.coerceIn(0f, 0.95f)
            val halfW = radius.coerceAtLeast(1f)
            val halfH = (radius * aspect.coerceIn(0.1f, 10f)).coerceAtLeast(1f)

            val coreW = halfW * h
            val coreH = halfH * h
            val falloff = (halfW * (1f - h)).coerceAtLeast(0.001f)

            // Euclidean distance to the rectangular core boundary
            fun distToCore(center: Offset): Float {
                val delta = p - center
                val lx = abs(delta.x * cosA + delta.y * sinA)
                val ly = abs(-delta.x * sinA + delta.y * cosA)
                val dx = max(lx - coreW, 0f)
                val dy = max(ly - coreH, 0f)
                return sqrt(dx * dx + dy * dy)
            }

            val dTo = distToCore(to)
            val dFrom = distToCore(from)
            val dMid = distToCore((from + to) * 0.5f)
            val d = min(dTo, min(dFrom, dMid))

            if (d <= 0f) 1f
            else if (d > falloff) 0f
            else {
                val x = (d / falloff).coerceIn(0f, 1f)
                1f - x * x * (3f - 2f * x)
            }
        }
    }
}
