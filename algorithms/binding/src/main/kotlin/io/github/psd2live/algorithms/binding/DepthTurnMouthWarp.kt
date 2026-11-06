package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/** The original mouth warp's correction, between the legacy face's nine keys. */
data class DepthTurnMouthWarp(
    val region: Bounds, val parentFrame: Bounds, val yawEase: Float, val pitchEase: Float,
    val featurePlane: Float, val nearLag: Float, val cornerLag: Float, val shift: Float,
    val arch: Float, val tilt: Float,
) {
    init {
        require(yawEase > 0f && pitchEase > 0f)
        require(listOf(yawEase, pitchEase, featurePlane, nearLag, cornerLag, shift, arch, tilt).all(Float::isFinite))
        require(listOf(region, parentFrame).all {
            it.width > 0f && it.height > 0f && listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite)
        })
    }

    fun place(warp: Deformer.Warp, head: HeadTurn, centerX: Float, radiusX: Float, strength: Float,
              checkpoint: () -> Unit): Deformer.Warp {
        val legacyYaw = floatArrayOf(-45f, 0f, 45f)
        val legacyPitch = floatArrayOf(-30f, 0f, 30f)
        val parentWidth = parentFrame.width.coerceAtLeast(1e-4f)
        val parentHeight = parentFrame.height.coerceAtLeast(1e-4f)
        // Use the same affine terms as featureWarp: the subtraction happens before division.
        val left = (region.left - parentFrame.left) / parentWidth
        val top = (region.top - parentFrame.top) / parentHeight
        val width = (region.right - parentFrame.left) / parentWidth - left
        val height = (region.bottom - parentFrame.top) / parentHeight - top
        val cells = buildList {
            for ((xi, yaw) in HeadTurn.YAW_KEYS.withIndex()) for ((yi, pitch) in HeadTurn.PITCH_KEYS.withIndex()) {
                checkpoint()
                val (i, s) = bracket(legacyYaw, yaw)
                val (j, t) = bracket(legacyPitch, pitch)
                val points = FloatArray((warp.columns + 1) * (warp.rows + 1) * 2)
                var point = 0
                for (row in 0..warp.rows) for (column in 0..warp.columns) {
                    val u = column.toFloat() / warp.columns
                    val v = row.toFloat() / warp.rows
                    var x = 0f
                    var y = 0f
                    for ((a, wa) in listOf(i to 1f - s, i + 1 to s)) for ((b, wb) in listOf(j to 1f - t, j + 1 to t)) {
                        if (wa * wb == 0f) continue
                        val (ox, oy) = offset(u, v, legacyYaw[a], legacyPitch[b], centerX, radiusX, strength)
                        x += wa * wb * ox
                        y += wa * wb * oy
                    }
                    val (dx, dy) = head.delta(region.left + u * region.width, region.top + v * region.height,
                        HeadShell.SKIN, yaw, pitch)
                    points[point++] = left + u * width + (x + dx) / parentWidth
                    points[point++] = top + v * height + (y + dy) / parentHeight
                }
                add(KeyformCell(intArrayOf(xi, yi), WarpLatticeForm(points)))
            }
        }
        return warp.copy(geometryGrid = KeyformGrid(listOf(
            KeyformAxis(ParameterId("ParamAngleX"), HeadTurn.YAW_KEYS),
            KeyformAxis(ParameterId("ParamAngleY"), HeadTurn.PITCH_KEYS),
        ), cells))
    }

    private fun offset(u: Float, v: Float, angleX: Float, angleY: Float, centerX: Float, radiusX: Float,
                       strength: Float): Pair<Float, Float> {
        if (angleX == 0f && angleY == 0f) return 0f to 0f
        val yaw = (tanh(angleX / yawEase) * strength.coerceIn(0f, 4f)).coerceIn(-4.6f, 4.6f)
        val pitch = (tanh(angleY / pitchEase) * strength.coerceIn(0f, 4f)).coerceIn(-4.4f, 4.4f)
        val absYaw = abs(yaw)
        val direction = if (yaw == 0f) 0f else sign(yaw)
        val pointX = region.left + u * region.width
        val centeredU = (u - 0.5f) * 2f
        val archWeight = max(0f, 1f - centeredU * centeredU)
        val perspectiveSlope = yaw * pitch * featurePlane / 100f
        val farWeight = ((centeredU * direction + 1f) * 0.5f).coerceIn(0f, 1f)
        val lag = abs(centeredU).toDouble().pow(1.35).toFloat() * (nearLag + farWeight * (cornerLag - nearLag)) / 100f
        var dx = direction * absYaw * radiusX * shift / 100f - direction * absYaw * region.width * lag
        dx += yaw * pitch * region.width * (0.028f + centeredU * 0.018f)
        var dy = perspectiveSlope * ((pointX + dx) - centerX)
        dy += archWeight * region.height * absYaw * arch / 100f
        dy += -pitch * region.height * (tilt / 100f + v * 0.12f)
        dy += -absYaw * pitch * archWeight * region.height * 0.075f
        return dx to dy
    }

    private fun bracket(keys: FloatArray, value: Float): Pair<Int, Float> {
        val index = (0 until keys.size - 1).firstOrNull { value <= keys[it + 1] } ?: (keys.size - 2)
        return index to ((value - keys[index]) / (keys[index + 1] - keys[index])).coerceIn(0f, 1f)
    }
}
