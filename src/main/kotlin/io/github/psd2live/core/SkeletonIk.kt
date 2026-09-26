package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Planar kinematics shared by the skeleton bake and the pose tool.
 *
 * Angles are degrees in the rig's own convention: canvas y points down and a positive angle turns +x
 * toward +y, which is exactly what a Cubism rotation deformer does with a positive keyform angle. Keeping
 * one convention here is what lets an IK answer be written straight into a parameter.
 */
internal object SkeletonIk {
	/** [x], [y] turned by [degrees] about ([cx], [cy]). */
	fun rotate(x: Double, y: Double, cx: Double, cy: Double, degrees: Double): DoubleArray {
		val radians = Math.toRadians(degrees)
		val c = cos(radians)
		val s = sin(radians)
		val dx = x - cx
		val dy = y - cy
		return doubleArrayOf(cx + c * dx - s * dy, cy + s * dx + c * dy)
	}

	/** Direction of ([dx], [dy]) in degrees. */
	fun heading(dx: Double, dy: Double): Double = Math.toDegrees(atan2(dy, dx))

	/** [degrees] folded into (-180, 180]. */
	fun wrap(degrees: Double): Double {
		var a = degrees % 360.0
		if (a > 180.0) a -= 360.0
		if (a <= -180.0) a += 360.0
		return a
	}

	/**
	 * The middle joint of a two-bone chain rooted at ([rx], [ry]) with segment lengths [l1] and [l2]
	 * reaching for ([tx], [ty]).
	 *
	 * [bend] picks which of the two mirror solutions: +1 puts the joint on the positive side of the
	 * root-to-target line (a positive cross product), -1 on the other. A target out of reach straightens
	 * the chain toward it; one closer than the segments allow folds it as far as it can.
	 */
	fun twoBone(rx: Double, ry: Double, l1: Double, l2: Double, tx: Double, ty: Double, bend: Double): DoubleArray {
		val dx = tx - rx
		val dy = ty - ry
		val length = hypot(dx, dy)
		if (length < 1e-9) return doubleArrayOf(rx + l1, ry)
		val ux = dx / length
		val uy = dy / length
		val d = length.coerceIn(abs(l1 - l2) + 1e-6, l1 + l2 - 1e-6)
		val along = (l1 * l1 - l2 * l2 + d * d) / (2.0 * d)
		val across = sqrt((l1 * l1 - along * along).coerceAtLeast(0.0)) * if (bend >= 0.0) 1.0 else -1.0
		return doubleArrayOf(rx + ux * along - uy * across, ry + uy * along + ux * across)
	}

	/**
	 * Cyclic coordinate descent over a chain of joint positions `[x0, y0, x1, y1, ..., xn, yn]` (n bones,
	 * n + 1 joints) toward ([tx], [ty]).
	 *
	 * Returns how far each bone should turn, in degrees, relative to the pose [joints] describes. A bone's
	 * turn carries every bone below it, which is how a rotation deformer nested under its parent behaves,
	 * so each answer is a delta for that bone's own parameter. [lower] and [upper] bound each bone's delta.
	 */
	fun ccd(
		joints: DoubleArray,
		tx: Double,
		ty: Double,
		lower: DoubleArray,
		upper: DoubleArray,
		iterations: Int = 32,
	): DoubleArray {
		val bones = joints.size / 2 - 1
		require(bones >= 1 && lower.size == bones && upper.size == bones)
		val points = joints.copyOf()
		val turned = DoubleArray(bones)
		repeat(iterations) {
			for (bone in bones - 1 downTo 0) {
				val px = points[bone * 2]
				val py = points[bone * 2 + 1]
				val ex = points[bones * 2]
				val ey = points[bones * 2 + 1]
				if (hypot(ex - px, ey - py) < 1e-9 || hypot(tx - px, ty - py) < 1e-9) continue
				val want = wrap(heading(tx - px, ty - py) - heading(ex - px, ey - py))
				val next = (turned[bone] + want).coerceIn(lower[bone], upper[bone])
				val delta = next - turned[bone]
				if (abs(delta) < 1e-9) continue
				turned[bone] = next
				for (joint in bone + 1..bones) {
					val moved = rotate(points[joint * 2], points[joint * 2 + 1], px, py, delta)
					points[joint * 2] = moved[0]
					points[joint * 2 + 1] = moved[1]
				}
			}
			if (hypot(points[bones * 2] - tx, points[bones * 2 + 1] - ty) < 0.05) return turned
		}
		return turned
	}
}
