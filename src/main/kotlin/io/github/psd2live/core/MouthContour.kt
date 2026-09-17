package io.github.psd2live.core

import kotlin.math.*

internal data class MouthColumn(
    val top: Pair<Float, Float>,
    val bottom: Pair<Float, Float>,
) {
    val x: Float get() = (top.first + bottom.first) * 0.5f
    val topY: Float get() = top.second
    val bottomY: Float get() = bottom.second
    val mid: Pair<Float, Float> get() = x to (topY + bottomY) * 0.5f
}

internal object MouthContour {
    const val DEFAULT_SEGMENTS = 24

    /** Samples a piecewise linear 2D polyline uniformly by cumulative arc length. */
    fun sampleCurveByArcLength(curve: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        if (curve.isEmpty()) return emptyList()
        if (curve.size == 1 || count <= 1) return List(count) { curve.first() }
        val dists = FloatArray(curve.size)
        var total = 0f
        for (i in 1 until curve.size) {
            total += hypot(curve[i].first - curve[i - 1].first, curve[i].second - curve[i - 1].second)
            dists[i] = total
        }
        if (total <= 1e-6f) return List(count) { curve.first() }
        return (0 until count).map { i ->
            val t = i.toFloat() / (count - 1)
            val targetDist = t * total
            var seg = 0
            while (seg < dists.size - 2 && dists[seg + 1] < targetDist) {
                seg++
            }
            val segLen = dists[seg + 1] - dists[seg]
            val alpha = if (segLen > 1e-6f) ((targetDist - dists[seg]) / segLen).coerceIn(0f, 1f) else 0f
            val p0 = curve[seg]
            val p1 = curve[seg + 1]
            (p0.first + (p1.first - p0.first) * alpha) to (p0.second + (p1.second - p0.second) * alpha)
        }
    }

    /** Extracts the outer closed boundary polygon from a MeshData in its rest/initial state. */
    fun extractBoundaryLoop(data: MeshData): List<Pair<Float, Float>> {
        val edges = mutableMapOf<Pair<Int, Int>, Int>()
        val indices = data.mesh.indices
        for (i in indices.indices step 3) {
            val a = indices[i]; val b = indices[i + 1]; val c = indices[i + 2]
            for ((u, v) in listOf(minOf(a, b) to maxOf(a, b), minOf(b, c) to maxOf(b, c), minOf(c, a) to maxOf(c, a))) {
                edges[u to v] = (edges[u to v] ?: 0) + 1
            }
        }
        val boundaryEdges = edges.filterValues { it == 1 }.keys
        if (boundaryEdges.isEmpty()) return emptyList()

        val adj = mutableMapOf<Int, MutableList<Int>>()
        for ((u, v) in boundaryEdges) {
            adj.getOrPut(u) { mutableListOf() }.add(v)
            adj.getOrPut(v) { mutableListOf() }.add(u)
        }

        val start = boundaryEdges.flatMap { listOf(it.first, it.second) }
            .minByOrNull { data.rigPositions[it * 2] } ?: return emptyList()

        val loop = mutableListOf<Int>()
        var curr = start
        var prev = -1
        while (true) {
            loop.add(curr)
            val neighbors = adj[curr].orEmpty()
            val next = neighbors.firstOrNull { it != prev && (it != start || loop.size > 2) } ?: break
            if (next == start) break
            prev = curr
            curr = next
            if (loop.size > boundaryEdges.size + 2) break
        }
        if (loop.size < 3) return emptyList()
        return loop.map { data.rigPositions[it * 2] to data.rigPositions[it * 2 + 1] }
    }

