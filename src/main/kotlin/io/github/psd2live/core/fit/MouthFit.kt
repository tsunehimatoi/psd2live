package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.SemanticTag
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The open mouth read off the art in the head's frame, as the eyes are ([EyeFit]): the first axis runs along the head's
 * level from the mouth's corner on the canvas's left (u = 0) to the other (u = 1), the second points down the face, and
 * a unit is the distance between the corners. The right corner may sit higher or lower ([cornerV]). [upper] and
 * [lower] are the outer edges of the drawn mouth - the top of the upper lip, the bottom of the lower one - sampled at
 * [SAMPLES] places between the corners, finely enough to follow a round mouth's steep sides at its corners.
 */
data class MouthFit(
	val originX: Float, val originY: Float,
	val axisX: Float, val axisY: Float,
	val width: Float,
	val upper: List<Float>,
	val lower: List<Float>,
) {
	val cornerV: Float get() = upper.last()
	private val downX: Float get() = -axisY
	private val downY: Float get() = axisX

	/** The line through both corners. */
	fun baseline(u: Float): Float = cornerV * u

	/** How far the drawn mouth opens at its middle, in units of its width. */
	val opening: Float get() = sample(lower, 0.5f) - sample(upper, 0.5f)

	fun toMouth(x: Float, y: Float): Pair<Float, Float> {
		val dx = (x - originX) / width; val dy = (y - originY) / width
		return (dx * axisX + dy * axisY) to (dx * downX + dy * downY)
	}

	fun toCanvas(u: Float, v: Float): Pair<Float, Float> = (originX + (u * axisX + v * downX) * width) to (originY + (u * axisY + v * downY) * width)

	fun canvasDelta(du: Float, dv: Float): Pair<Float, Float> = ((du * axisX + dv * downX) * width) to ((du * axisY + dv * downY) * width)

	companion object {
		const val SAMPLES = 49
		fun u(k: Int) = k.toFloat() / (SAMPLES - 1)

		/** [curve] at [u], linear between samples, held at its ends outside 0..1. */
		fun sample(curve: List<Float>, u: Float): Float {
			val f = u.coerceIn(0f, 1f) * (curve.size - 1)
			val i = f.toInt().coerceAtMost(curve.size - 2)
			return curve[i] + (curve[i + 1] - curve[i]) * (f - i)
		}
	}
}

/** Reads the mouth off the art: [fit] the drawn open mouth, or why it can not be read. */
object MouthFitting {
	class Result(val fit: MouthFit?, val reason: String?, val layers: List<ClassifiedLayer>)

