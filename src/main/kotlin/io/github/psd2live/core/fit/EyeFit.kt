package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One eye's feature points read off the art, in the rig's aligned space: the inner and outer corners and the upper and
 * lower lid edges sampled at [SAMPLES] places from corner to corner, plus the drawn closed-eye line when the art has
 * one and the iris box.
 *
 * Everything is kept in the eye's own frame, set by the head rather than by the eye: the first axis ([axisX], [axisY])
 * runs along the head's level - the line through both eyes - from the inner corner toward the outer one, the second
 * points down the face, a unit is the corners' distance along the first axis ([width]), and the inner corner is the
 * origin. A head drawn tilted, or turned so the eyes sit askew, keeps its lids closing down its own face. The outer
 * corner may sit above or below the inner one ([cornerV]): the corners' line need not be level with the head.
 * [upper], [lower] and [closed] are the second coordinates of the lid edges at u = k / (SAMPLES - 1); [closed] is null
 * when no closed line is drawn.
 */
data class EyeFit(
	val side: Side,
	val originX: Float, val originY: Float,
	val axisX: Float, val axisY: Float,
	val width: Float,
	val upper: List<Float>,
	val lower: List<Float>,
	val closed: List<Float>?,
	val iris: Bounds?,
) {
	/** The frame's second axis, perpendicular to the head's level and pointing down the face. */
	private val downX: Float get() = if (axisX >= 0f) -axisY else axisY
	private val downY: Float get() = if (axisX >= 0f) axisX else -axisX

	/** Where the outer corner sits on the second axis: 0 when it is level with the inner corner. */
	val cornerV: Float get() = upper.last()

	/** The corners' line at [u]: where the lid edges meet at both ends, and what the lids' arcs rise from. */
	fun baseline(u: Float): Float = cornerV * u

	fun toCanvas(u: Float, v: Float): Pair<Float, Float> =
		(originX + (u * axisX + v * downX) * width) to (originY + (u * axisY + v * downY) * width)

	fun toEye(x: Float, y: Float): Pair<Float, Float> {
		val dx = (x - originX) / width; val dy = (y - originY) / width
		return (dx * axisX + dy * axisY) to (dx * downX + dy * downY)
	}

	/** A canvas displacement of the eye-frame displacement ([du], [dv]). */
	fun canvasDelta(du: Float, dv: Float): Pair<Float, Float> = ((du * axisX + dv * downX) * width) to ((du * axisY + dv * downY) * width)

	companion object {
		const val SAMPLES = 17

		/** Sample [k]'s place from corner to corner. */
		fun u(k: Int) = k.toFloat() / (SAMPLES - 1)

		/** [curve] at [u], linear between samples, held at its ends outside 0..1. */
		fun sample(curve: List<Float>, u: Float): Float {
			val f = (u.coerceIn(0f, 1f) * (SAMPLES - 1))
			val i = f.toInt().coerceAtMost(SAMPLES - 2)
			return curve[i] + (curve[i + 1] - curve[i]) * (f - i)
		}
	}
}

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

	class Result(val fits: Map<Side, EyeFit>, val reasons: Map<Side, String>)

	/** A layer's alpha at canvas ([x], [y]), 0 outside it. */
	private fun alpha(layer: ClassifiedLayer, x: Float, y: Float): Int {
		val raster = layer.source.raster
		val px = (x - layer.source.bounds.left).toInt(); val py = (y - layer.source.bounds.top).toInt()
		if (px < 0 || py < 0 || px >= raster.width || py >= raster.height) return 0
		return raster.rgba[(py * raster.width + px) * 4 + 3].toInt() and 0xff
	}

	/** The canvas centres of a layer's opaque pixels, restricted to those [keep] accepts. */
	private fun pixels(layer: ClassifiedLayer, keep: (Float) -> Boolean): List<Pair<Float, Float>> {
		val raster = layer.source.raster
		val out = ArrayList<Pair<Float, Float>>()
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < ALPHA) continue
			val cx = layer.source.bounds.left + x + 0.5f
			if (keep(cx)) out += cx to (layer.source.bounds.top + y + 0.5f)
		}
		return out
	}

	/**
	 * The fits of [layers]' eyes. [toRig] carries canvas points into the rig's aligned space; [faceCenterX] tells the
	 * inner corner from the outer one and splits a white drawn for both eyes on one layer.
	 */
	fun fit(layers: List<ClassifiedLayer>, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>): Result {
		fun onSide(side: Side, x: Float) = if (side == Side.LEFT) x >= faceCenterX else x < faceCenterX
		fun candidates(side: Side, tag: SemanticTag) = layers.filter {
			it.opaquePixels > 0 && it.semantic.tag == tag && it.semantic.role == null &&
				(it.semantic.side == side || it.semantic.side == Side.NONE && onSide(side, it.centroidX))
		}
		// Side.LEFT is the character's left, on the canvas's right.
		val whites = listOf(Side.LEFT, Side.RIGHT).associateWith { side ->
			candidates(side, SemanticTag.EYEWHITE).maxByOrNull { it.bounds.width * it.bounds.height }?.let { white ->
				white to pixels(white) { x -> white.semantic.side != Side.NONE || onSide(side, x) }
			}
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
			val fit = read(side, white.first, white.second, canvasLevel, rigLevel, faceCenterX, toRig,
				candidates(side, SemanticTag.EYE_CLOSE).maxByOrNull { it.opaquePixels }, candidates(side, SemanticTag.IRIDES).maxByOrNull { it.opaquePixels })
			val problem = fit?.let(::check) ?: "the eye white has no clear outline"
			if (fit != null && check(fit) == null) fits[side] = fit else reasons[side] = problem
		}
		return Result(fits, reasons)
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
		side: Side, white: ClassifiedLayer, points: List<Pair<Float, Float>>, level: Pair<Float, Float>, rigLevel: Pair<Float, Float>,
		faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>, closedLayer: ClassifiedLayer?, irisLayer: ClassifiedLayer?,
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
				if (alpha(white, bx + dx * t, by + dy * t) >= ALPHA) { if (first.isNaN()) first = t; last = t }
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
			val line = centreLine(layer, level).map { eye(it) }.sortedBy { it.first }
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
		return frame.copy(upper = upper, lower = lower, closed = closed, iris = irisLayer?.bounds)
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
	private fun centreLine(layer: ClassifiedLayer, level: Pair<Float, Float>): List<Pair<Float, Float>> {
		val (lx, ly) = level
		val buckets = HashMap<Int, DoubleArray>()
		val raster = layer.source.raster
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			val a = raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff
			if (a < ALPHA) continue
			val cx = layer.source.bounds.left + x + 0.5f; val cy = layer.source.bounds.top + y + 0.5f
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

/**
 * The eyelid as a plane turning about the corners' line over a round eyeball (the pseudo-3D lid): where the plane
 * cuts the ball is the lid's edge, so as the lid closes its edge stays an arc that sinks over the ball, flattens only as
 * it passes the corners and turns into the downward arc of a closed eye - never the flattened lens a straight blend
 * of the open and closed shapes passes through. The ball has radius [radius] (of the eye's width) about (0.5,
 * [centreY], 0); the corners are on its surface.
 */
class EyelidModel(private val radius: Float = 0.68f, private val centreY: Float = -0.18f) {
	private val cornerDepth = sqrt((radius * radius - 0.25f - centreY * centreY).coerceAtLeast(1e-4f))

	/** The lid edge's height at [u] (the eye frame's second coordinate, negative above the corners) with the plane at [angle] radians. */
	fun edge(u: Float, angle: Float): Float {
		val b = 2f * (centreY * cos(angle) + cornerDepth * sin(angle))
		val c = (u - 0.5f) * (u - 0.5f) - 0.25f
		val root = sqrt((b * b - 4f * c).coerceAtLeast(0f))
		// The front of the two cuts: the larger s faces the viewer while the plane leans toward them.
		val s = if (sin(angle) >= 0f) (-b + root) / 2f else (-b - root) / 2f
		return -s * cos(angle)
	}

	/** The angle whose edge reaches [height] at the eye's middle, between [from] and [to] radians (bisection). */
	fun angleFor(height: Float, from: Float = 0.05f, to: Float = 2.6f): Float {
		var lo = from; var hi = to
		val rising = edge(0.5f, lo) < edge(0.5f, hi)
		repeat(48) {
			val mid = (lo + hi) / 2f
			if ((edge(0.5f, mid) < height) == rising) lo = mid else hi = mid
		}
		return (lo + hi) / 2f
	}
}
