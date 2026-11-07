package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*
import kotlin.math.PI
import kotlin.math.sin

/** Head-plane parallax and roll shared by the original rig and independent chains. */
object HairFollowMotion {
	data class Roll(val pivotX: Float, val pivotY: Float, val chinY: Float, val hold: Float) {
		/** The canvas offset that turns ([x], [y]) back for an Angle Z of [angleZ], on hair ending at [bottom]. */
		fun turnBack(x: Float, y: Float, bottom: Float, angleZ: Float): Pair<Float, Float> {
			// Full hold a little below the chin - about half the chin's depth under the pivot, times six - or at the tips if they are nearer.
			val reach = minOf(bottom - chinY, (chinY - pivotY).coerceAtLeast(1f) * 6f).coerceAtLeast(1f)
			val t = ((y - chinY) / reach).coerceIn(0f, 1f)
			val weight = t * t * (3f - 2f * t) * hold
			if (weight <= 0f || angleZ == 0f) return 0f to 0f
			val radians = Math.toRadians((-angleZ * weight).toDouble())
			val cos = kotlin.math.cos(radians).toFloat(); val sin = kotlin.math.sin(radians).toFloat()
			val rx = x - pivotX; val ry = y - pivotY
			return (rx * cos - ry * sin - rx) to (rx * sin + ry * cos - ry)
		}
	}

	fun point(
		inHead: Bounds,
		u: Float,
		v: Float,
		angleX: Float,
		angleY: Float,
		yawParallax: Float,
		pitchParallax: Float,
		yawPerspective: Float = 0f,
	): Pair<Float, Float> {
		val yaw = angleX / 45f
		val pitch = angleY / 30f
		val yawDepthWeight = 0.62f + v * 0.38f
		// Both endpoints receive the same vertical offset. The middle retains a depth bulge without
		// turning signed pitch into a global height scale.
		val pitchDepthWeight = 0.78f + sin(PI * v).toFloat().coerceAtLeast(0f) * 0.22f
		val uPerspective = u + yaw * yawPerspective * 4f * u * (1f - u)
		return (inHead.left + uPerspective * inHead.width + yaw * yawParallax * yawDepthWeight) to
			(inHead.top + v * inHead.height + pitch * pitchParallax * pitchDepthWeight)
	}


    data class Head(val frame: Bounds, val pivotX: Float, val pivotY: Float, val chinY: Float)
    internal data class Tuning(val yaw: Float, val pitch: Float, val perspective: Float, val hold: Float)
    internal fun defaults(front: Boolean) =
        if (front) Tuning(2f, 0.6f, 10f, 80f) else Tuning(1.8f, -0.4f, 0f, 80f)

    internal fun grid(frame: Bounds, head: Head, tuning: Tuning): KeyformGrid<WarpLatticeForm> {
        val bounds = head.frame
        val width = bounds.width.coerceAtLeast(1e-4f)
        val height = bounds.height.coerceAtLeast(1e-4f)
        val local = Bounds((frame.left - bounds.left) / width, (frame.top - bounds.top) / height,
            (frame.right - bounds.left) / width, (frame.bottom - bounds.top) / height)
        val roll = Roll(head.pivotX, head.pivotY, head.chinY, tuning.hold / 100f)
        val bends = roll.hold > 0f && frame.bottom > roll.chinY + frame.height * 0.1f
        val axes = listOf(KeyformAxis(ParameterId("ParamAngleX"), floatArrayOf(-45f, 0f, 45f)),
            KeyformAxis(ParameterId("ParamAngleY"), floatArrayOf(-30f, 0f, 30f))) +
            listOf(KeyformAxis(ParameterId("ParamAngleZ"), floatArrayOf(-30f, 0f, 30f))).takeIf { bends }.orEmpty()
        return KeyformGrid(axes, BindingAxes.coordinates(axes.asReversed()).map { coordinate ->
            val keys = coordinate.reversedArray()
            val yaw = axes[0].keys[keys[0]]
            val pitch = axes[1].keys[keys[1]]
            val points = FloatArray(40)
            var i = 0
            for (row in 0..4) for (column in 0..3) {
                val u = column / 3f
                val v = row / 4f
                val (x, y) = point(local, u, v, yaw, pitch, -tuning.yaw / 100f, -tuning.pitch / 100f, tuning.perspective / 100f)
                if (bends) {
                    val (dx, dy) = roll.turnBack(frame.left + u * frame.width, frame.top + v * frame.height, frame.bottom, axes[2].keys[keys[2]])
                    points[i++] = x + dx / bounds.width
                    points[i++] = y + dy / bounds.height
                } else {
                    points[i++] = x
                    points[i++] = y
                }
            }
            KeyformCell(keys, WarpLatticeForm(points))
        })
    }
}
