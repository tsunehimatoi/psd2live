package io.github.psd2live.core.fit

import kotlin.math.abs

/** Fits the turning-side forehead edge in the band measured between the skin top and eye line. */
internal class UpperFaceTurn private constructor(
    private val top: Float,
    private val height: Float,
    private val centre: Float,
    private val leftReach: Float,
    private val rightReach: Float,
    private val leftWeight: Float,
    private val rightWeight: Float,
) {
    private val width = leftReach + rightReach

    fun shift(yaw: Float, pitch: Float, turnStrength: Float, lookStrength: Float): Float {
        val turn = (abs(yaw) / FaceTurn.TURN_END).coerceAtMost(1f) * turnStrength
        val look = (pitch / FaceTurn.LOOK_END * lookStrength).coerceIn(-1f, 1f)
        // Mature skin outlines already recover part of the yaw excess in their low corner keys.
        val cornerShare = if (look < 0f) 1f + .45f * look else 1f - look
        return .122f * width * turn * cornerShare
    }

    fun weight(x: Float, y: Float, outward: Float): Float {
        val level = (y - top) / height
        if (level <= 0f || level >= 1f) return 0f
        val reach = if (outward > 0f) rightReach else leftReach
        val coverage = if (outward > 0f) rightWeight else leftWeight
        val side = (x - centre) * outward / reach
        if (side <= 0f) return 0f
        // Let the measured silhouette define the height and reach. Avoid a single noisy curvature peak.
        val forehead = ease(level / .32f) * ease((1f - level) / .48f)
        return forehead * ease(side) * coverage
    }

    companion object {
        fun measure(marks: FaceLandmarks, eyeY: Float, centre: Float): UpperFaceTurn? {
            val top = marks["face.top"]?.y ?: return null
            // Broad hair coverage already accommodates the styled turn. Missing coverage can be an intentional opening.
            fun cover(side: String) = marks.measures["face.upperCover.$side"]?.let { ((.15f - it) / .055f).coerceIn(0f, 1f) } ?: 0f
            val leftWeight = cover("R")
            val rightWeight = cover("L")
            if (leftWeight == 0f && rightWeight == 0f) return null
            val height = eyeY - top
            if (height <= 1f) return null
            val y = top + height * .46f
            val contour = marks.contour
            val edges = contour.indices.mapNotNull { i ->
                val (ax, ay) = contour[i]
                val (bx, by) = contour[(i + 1) % contour.size]
                if ((ay > y) == (by > y)) null else ax + (bx - ax) * (y - ay) / (by - ay)
            }
            val left = centre - (edges.minOrNull() ?: return null)
            val right = (edges.maxOrNull() ?: return null) - centre
            if (left <= 1f || right <= 1f) return null
            return UpperFaceTurn(top, height, centre, left, right, leftWeight, rightWeight)
        }

        private fun ease(progress: Float): Float {
            val t = progress.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
