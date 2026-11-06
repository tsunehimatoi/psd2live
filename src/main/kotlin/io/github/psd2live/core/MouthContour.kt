package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthContour as Contour

internal typealias MouthColumn = io.github.psd2live.algorithms.binding.MouthColumn

/** Product mesh adapter; contour and ribbon calculations are owned by the binding module. */
internal object MouthContour {
    const val DEFAULT_SEGMENTS = Contour.DEFAULT_SEGMENTS
    fun sampleCurveByArcLength(curve: List<Pair<Float, Float>>, count: Int) = Contour.sampleCurveByArcLength(curve, count)
    fun extractBoundaryLoop(data: MeshData) = Contour.extractBoundaryLoop(data.rigPositions, data.mesh.indices)
    fun splitAndResample(loop: List<Pair<Float, Float>>, segments: Int = DEFAULT_SEGMENTS) = Contour.splitAndResample(loop, segments)
    fun uniformColumns(data: MeshData, segments: Int = DEFAULT_SEGMENTS) = Contour.uniformColumns(data.rigPositions, data.mesh.indices, segments)
    fun overlapCount(columns: Int) = Contour.overlapCount(columns)
    fun crossedPath(columns: List<MouthColumn>, side: Int) = Contour.crossedPath(columns, side)
    @JvmName("crossedPathTriples")
    fun crossedPath(samples: List<Triple<Float, Float, Float>>, side: Int) = Contour.crossedPath(samples, side)
}
