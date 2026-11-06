package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.core.modifier.ModifierSourceTransform

/** The ribbon and all of its posed versions share the same path samples and topology. */
class MouthOutlineStroke(columns: List<MouthColumn>, side: Int, thickness: Float) {
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
