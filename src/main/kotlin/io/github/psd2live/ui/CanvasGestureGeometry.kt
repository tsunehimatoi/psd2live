package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/** Geometry shared by pointer previews and committed gestures. Distances are screen pixels. */
internal object CanvasGestureGeometry {
    fun project(point: Offset, a: Offset, b: Offset): Float {
        val d = b - a
        val lengthSquared = d.x * d.x + d.y * d.y
        return if (lengthSquared < 1e-8f) 0f else
            (((point.x - a.x) * d.x + (point.y - a.y) * d.y) / lengthSquared).coerceIn(0f, 1f)
    }

    fun segmentDistance(a: Offset, b: Offset, c: Offset, d: Offset): Float {
        fun cross(u: Offset, v: Offset) = u.x * v.y - u.y * v.x
        val ab = b - a
        val cd = d - c
        val denominator = cross(ab, cd)
        if (abs(denominator) > 1e-6f) {
            val t = cross(c - a, cd) / denominator
            val u = cross(c - a, ab) / denominator
            if (t in 0f..1f && u in 0f..1f) return 0f
        }
        return minOf(
            (a - (c + cd * project(a, c, d))).getDistance(),
            (b - (c + cd * project(b, c, d))).getDistance(),
            (c - (a + ab * project(c, a, b))).getDistance(),
            (d - (a + ab * project(d, a, b))).getDistance(),
        )
    }

    fun direction(origin: Offset, pointer: Offset, length: Float? = null, snap: Boolean = false): Offset {
        val d = pointer - origin
        if (d.getDistance() < 1e-5f) return origin
        var angle = atan2(d.y, d.x)
        if (snap) {
            val step = PI.toFloat() / 12f
            angle = round(angle / step) * step
        }
        val radius = length ?: d.getDistance()
        return origin + Offset(cos(angle), sin(angle)) * radius
    }
}