	/**
	 * How thick a layered mouth's lip lines are drawn, in units of [fit]'s width: across the middle fifth of the mouth,
	 * the typical height of each line layer's column of opaque pixels, the two lines averaged; null without line layers.
	 */
	fun lineWidth(fit: MouthFit, layers: List<ClassifiedLayer>, toRig: (Float, Float) -> Pair<Float, Float>): Float? {
		val lines = layers.filter { it.opaquePixels > 0 && it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
			(it.semantic.role == io.github.psd2live.core.PartRole.UPPER_LINE || it.semantic.role == io.github.psd2live.core.PartRole.LOWER_LINE) }
		val widths = lines.mapNotNull { layer ->
			val raster = layer.source.raster; val bounds = layer.source.bounds
			val sx = bounds.width / raster.width.coerceAtLeast(1).toFloat(); val sy = bounds.height / raster.height.coerceAtLeast(1).toFloat()
			val bins = 12
			val tops = FloatArray(bins) { Float.MAX_VALUE }; val bottoms = FloatArray(bins) { -Float.MAX_VALUE }
			for (y in 0 until raster.height) for (x in 0 until raster.width) {
				if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < 96) continue
				val (rx, ry) = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
				val (u, v) = fit.toMouth(rx, ry)
				if (u < 0.4f || u >= 0.6f) continue
				val k = ((u - 0.4f) / 0.2f * bins).toInt().coerceIn(0, bins - 1)
				tops[k] = min(tops[k], v); bottoms[k] = max(bottoms[k], v)
			}
			val columns = (0 until bins).filter { bottoms[it] >= tops[it] }.map { bottoms[it] - tops[it] + sy / fit.width }.sorted()
			columns.getOrNull(columns.size / 2)
		}
		return if (widths.isEmpty()) null else widths.average().toFloat()
	}

	/**
	 * The mouth drawn open: the largest mouth layer with the mouth layers beside it that belong to it (an open-mouth
	 * interior), in the head's [level] (in the rig's space; [toRig] carries canvas points there). A mouth drawn closed
	 * - a line - has nothing to open from and is left to the legacy rig.
	 */
	fun fit(layers: List<ClassifiedLayer>, level: Pair<Float, Float>, toRig: (Float, Float) -> Pair<Float, Float>): Result {
		// Generated lip ribbons outline the mouth; they are not its drawing. A layered mouth's lip lines bound its opening
		// as a whole mouth's lines do, but its lips reach out over the skin and are left out.
		val lines = setOf(io.github.psd2live.core.PartRole.UPPER_LINE, io.github.psd2live.core.PartRole.LOWER_LINE)
		// A layer clipped onto the opening (the inside's base, drawn larger than any opening) is not its shape.
		val mouths = layers.filter { it.opaquePixels > 0 && !it.source.clipped && (it.semantic.role == null || it.semantic.role in lines) &&
			it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && !io.github.psd2live.core.MouthLipLayer.isLipId(it.source.id.raw) }
		val main = mouths.filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null }.maxByOrNull { it.opaquePixels }
			?: mouths.filter { it.semantic.role == null }.maxByOrNull { it.opaquePixels } ?: return Result(null, "no mouth", emptyList())
		val own = mouths.filter { it === main || it.semantic.variant == main.semantic.variant && it.semantic.side == main.semantic.side &&
			it.bounds.left < main.bounds.right && main.bounds.left < it.bounds.right && it.bounds.top < main.bounds.bottom && main.bounds.top < it.bounds.bottom }
		// Lip lines, where drawn, are the lips' edges: the inside behind them is often drawn past them, to be clipped.
		val edges = own.filter { it.semantic.role in lines }.takeIf { drawn -> drawn.any { it.semantic.role == io.github.psd2live.core.PartRole.UPPER_LINE } &&
			drawn.any { it.semantic.role == io.github.psd2live.core.PartRole.LOWER_LINE } } ?: own
		val points = ArrayList<Pair<Float, Float>>()
		// Each line's own points, where the mouth is read off its lines: its corners are where the two meet.
		val upperLine = ArrayList<Pair<Float, Float>>(); val lowerLine = ArrayList<Pair<Float, Float>>()
		for (layer in edges) {
			val raster = layer.source.raster; val bounds = layer.source.bounds
			val sx = bounds.width / raster.width.coerceAtLeast(1).toFloat(); val sy = bounds.height / raster.height.coerceAtLeast(1).toFloat()
			val own = when (layer.semantic.role) {
				io.github.psd2live.core.PartRole.UPPER_LINE -> upperLine
				io.github.psd2live.core.PartRole.LOWER_LINE -> lowerLine
				else -> null
			}
			for (y in 0 until raster.height) for (x in 0 until raster.width) {
				if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < 96) continue
				val p = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
				points += p; own?.add(p)
			}
		}
		if (points.size < 24) return Result(null, "the mouth is too small", own)
		val (lx, ly) = level
		fun along(p: Pair<Float, Float>) = p.first * lx + p.second * ly
		val lo = points.minOf(::along); val hi = points.maxOf(::along)
		if (hi - lo < 6f) return Result(null, "the mouth is too narrow", own)
		fun corner(near: Float): Pair<Float, Float> {
			val band = points.filter { abs(along(it) - near) <= (hi - lo) * 0.03f }
			// Drawn lines meet at a corner, one often running on past it - a round mouth's side standing up to the other line.
			// The corner is where both are, not the middle of that side.
			val reach = (hi - lo) * 0.06f; val touch = max(2f, (hi - lo) * 0.015f)
			val ups = upperLine.filter { abs(along(it) - near) <= reach }; val lows = lowerLine.filter { abs(along(it) - near) <= reach }
			val met = if (ups.isEmpty() || lows.isEmpty()) emptyList() else (ups + lows).filter { p ->
				(if (p in ups) lows else ups).any { q -> hypot(p.first - q.first, p.second - q.second) <= touch }
			}.let { both -> val end = both.minOfOrNull { abs(along(it) - near) } ?: 0f; both.filter { abs(along(it) - near) <= end + touch } }
			val at = met.ifEmpty { band }
			return at.map { it.first }.average().toFloat() to at.map { it.second }.average().toFloat()
		}
		val left = corner(lo); val right = corner(hi)
		val length = hypot(right.first - left.first, right.second - left.second)
		val frame = MouthFit(left.first, left.second, lx, ly, length.coerceAtLeast(1f), emptyList(), emptyList())
		val n = MouthFit.SAMPLES
		val tops = arrayOfNulls<Float>(n); val bottoms = arrayOfNulls<Float>(n)
		for (p in points) {
			val (u, v) = frame.toMouth(p.first, p.second)
			if (u < 0f || u > 1f) continue
			val k = Math.round(u * (n - 1))
			if (tops[k] == null || v < tops[k]!!) tops[k] = v
			if (bottoms[k] == null || v > bottoms[k]!!) bottoms[k] = v
		}
		val cornerV = frame.toMouth(right.first, right.second).second
		fun line(values: Array<Float?>): List<Float> {
			val known = values.indices.filter { values[it] != null }
			if (known.isEmpty()) return List(n) { k -> cornerV * MouthFit.u(k) }
			val raw = List(n) { k ->
				values[k] ?: run {
					val a = known.lastOrNull { it < k }; val b = known.firstOrNull { it > k }
					when {
						a != null && b != null -> values[a]!! + (values[b]!! - values[a]!!) * (k - a) / (b - a).toFloat()
						else -> values[a ?: b!!]!!
					}
				}
			}
			// Lightly smoothed; the ends sit on the corners.
			return List(n) { k ->
				when (k) {
					0 -> 0f
					n - 1 -> cornerV
					else -> (raw[k - 1] + 2f * raw[k] + raw[k + 1]) / 4f
				}
			}
		}
		val fit = frame.copy(upper = line(tops), lower = line(bottoms))
		return when {
			fit.opening < 0.12f -> Result(null, "the mouth is drawn closed", own)
			fit.opening > 1.6f -> Result(null, "the mouth is taller than it is wide", own)
			else -> Result(fit, null, own)
		}
	}
}
