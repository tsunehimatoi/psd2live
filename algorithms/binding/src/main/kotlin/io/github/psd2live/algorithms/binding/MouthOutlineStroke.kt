package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.core.modifier.ModifierSourceTransform

/** The ribbon and all of its posed versions share the same path samples and topology. */
class MouthOutlineStroke(columns: List<MouthColumn>, val side: Int, thickness: Float) {
    init {
        require(columns.size >= 2 && side in 0..1 && thickness.isFinite()) { "Invalid outline contour" }
    }

    private val columnCount = columns.size
    private val start = MouthContour.overlapCount(columnCount)
    private val end = start + columns.lastIndex
    private val joins = listOf(start, end)
    private val path = MouthContour.crossedPath(columns, side)
    val radius = thickness.coerceIn(0.5f, 8f) * 0.5f

    fun indices(): IntArray = MouthStrokeMesh.indices(path.size, joins)

    fun positions(): FloatArray = MouthStrokeMesh.positions(path, radius, joins)

    fun parentPositions(frame: ModifierSourceBounds): FloatArray = inParent(positions(), frame)

    fun delta(
        frame: ModifierSourceBounds,
        restPositions: FloatArray,
        transformPoint: (Float, Float) -> Pair<Float, Float>,
    ): FloatArray {
        val posedPath = path.map { (x, y) -> transformPoint(x, y) }
        val posed = inParent(MouthStrokeMesh.positions(posedPath, radius, joins), frame)
        require(restPositions.size == posed.size)
        return FloatArray(posed.size) { posed[it] - restPositions[it] }
    }

    /** Deforms the ribbon by moving its centerline (with opposite-lip overlap tips) and rotating rest offsets smoothly. */
    fun ribbonDelta(
        frame: ModifierSourceBounds,
        restPositions: FloatArray,
        transformCenter: (upper: Boolean, x: Float, y: Float) -> Pair<Float, Float>,
    ): FloatArray {
        val raw = positions()
        require(raw.size == restPositions.size)
        val posedCenters = path.mapIndexed { i, (x, y) ->
            val onMain = i in start..end
            val upper = if (onMain) side == 0 else side != 0
            transformCenter(upper, x, y)
        }
        fun unitTangent(points: List<Pair<Float, Float>>, i: Int): Pair<Float, Float>? {
            val a = points[(i - 1).coerceAtLeast(0)]
            val b = points[(i + 1).coerceAtMost(points.lastIndex)]
            val dx = b.first - a.first; val dy = b.second - a.second
            val len = kotlin.math.hypot(dx, dy)
            return if (len > 1e-4f) (dx / len to dy / len) else null
        }
        val rotations = path.indices.map { i ->
            val t0 = unitTangent(path, i) ?: (1f to 0f)
            val t1 = unitTangent(posedCenters, i) ?: t0
            // cos = t0 . t1, sin = t0 x t1
            (t0.first * t1.first + t0.second * t1.second) to (t0.first * t1.second - t0.second * t1.first)
        }
        val posed = FloatArray(raw.size)
        fun writeVertex(vertexIndex: Int, pathIndex: Int) {
            val rx = raw[vertexIndex * 2]; val ry = raw[vertexIndex * 2 + 1]
            val p = path[pathIndex]; val q = posedCenters[pathIndex]
            val ox = rx - p.first; val oy = ry - p.second
            val (c, s) = rotations[pathIndex]
            val wx = q.first + ox * c - oy * s
            val wy = q.second + ox * s + oy * c
            posed[vertexIndex * 2] = normalize(wx, frame.left, frame.right) - restPositions[vertexIndex * 2]
            posed[vertexIndex * 2 + 1] = normalize(wy, frame.top, frame.bottom) - restPositions[vertexIndex * 2 + 1]
        }
        var v = 0
        for (i in path.indices) {
            writeVertex(v++, i)
            writeVertex(v++, i)
        }
        val capCount = MouthStrokeMesh.CAP_SEGMENTS + 2
        repeat(capCount) { writeVertex(v++, 0) }
        repeat(capCount) { writeVertex(v++, path.lastIndex) }
        val joinCount = raw.size / 2 - v
        if (joinCount > 0) {
            val perJoin = joinCount / joins.size
            for ((j, pathIndex) in joins.withIndex()) {
                val count = if (j == joins.lastIndex) raw.size / 2 - v else perJoin
                repeat(count) { writeVertex(v++, pathIndex) }
            }
        }
        return posed
    }

    /** The atlas packer later rebinds these local pixel coordinates to the generated tile. */
    fun texturePixels(art: MouthOutlineRaster.Result, toCanvas: ModifierSourceTransform): FloatArray =
        positions().also { points ->
            for (i in points.indices step 2) {
                val (x, y) = toCanvas.map(points[i], points[i + 1])
                points[i] = (x - art.left).coerceIn(0f, art.width.toFloat())
                points[i + 1] = (y - art.top).coerceIn(0f, art.height.toFloat())
            }
        }

    data class Handle(val x: Float, val y: Float, val corner: Boolean)

    fun handles(frame: ModifierSourceBounds): List<Handle> {
        if (columnCount < 5) return emptyList()
        return (0..8).map { sample ->
            val index = start + (sample * (end - start) + 4) / 8
            val (x, y) = path[index]
            Handle(normalize(x, frame.left, frame.right), normalize(y, frame.top, frame.bottom),
                corner = index == start || index == end)
        }
    }

    private fun inParent(points: FloatArray, frame: ModifierSourceBounds) =
        FloatArray(points.size) { i ->
            if (i % 2 == 0) normalize(points[i], frame.left, frame.right)
            else normalize(points[i], frame.top, frame.bottom)
        }

    private fun normalize(value: Float, min: Float, max: Float) = (value - min) / (max - min).coerceAtLeast(1e-4f)
}
