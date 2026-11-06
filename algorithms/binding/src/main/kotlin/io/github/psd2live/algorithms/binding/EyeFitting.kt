package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceInputs
import io.github.psd2live.core.modifier.ModifierSourceLayer
import kotlin.math.*

/**
 * Reads [EyeFit]s off classified layers. The head's level is the line through both eye whites' centres (a single eye
 * takes the rig's level), so a tilted or turned head is read in its own frame. Along that level the eye white's opaque
 * pixels give the corners - the ends of the white - and, sampled down the face at every place between them, both lid
 * edges (artists draw the white whole under the lashes); the closed-eye line gives its centre line, the iris its box.
 * Each fit is checked before use - the eye has a sensible width and opening, the upper edge lies above the lower one,
 * the corners are not steeper than the eye is wide - and an eye that fails is left to the legacy rig, with the reason
 * in [Result.reasons].
 */
object EyeFitting {
	private const val ALPHA = 96

	/**
	 * The fits, the reasons an eye was left out, and the head's level in the rig's space ([level], a unit vector from
	 * the character's right toward its left): the frame the brows and the mouth are read in too.
	 */
	class Result(val fits: Map<Side, EyeFit>, val reasons: Map<Side, String>, val level: Pair<Float, Float> = 1f to 0f)

	/** A layer's alpha at canvas ([x], [y]), 0 outside it. */
	private fun alpha(inputs: ModifierSourceInputs, layer: ModifierSourceLayer, x: Float, y: Float): Int {
		val raster = inputs.raster(layer)
		val px = (x - layer.left).toInt(); val py = (y - layer.top).toInt()
		if (px < 0 || py < 0 || px >= raster.width || py >= raster.height) return 0
		return raster.alpha(px, py)
	}

