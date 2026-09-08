package io.github.psd2live.core

import kotlin.math.*

/** Fixed topology ribbon with normal offsets, miter joins and two semicircular end caps. */
internal object MouthStrokeMesh {
    const val CAP_SEGMENTS = 24
    private const val JOIN_SEGMENTS = 24
    private const val EPSILON = 0.00001f

    private fun direction(a: Pair<Float, Float>, b: Pair<Float, Float>): Pair<Float, Float> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = hypot(dx, dy)
        return if (length < EPSILON) 1f to 0f else dx / length to dy / length
    }

    private fun tangents(points: List<Pair<Float, Float>>, i: Int): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val p = points[i]
        fun distinct(j: Int) = hypot(points[j].first - p.first, points[j].second - p.second) > EPSILON
        val before = (i - 1 downTo 0).firstOrNull(::distinct)
        val after = (i + 1 until points.size).firstOrNull(::distinct)
        val incoming = before?.let { direction(points[it], p) }
        val outgoing = after?.let { direction(p, points[it]) }
        return (incoming ?: outgoing ?: (1f to 0f)) to (outgoing ?: incoming ?: (1f to 0f))
    }

    fun positions(points: List<Pair<Float, Float>>, radius: Float, roundJoins: List<Int> = emptyList()): FloatArray {
        require(points.size >= 2)
        val result = FloatArray((points.size * 2 + (CAP_SEGMENTS + 2) * 2 + roundJoins.size * (JOIN_SEGMENTS + 2)) * 2)
        for ((i, p) in points.withIndex()) {
            val (incoming, outgoing) = tangents(points, i)
            val nx = -incoming.second - outgoing.second
            val ny = incoming.first + outgoing.first
            val length = hypot(nx, ny).coerceAtLeast(EPSILON)
            val normalX = if (length <= EPSILON) -outgoing.second else nx / length
            val normalY = if (length <= EPSILON) outgoing.first else ny / length
            // Round corner patches cover sharp turns and reversals without a projecting miter.
            val projection = if (i in roundJoins) 1f else
                (normalX * -incoming.second + normalY * incoming.first).coerceAtLeast(if (roundJoins.isEmpty()) 0.25f else 0.9f)
            val offsetX = normalX * radius / projection
            val offsetY = normalY * radius / projection
            result[i * 4] = p.first - offsetX
            result[i * 4 + 1] = p.second - offsetY
            result[i * 4 + 2] = p.first + offsetX
            result[i * 4 + 3] = p.second + offsetY
        }
        for (end in 0..1) {
            val i = if (end == 0) 0 else points.lastIndex
            val p = points[i]
            val tangent = tangents(points, i).let { if (end == 0) it.second else it.first }
            val base = points.size * 4 + end * (CAP_SEGMENTS + 2) * 2
            result[base] = p.first
            result[base + 1] = p.second
            for (step in 0..CAP_SEGMENTS) {
                val angle = PI * step / CAP_SEGMENTS
                val along = sin(angle).toFloat() * if (end == 0) -1f else 1f
                val normal = cos(angle).toFloat()
                val j = base + (step + 1) * 2
                result[j] = p.first + radius * (tangent.first * along - tangent.second * normal)
                result[j + 1] = p.second + radius * (tangent.second * along + tangent.first * normal)
            }
        }
        for ((join, pointIndex) in roundJoins.withIndex()) {
            val p = points[pointIndex]
            val base = (points.size * 2 + (CAP_SEGMENTS + 2) * 2 + join * (JOIN_SEGMENTS + 2)) * 2
            result[base] = p.first
            result[base + 1] = p.second
            for (step in 0..JOIN_SEGMENTS) {
                val angle = 2.0 * PI * step / JOIN_SEGMENTS
                result[base + (step + 1) * 2] = p.first + radius * cos(angle).toFloat()
                result[base + (step + 1) * 2 + 1] = p.second + radius * sin(angle).toFloat()
            }
        }
        return result
    }

    fun indices(count: Int, roundJoins: List<Int> = emptyList()): IntArray = buildList {
        for (i in 0 until count - 1) {
            val a = i * 2
            addAll(listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2))
        }
        for (end in 0..1) {
            val base = count * 2 + end * (CAP_SEGMENTS + 2)
            for (step in 0 until CAP_SEGMENTS) {
                if (end == 0) addAll(listOf(base, base + step + 2, base + step + 1))
                else addAll(listOf(base, base + step + 1, base + step + 2))
            }
        }
        for (join in roundJoins.indices) {
            val base = count * 2 + (CAP_SEGMENTS + 2) * 2 + join * (JOIN_SEGMENTS + 2)
            for (step in 0 until JOIN_SEGMENTS) addAll(listOf(base, base + step + 2, base + step + 1))
        }
    }.toIntArray()

    /** Coordinates in the independently packed capsule texture, in pixels. */
    fun texturePositions(count: Int, roundJoins: List<Int> = emptyList()): FloatArray {
        val result = FloatArray((count * 2 + (CAP_SEGMENTS + 2) * 2 + roundJoins.size * (JOIN_SEGMENTS + 2)) * 2)
        for (i in 0 until count) for (v in 0..1) {
            val j = i * 4 + v * 2
            result[j] = 20f + 88f * i / (count - 1)
            result[j + 1] = 8f + 16f * v
        }
        for (end in 0..1) {
            val base = count * 4 + end * (CAP_SEGMENTS + 2) * 2
            val centerX = if (end == 0) 10f else 118f
            result[base] = centerX
            result[base + 1] = 16f
            for (step in 0..CAP_SEGMENTS) {
                val angle = PI * step / CAP_SEGMENTS
                val j = base + (step + 1) * 2
                result[j] = centerX + 8f * sin(angle).toFloat() * if (end == 0) -1f else 1f
                result[j + 1] = 16f + 8f * cos(angle).toFloat()
            }
        }
        for (join in roundJoins.indices) {
            val base = (count * 2 + (CAP_SEGMENTS + 2) * 2 + join * (JOIN_SEGMENTS + 2)) * 2
            result[base] = 10f
            result[base + 1] = 42f
            for (step in 0..JOIN_SEGMENTS) {
                val angle = 2.0 * PI * step / JOIN_SEGMENTS
                result[base + (step + 1) * 2] = 10f + 8f * cos(angle).toFloat()
                result[base + (step + 1) * 2 + 1] = 42f + 8f * sin(angle).toFloat()
            }
        }
        return result
    }
}
