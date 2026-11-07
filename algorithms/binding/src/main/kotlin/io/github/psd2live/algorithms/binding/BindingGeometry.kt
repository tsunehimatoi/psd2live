package io.github.psd2live.algorithms.binding

/** Source-coordinate geometry shared by binding implementations and their host adapters. */
@kotlinx.serialization.Serializable
enum class Side { LEFT, RIGHT, NONE }

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
	val width: Float get() = right - left
	val height: Float get() = bottom - top
	val centerX: Float get() = (left + right) * 0.5f
	val centerY: Float get() = (top + bottom) * 0.5f

	fun union(other: Bounds): Bounds =
		Bounds(
			minOf(left, other.left),
			minOf(top, other.top),
			maxOf(right, other.right),
			maxOf(bottom, other.bottom),
		)

	fun expanded(fraction: Float): Bounds {
		val dx = width * fraction
		val dy = height * fraction
		return Bounds(left - dx, top - dy, right + dx, bottom + dy)
	}
}

