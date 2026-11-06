package io.github.psd2live.algorithms.binding

import kotlin.math.*

/** A continuous material band. Source coordinates stay fixed while its centre and transverse extent change. */
internal class EyeBand(private val fit: EyeFit, private val stroke: EyeStroke,
	private val roundness: Float = 1f, private val smileArc: Float = 0.12f,
	private val closedCentre: ((Float, Boolean) -> Float)? = null) {
	companion object {
		/** Cubic Hermite interpolation, with tangent continuation beyond the two ends. */
		fun sample(values: List<Float>, u: Float): Float {
			val last = values.lastIndex
			fun tangent(k: Int) = when (k) {
				0 -> values[1] - values[0]
				last -> values[k] - values[k - 1]
				else -> (values[k + 1] - values[k - 1]) / 2f
			}
			if (u < 0f) return values.first() + tangent(0) * u * last
			if (u > 1f) return values.last() + tangent(last) * (u - 1f) * last
			val x = u * last
			val i = x.toInt().coerceAtMost(last - 1)
			val t = x - i
			val t2 = t * t
			val t3 = t2 * t
			return (2 * t3 - 3 * t2 + 1) * values[i] + (t3 - 2 * t2 + t) * tangent(i) +
				(-2 * t3 + 3 * t2) * values[i + 1] + (t3 - t2) * tangent(i + 1)
		}
	}

	private val n = EyeFit.SAMPLES
	private val y = FloatArray(n) { i ->
		val u = i.toFloat() / (n - 1)
		val radius = max(stroke.halfWidth(u), 1f / (n - 1))
		val points = stroke.points.filter { abs(it.x - u) <= radius && it.x in 0f..1f }
		if (points.isEmpty()) stroke.at(u).y else points.map { it.y }.average().toFloat()
	}

	val halfWidth = stroke.halfWidths.filterIndexed { i, _ -> stroke.observed[i] && stroke.points[i].x in 0.2f..0.8f }
		.sorted().let { if (it.isEmpty()) 1f / fit.width else it[it.size / 2] }.coerceAtLeast(0.5f / fit.width)
	private val centres = y.toList()
	fun centre(u: Float): Float = sample(centres, u)

	fun slope(u: Float): Float = (centre(u + 0.001f) - centre(u - 0.001f)) / 0.002f
	fun closed(u: Float, smile: Boolean): Float {
		closedCentre?.let { return it(u, smile) }
		if (!smile && fit.closed != null) return sample(fit.closed,u) + (fit.upperCorrection?.let { sample(it,u) } ?: 0f)
		val x = u.coerceIn(0f, 1f)
		val tilt = fit.cornerV
		val midpoint = if (smile) fit.baseline(0.5f) - smileArc else EyeFit.sample(fit.lower, 0.5f) - halfWidth * 2f
		val depth = if (smile) -smileArc else halfWidth * roundness
		val endSlope = tilt + depth * 4f * (1f - 2f * x)
		return midpoint + tilt * (x - 0.5f) + depth * (4f * x * (1f - x) - 1f) + (u - x) * endSlope +
			(fit.upperCorrection?.let { sample(it, u) } ?: 0f)
	}
	fun move(u: Float, v: Float, open: Float, smile: Boolean, turnTuft: Boolean, toothWeight: Float = 1f): EyePoint {
		val o = open.coerceIn(0f, 1f)
		val rest = centre(u)
		val offset = v - rest
		val target = closed(u, smile)
		val sourceSlope = slope(u)
		val targetSlope = (closed(u + 0.001f, smile) - closed(u - 0.001f, smile)) / 0.002f
		val angle = atan(sourceSlope * o + targetSlope * (1f - o)) - atan(sourceSlope)
		// Thickness belongs to the band. The exposed portion of a tooth can flatten into it and emerge on the other side.
		val exposed = (-offset - halfWidth).coerceAtLeast(0f)
		val t = (exposed / halfWidth).coerceIn(0f, 1f)
		val blend = t * t * (3f - 2f * t)
		val tooth = if (turnTuft && !smile) 2f * exposed * blend * (1f - o) * toothWeight else 0f
		val side = (offset - halfWidth).coerceAtLeast(0f)
		val retract = if (stroke.region == EyeRegion.UPPER) side * side / (side + halfWidth) * (1f - o) else 0f
		val dy = (target - rest) * (1f - o) + offset * (cos(angle) - 1f) + tooth - retract
		return EyePoint(u - offset * sin(angle), v + dy)
	}
}