	/** The canvas centres of a layer's opaque pixels, restricted to those [keep] accepts. */
	private fun pixels(inputs: ModifierSourceInputs, layer: ModifierSourceLayer, keep: (Float) -> Boolean): List<Pair<Float, Float>> {
		val raster = inputs.raster(layer)
		val out = ArrayList<Pair<Float, Float>>()
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			if (raster.alpha(x, y) < ALPHA) continue
			val cx = layer.left + x + 0.5f
			if (keep(cx)) out += cx to (layer.top + y + 0.5f)
		}
		return out
	}

	/**
	 * The fits of [layers]' eyes. [toRig] carries canvas points into the rig's aligned space; [faceCenterX] tells the
	 * inner corner from the outer one and splits a white drawn for both eyes on one layer.
	 */
	fun fit(inputs: ModifierSourceInputs, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float> = inputs.toModel::map): Result {
        val layers = inputs.layers
		fun onSide(side: Side, x: Float) = if (side == Side.LEFT) x >= faceCenterX else x < faceCenterX
		fun candidates(side: Side, tag: String) = layers.filter {
			it.opaquePixels > 0 && it.tag == tag && it.role == null &&
				(it.side == side.name || it.side == Side.NONE.name && onSide(side, it.centroidX))
		}
		// Side.LEFT is the character's left, on the canvas's right.
		val whites = listOf(Side.LEFT, Side.RIGHT).associateWith { side ->
			layers.filter { it.opaquePixels > 0 && it.tag == "EYEWHITE" && it.role == null &&
				(it.side == side.name || it.side == Side.NONE.name) }.map { white ->
				white to pixels(inputs, white) { x -> white.side != Side.NONE.name || onSide(side, x) }
			}.filter { it.second.isNotEmpty() }.maxByOrNull { it.second.size }
		}
		fun centre(points: List<Pair<Float, Float>>) = points.map { it.first }.average().toFloat() to points.map { it.second }.average().toFloat()
		val centres = whites.mapValues { (_, w) -> w?.second?.takeIf { it.isNotEmpty() }?.let(::centre) }
		// The head's level, from the character's right eye to its left one; a single eye takes the rig's level.
		val level = centres[Side.LEFT]?.let { l -> centres[Side.RIGHT]?.let { r -> l to r } }?.let { (l, r) ->
			val canvas = (l.first - r.first) to (l.second - r.second)
			val rl = toRig(l.first, l.second); val rr = toRig(r.first, r.second)
			canvas to ((rl.first - rr.first) to (rl.second - rr.second))
		}
		val canvasLevel = level?.first?.let { normalized(it) }?.takeIf { abs(it.second) < 0.7f } ?: (1f to 0f)
		val rigLevel = level?.second?.let { normalized(it) }?.takeIf { abs(it.second) < 0.7f } ?: rigHorizontal(toRig)
		val fits = LinkedHashMap<Side, EyeFit>()
		val reasons = LinkedHashMap<Side, String>()
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val white = whites[side]
			if (white == null || white.second.size < 12) { reasons[side] = if (white == null) "no eye white" else "the eye white is too small"; continue }
			val fit = read(inputs, side, white.first, white.second, canvasLevel, rigLevel, faceCenterX, toRig,
				candidates(side, "EYE_CLOSE").maxByOrNull { it.opaquePixels }, candidates(side, "IRIDES").maxByOrNull { it.opaquePixels })
			val problem = fit?.let(::check) ?: "the eye white has no clear outline"
			if (fit != null && check(fit) == null) fits[side] = fit.copy(openingLayers = setOf(white.first.id)) else reasons[side] = problem
		}
		return Result(fits, reasons, rigLevel)
	}

	private fun normalized(v: Pair<Float, Float>): Pair<Float, Float>? {
		val length = hypot(v.first, v.second)
		return if (length < 1e-3f) null else (v.first / length) to (v.second / length)
	}

	/** The rig's own level: the direction canvas (1, 0) points to once carried into it. */
	private fun rigHorizontal(toRig: (Float, Float) -> Pair<Float, Float>): Pair<Float, Float> {
		val a = toRig(0f, 0f); val b = toRig(100f, 0f)
		return normalized((b.first - a.first) to (b.second - a.second)) ?: (1f to 0f)
	}

	private fun read(
        inputs: ModifierSourceInputs,
		side: Side, white: ModifierSourceLayer, points: List<Pair<Float, Float>>, level: Pair<Float, Float>, rigLevel: Pair<Float, Float>,
		faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>, closedLayer: ModifierSourceLayer?, irisLayer: ModifierSourceLayer?,
	): EyeFit? {
		val (lx, ly) = level
		// Down the face: perpendicular to the level, toward the canvas's bottom.
		val dx = -ly; val dy = lx
		fun along(p: Pair<Float, Float>) = p.first * lx + p.second * ly
		val positions = points.map(::along)
		val lo = positions.min(); val hi = positions.max()
		if (hi - lo < 4f) return null
		// The corners: the middle of the white at its two ends along the level, over the outermost few percent.
		val band = (hi - lo) * 0.03f
		fun corner(at: Float) = points.filter { abs(along(it) - at) <= band }.let(::average)
		val low = corner(lo); val high = corner(hi)
		val lowInner = abs(low.first - faceCenterX) < abs(high.first - faceCenterX)
		val inner = if (lowInner) low else high
		val outer = if (lowInner) high else low
		val span = abs(along(outer) - along(inner))
		// Carried into the rig and the eye's frame there.
		val rInner = toRig(inner.first, inner.second); val rOuter = toRig(outer.first, outer.second)
		val sign = if ((rOuter.first - rInner.first) * rigLevel.first + (rOuter.second - rInner.second) * rigLevel.second >= 0f) 1f else -1f
		val axis = (rigLevel.first * sign) to (rigLevel.second * sign)
		val width = ((rOuter.first - rInner.first) * axis.first + (rOuter.second - rInner.second) * axis.second).coerceAtLeast(1e-3f)
		val frame = EyeFit(side, rInner.first, rInner.second, axis.first, axis.second, width, listOf(0f), listOf(0f), null, null)
		fun eye(p: Pair<Float, Float>) = toRig(p.first, p.second).let { frame.toEye(it.first, it.second) }
		val cornerV = eye(outer).second
		// Both lid edges at each place from corner to corner: where the white begins and ends going down the face.
		val samples = (0 until EyeFit.SAMPLES * 3).mapNotNull { i ->
			val u = i / (EyeFit.SAMPLES * 3 - 1f)
			val sign2 = if (lowInner) 1f else -1f
			val bx = inner.first + sign2 * lx * u * span; val by = inner.second + sign2 * ly * u * span
			val reach = span * 1.2f
			var first = Float.NaN; var last = Float.NaN
			var t = -reach
			while (t <= reach) {
				if (alpha(inputs, white, bx + dx * t, by + dy * t) >= ALPHA) { if (first.isNaN()) first = t; last = t }
				t += 0.5f
			}
			if (first.isNaN()) null else Triple(u, eye(bx + dx * first to by + dy * first), eye(bx + dx * last to by + dy * last))
		}
		if (samples.size < 6) return null
		// Smoothed and resampled at the frame's places; the edges meet at the corners.
		fun curve(pick: (Triple<Float, Pair<Float, Float>, Pair<Float, Float>>) -> Pair<Float, Float>): List<Float> {
			val raw = samples.map(pick).sortedBy { it.first }
			val reach = max(1, raw.size / 12)
			val smooth = raw.indices.map { i -> raw[i].first to raw.subList(max(0, i - reach), min(raw.size, i + reach + 1)).map { it.second }.average().toFloat() }
			return (0 until EyeFit.SAMPLES).map { k ->
				val u = EyeFit.u(k)
				when (k) {
					0 -> 0f
					EyeFit.SAMPLES - 1 -> cornerV
					else -> interpolate(smooth, u)
				}
			}
		}
		val upper = curve { it.second }
		val lower = curve { it.third }
		val closed = closedLayer?.let { layer ->
			val line = centreLine(inputs, layer, level).map { eye(it) }.sortedBy { it.first }
			// Only a line spanning most of the eye stands for its closed lid.
			if (line.size < 4 || line.last().first - line.first().first < 0.6f) null
			else (0 until EyeFit.SAMPLES).map { k ->
				val u = EyeFit.u(k)
				when {
					u <= line.first().first -> line.first().second * (u / line.first().first.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
					u >= line.last().first -> cornerV + (line.last().second - cornerV) * ((1f - u) / (1f - line.last().first).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
					else -> interpolate(line, u)
				}
			}
		}
		return frame.copy(upper = upper, lower = lower, closed = closed, iris = irisLayer?.bounds?.let { Bounds(it.left, it.top, it.right, it.bottom) })
	}

	private fun average(points: List<Pair<Float, Float>>) = points.map { it.first }.average().toFloat() to points.map { it.second }.average().toFloat()

	/** [points] (sorted by their first value) at [u], linear between them, held at the ends. */
	private fun interpolate(points: List<Pair<Float, Float>>, u: Float): Float {
		val i = points.indexOfFirst { it.first >= u }
		return when {
			i <= 0 -> points.first().second
			else -> {
				val a = points[i - 1]; val b = points[i]
				val t = if (b.first - a.first < 1e-6f) 0f else (u - a.first) / (b.first - a.first)
				a.second + (b.second - a.second) * t
			}
		}
	}

	/** A drawn line's centre: the alpha-weighted middle of its pixels across every place along the head's [level]. */
	private fun centreLine(inputs: ModifierSourceInputs, layer: ModifierSourceLayer, level: Pair<Float, Float>): List<Pair<Float, Float>> {
		val (lx, ly) = level
		val buckets = HashMap<Int, DoubleArray>()
		val raster = inputs.raster(layer)
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			val a = raster.alpha(x, y)
			if (a < ALPHA) continue
			val cx = layer.left + x + 0.5f; val cy = layer.top + y + 0.5f
			val key = Math.round(cx * lx + cy * ly)
			val bucket = buckets.getOrPut(key) { DoubleArray(3) }
			bucket[0] += a.toDouble(); bucket[1] += cx * a.toDouble(); bucket[2] += cy * a.toDouble()
		}
		return buckets.values.map { (it[1] / it[0]).toFloat() to (it[2] / it[0]).toFloat() }
	}

	/** Why [fit] cannot be used, or null. */
	private fun check(fit: EyeFit): String? {
		if (fit.width < 6f) return "the eye is narrower than 6 px"
		val inner = 2 until EyeFit.SAMPLES - 2
		val opening = inner.maxOf { fit.lower[it] - fit.upper[it] }
		if (opening < 0.12f || opening > 1.3f) return "the eye opens ${"%.2f".format(opening)} of its width"
		if (inner.any { fit.upper[it] >= fit.lower[it] }) return "the upper lid edge is not above the lower one"
		if (abs(fit.cornerV) > 0.6f) return "the corners lie too steep"
		return null
	}
}
