package io.github.psd2live.core

import kotlin.math.*

internal object MouthContour {
    /** Smooth the closed perimeter before dense resampling, including the two corners. */
    fun denseColumns(boundary: List<Triple<Float, Float, Float>>): List<Triple<Float, Float, Float>> {
        if (boundary.size < 2) return boundary
        val ring = (boundary.map { it.first to it.second } + boundary.asReversed().map { it.first to it.third })
            .fold(mutableListOf<Pair<Float, Float>>()) { result, p ->
                if (result.lastOrNull() != p) result.add(p)
                result
            }.also { if (it.size > 1 && it.first() == it.last()) it.removeAt(it.lastIndex) }
        if (ring.size < 4) return boundary
        val smooth = buildList {
            for (i in ring.indices) {
                val a = ring[(i - 1 + ring.size) % ring.size]
                val b = ring[i]
                val c = ring[(i + 1) % ring.size]
                val d = ring[(i + 2) % ring.size]
                val steps = ceil(hypot(c.first - b.first, c.second - b.second) / 0.35f).toInt().coerceIn(4, 64)
                for (step in 0 until steps) {
                    val t = step.toFloat() / steps
                    fun cubic(p0: Float, p1: Float, p2: Float, p3: Float): Float =
                        0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                            (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
                    add(cubic(a.first,b.first,c.first,d.first) to cubic(a.second,b.second,c.second,d.second))
                }
            }
        }
        val left = smooth.minOf { it.first }
        val right = smooth.maxOf { it.first }
        val count = ceil((right - left) / 0.5f).toInt().coerceIn(256, 1024)
        return (0..count).map { column ->
            val x = if (column == count) right else left + (right - left) * column / count
            val ys = buildList {
                for (i in smooth.indices) {
                    val a = smooth[i]
                    val b = smooth[(i + 1) % smooth.size]
                    if (x in minOf(a.first,b.first)..maxOf(a.first,b.first) && abs(a.first-b.first)>0.000001f) {
                        add(a.second+(b.second-a.second)*(x-a.first)/(b.first-a.first))
                    }
                }
            }
            val fallback = smooth.minBy { abs(it.first - x) }.second
            val top = ys.minOrNull() ?: fallback
            val bottom = ys.maxOrNull() ?: fallback
            if (column == 0 || column == count) Triple(x,(top+bottom)*0.5f,(top+bottom)*0.5f)
            else Triple(x,top,bottom)
        }
    }

    fun overlapCount(columns: Int): Int = ((columns - 1) * 0.04f).roundToInt().coerceIn(4, 32).coerceAtMost((columns - 1) / 2)

    /** Each tip continues along the opposite half; neither tip stops at a mouth corner. */
    fun crossedPath(samples: List<Triple<Float, Float, Float>>, side: Int): List<Pair<Float, Float>> {
        val overlap = overlapCount(samples.size)
        fun point(i: Int, half: Int) = samples[i].let { it.first to if (half == 0) it.second else it.third }
        return buildList {
            for (i in overlap downTo 1) add(point(i,1-side))
            for (i in samples.indices) add(point(i,side))
            for (i in samples.lastIndex-1 downTo samples.lastIndex-overlap) add(point(i,1-side))
        }
    }
}
