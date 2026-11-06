package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*
import org.umamo.runtime.eval.EPS_KEY
import kotlin.math.abs

/** Shared keyform axis union and bounded coordinate enumeration. */
object BindingAxes {
	/** Axes with every knot of [input] per parameter, in first-seen order; near-duplicate knots merge. */
	fun unionAxes(input: List<KeyformAxis>): List<KeyformAxis> = input.groupBy { it.parameterId }.map { (id, axes) ->
		val values = ArrayList<Float>()
		axes.forEach { axis -> axis.keys.forEach { value -> if (values.none { abs(it - value) < EPS_KEY }) values += value } }
		KeyformAxis(id, values.sorted().toFloatArray())
	}

	/** Every coordinate of a grid over [axes], first axis fastest. */
	fun coordinates(axes: List<KeyformAxis>): List<IntArray> {
		var count = 1L
		for (axis in axes) {
			require(axis.keys.isNotEmpty()) { "Art primitive keyform axis is empty" }
			count = Math.multiplyExact(count, axis.keys.size.toLong())
			require(count <= 262144) { "Art primitive keyforms exceed the allocation limit" }
		}
		return (0 until count.toInt()).map { index ->
			var value = index
			IntArray(axes.size) { axis -> (value % axes[axis].keys.size).also { value /= axes[axis].keys.size } }
		}
	}

	fun key(axes: List<KeyformAxis>, coordinate: IntArray): Map<ParameterId, Float> =
		axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }

}