    /** Splits a closed perimeter into upper and lower curves and resamples both evenly by arc length. */
    fun splitAndResample(loop: List<Pair<Float, Float>>, segments: Int = DEFAULT_SEGMENTS): List<MouthColumn> {
        if (loop.size < 3) return emptyList()
        val leftIdx = loop.indices.minByOrNull { loop[it].first } ?: 0
        val rotated = (loop.subList(leftIdx, loop.size) + loop.subList(0, leftIdx))
        val rightIdx = rotated.indices.maxByOrNull { rotated[it].first } ?: (rotated.size / 2)
        if (rightIdx <= 0 || rightIdx >= rotated.size) return emptyList()

        val path1 = rotated.subList(0, rightIdx + 1)
        val path2 = (rotated.subList(rightIdx, rotated.size) + listOf(rotated[0])).asReversed()

        // In canvas coordinates, Y points down. Upper lip has smaller average Y.
        val avgY1 = path1.map { it.second }.average()
        val avgY2 = path2.map { it.second }.average()
        val upperCurve = if (avgY1 <= avgY2) path1 else path2
        val lowerCurve = if (avgY1 <= avgY2) path2 else path1

        val sampledUpper = sampleCurveByArcLength(upperCurve, segments + 1)
        val sampledLower = sampleCurveByArcLength(lowerCurve, segments + 1)

        return (0..segments).map { i ->
            val top = sampledUpper[i]
            val bottom = sampledLower[i]
            if (i == 0 || i == segments) {
                val cornerX = (top.first + bottom.first) * 0.5f
                val cornerY = (top.second + bottom.second) * 0.5f
                MouthColumn(cornerX to cornerY, cornerX to cornerY)
            } else {
                MouthColumn(top, bottom)
            }
        }
    }

    /** Uniform columns sampled evenly by arc length along the initial mouth contour. */
    fun uniformColumns(data: MeshData, segments: Int = DEFAULT_SEGMENTS): List<MouthColumn> {
        val loop = extractBoundaryLoop(data)
        if (loop.size >= 3) {
            val cols = splitAndResample(loop, segments)
            if (cols.size >= 2) return cols
        }
        // Fallback: use bounding box of rig positions
        val xs = data.rigPositions.indices.step(2).map { data.rigPositions[it] }
        val ys = (1 until data.rigPositions.size step 2).map { data.rigPositions[it] }
        if (xs.isEmpty() || ys.isEmpty()) return emptyList()
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val midY = (minY + maxY) * 0.5f
        return (0..segments).map { i ->
            val t = i.toFloat() / segments
            val x = minX + (maxX - minX) * t
            if (i == 0 || i == segments) MouthColumn(x to midY, x to midY)
            else MouthColumn(x to minY, x to maxY)
        }
    }

    /** Compatibility adapter for callers expecting Triple<x, topY, bottomY>. */
    fun denseColumns(boundary: List<Triple<Float, Float, Float>>): List<Triple<Float, Float, Float>> {
        if (boundary.size < 2) return boundary
        val loop = (boundary.map { it.first to it.second } + boundary.asReversed().map { it.first to it.third })
            .fold(mutableListOf<Pair<Float, Float>>()) { result, p ->
                if (result.lastOrNull() != p) result.add(p)
                result
            }.also { if (it.size > 1 && it.first() == it.last()) it.removeAt(it.lastIndex) }
        val cols = splitAndResample(loop, DEFAULT_SEGMENTS)
        if (cols.isEmpty()) return boundary
        return cols.map { col ->
            val x = (col.top.first + col.bottom.first) * 0.5f
            Triple(x, col.top.second, col.bottom.second)
        }
    }

    fun overlapCount(columns: Int): Int = ((columns - 1) * 0.08f).roundToInt().coerceIn(2, 6).coerceAtMost((columns - 1) / 2)

    /** Each tip continues along the opposite half; neither tip stops at a mouth corner. */
    fun crossedPath(columns: List<MouthColumn>, side: Int): List<Pair<Float, Float>> {
        val overlap = overlapCount(columns.size)
        fun point(i: Int, half: Int): Pair<Float, Float> =
            if (half == 0) columns[i].top else columns[i].bottom
        return buildList {
            for (i in overlap downTo 1) add(point(i, 1 - side))
            for (i in columns.indices) add(point(i, side))
            for (i in columns.lastIndex - 1 downTo columns.lastIndex - overlap) add(point(i, 1 - side))
        }
    }

    @JvmName("crossedPathTriples")
    fun crossedPath(samples: List<Triple<Float, Float, Float>>, side: Int): List<Pair<Float, Float>> {
        val overlap = overlapCount(samples.size)
        fun point(i: Int, half: Int) = samples[i].let { it.first to if (half == 0) it.second else it.third }
        return buildList {
            for (i in overlap downTo 1) add(point(i, 1 - side))
            for (i in samples.indices) add(point(i, side))
            for (i in samples.lastIndex - 1 downTo samples.lastIndex - overlap) add(point(i, 1 - side))
        }
    }
}
