package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** Legacy eye motion in head-aligned source pixels, with offsets expressed in the scaffold parent. */
object EyeLegacyMotion {
    val leftOpen = ParameterId("ParamEyeLOpen")
    val rightOpen = ParameterId("ParamEyeROpen")
    val gazeX = ParameterId("ParamEyeBallX")
    val gazeY = ParameterId("ParamEyeBallY")
    val irisForm = ParameterId("ParamEyeBallForm")

    data class Tuning(
        val blinkCorner: Float,
        val blinkDepth: Float,
        val lashSquash: Float,
        val irisSquash: Float,
        val irisStretch: Float,
        val gazeX: Float,
        val gazeY: Float,
    ) {
        init {
            require(listOf(blinkCorner, blinkDepth, lashSquash, irisSquash, irisStretch, gazeX, gazeY).all(Float::isFinite))
        }
    }

    fun blink(
        positions: FloatArray,
        frame: Bounds,
        eye: ScaffoldMesh.Geometry.Eye,
        side: Side,
        faceCenterX: Float,
        checkpoint: () -> Unit = {},
    ): KeyformGrid<MeshDeltaForm> {
        val parameters = if (side == Side.NONE) listOf(leftOpen, rightOpen) else listOf(open(side))
        val axes = parameters.map { KeyformAxis(it, floatArrayOf(0f, 1f)) }
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val closed = FloatArray(positions.size)
        for (i in positions.indices step 2) {
            if (i % 1024 == 0) checkpoint()
            val (x, y) = ScaffoldMesh.eyePoint(positions[i], positions[i + 1], eye)
            closed[i] = (x - positions[i]) / width
            closed[i + 1] = (y - positions[i + 1]) / height
        }
        // Native grids enumerate the last axis first. Open keys preserve the sign of zero.
        val coordinates = if (side == Side.NONE) listOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(1, 1))
            else listOf(intArrayOf(0), intArrayOf(1))
        return KeyformGrid(axes, coordinates.map { coordinate ->
            val deltas = FloatArray(positions.size) { i ->
                val axis = if (side == Side.NONE && positions[i - i % 2] < faceCenterX) 1 else 0
                closed[i] * (1f - coordinate[axis])
            }
            KeyformCell(coordinate, MeshDeltaForm(deltas))
        })
    }

    fun iris(
        positions: FloatArray,
        frame: Bounds,
        iris: ScaffoldMesh.Geometry.Iris,
        checkpoint: () -> Unit = {},
    ): KeyformGrid<MeshDeltaForm> {
        val keys = floatArrayOf(-1f, 0f, 1f)
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        return KeyformGrid(listOf(KeyformAxis(irisForm, keys)), keys.mapIndexed { index, key ->
            val deltas = FloatArray(positions.size)
            for (i in positions.indices step 2) {
                if (i % 1024 == 0) checkpoint()
                val (x, y) = ScaffoldMesh.irisPoint(positions[i], positions[i + 1], iris, key)
                deltas[i] = (x - positions[i]) / width
                deltas[i + 1] = (y - positions[i + 1]) / height
            }
            KeyformCell(intArrayOf(index), MeshDeltaForm(deltas))
        })
    }

    fun gaze(tuning: Tuning): KeyformGrid<WarpLatticeForm> {
        val keys = floatArrayOf(-1f, 0f, 1f)
        return KeyformGrid(listOf(KeyformAxis(gazeX, keys), KeyformAxis(gazeY, keys)), buildList {
            for ((xIndex, x) in keys.withIndex()) for ((yIndex, y) in keys.withIndex()) {
                val points = FloatArray(18)
                for (row in 0..2) for (column in 0..2) {
                    val i = (row * 3 + column) * 2
                    points[i] = column / 2f + x * tuning.gazeX / 100f
                    points[i + 1] = row / 2f - y * tuning.gazeY / 100f
                }
                add(KeyformCell(intArrayOf(xIndex, yIndex), WarpLatticeForm(points)))
            }
        })
    }

    fun closedDrawing(side: Side): KeyformGrid<ChannelValue> {
        val keys = floatArrayOf(0f, 1f)
        return KeyformGrid(listOf(KeyformAxis(open(side), keys)), keys.mapIndexed { index, key ->
            KeyformCell(intArrayOf(index), ChannelValue.Scalar(1f - key))
        })
    }

    private fun open(side: Side) = if (side == Side.LEFT) leftOpen else rightOpen
}
