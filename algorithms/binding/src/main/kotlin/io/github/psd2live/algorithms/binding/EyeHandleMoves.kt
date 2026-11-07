package io.github.psd2live.algorithms.binding

import kotlin.math.max
import kotlin.math.min

object FeatureInterpolation {
	/**
	 * The second coordinates, at [n] evenly spaced places along the first, of the rig points [points] seen in a frame
	 * ([toFrame]): what a curve becomes once its points moved and its frame changed.
	 */
	fun resample(points: List<Pair<Float, Float>>, toFrame: (Float, Float) -> Pair<Float, Float>, n: Int): List<Float> {
		val framed = points.map { toFrame(it.first, it.second) }.sortedBy { it.first }
		return List(n) { k ->
			val u = k.toFloat() / (n - 1)
			val i = framed.indexOfFirst { it.first >= u }
			when {
				i < 0 -> framed.last().second
				i == 0 -> framed.first().second
				else -> {
					val a = framed[i - 1]; val b = framed[i]
					val t = ((u - a.first) / max(b.first - a.first, 1e-5f)).coerceIn(0f, 1f)
					a.second + (b.second - a.second) * t
				}
			}
		}
	}

	/** How much of a handle at the middle of an edge moves the edge at [u]: all of it at the middle, none at the ends. */
	fun middle(u: Float): Float = EyeRig.hump(min(max(u, 0f), 1f)).coerceAtLeast(0f)
}

/** A rig-space move of a feature's handle. */
typealias RigMove = Pair<Float, Float>

private operator fun RigMove.times(k: Float): RigMove = first * k to second * k
private operator fun RigMove.plus(o: RigMove): RigMove = first + o.first to second + o.second
private val NONE: RigMove = 0f to 0f

/** This eye with its handles ([moves]: inner, outer, upper, lower, as rig moves) moved. */
fun EyeFit.moved(moves: Map<String, RigMove>): EyeFit {
	if (moves.isEmpty()) return this
	val inner = moves["inner"] ?: NONE; val outer = moves["outer"] ?: NONE
	fun shifted(curve: List<Float>, peak: RigMove): List<Pair<Float, Float>> = curve.indices.map { k ->
		val u = EyeFit.u(k)
		val (x, y) = toCanvas(u, curve[k])
		val d = inner * (1f - u) + outer * u + peak * FeatureInterpolation.middle(u)
		(x + d.first) to (y + d.second)
	}
	val upperPoints = shifted(upper, moves["upper"] ?: NONE)
	val lowerPoints = shifted(lower, moves["lower"] ?: NONE)
	val closedPoints = closed?.let { shifted(it, NONE) }
	val (ox, oy) = upperPoints.first(); val (ex, ey) = upperPoints.last()
	val width = ((ex - ox) * axisX + (ey - oy) * axisY).coerceAtLeast(1f)
	val frame = copy(originX = ox, originY = oy, width = width)
	val movedUpper = FeatureInterpolation.resample(upperPoints, frame::toEye, EyeFit.SAMPLES)
	val sourceUpper = FeatureInterpolation.resample(upper.indices.map { toCanvas(EyeFit.u(it),upper[it]) }, frame::toEye, EyeFit.SAMPLES)
	return frame.copy(upper = movedUpper,
		lower = FeatureInterpolation.resample(lowerPoints, frame::toEye, EyeFit.SAMPLES),
		closed = closedPoints?.let { FeatureInterpolation.resample(it, frame::toEye, EyeFit.SAMPLES) },
		artwork = null, upperCorrection = movedUpper.indices.map { movedUpper[it]-sourceUpper[it] })
}
