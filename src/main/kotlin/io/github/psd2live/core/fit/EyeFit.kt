package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
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
 * Everything is kept in the eye's own frame: the inner corner is the origin, the outer corner is (1, 0), the second
 * axis points down the canvas, and a unit is the corner-to-corner width [width]. [upper], [lower] and [closed] are the
 * second coordinates of the lid edges at u = k / (SAMPLES - 1); [closed] is null when no closed line is drawn.
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
	/** The frame's second axis, perpendicular to the corners' line and pointing down the canvas. */
	private val downX: Float get() = if (axisX >= 0f) -axisY else axisY
	private val downY: Float get() = if (axisX >= 0f) axisX else -axisX

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
 * Reads [EyeFit]s off classified layers: the eye white's opaque outline gives the corners and both lid edges (artists
 * draw the white whole under the lashes), the closed-eye line its centre line, the iris its box. Each fit is checked
 * before use - the eye has a sensible width and opening, the upper edge lies above the lower one, the iris sits inside
 * the eye - and an eye that fails is left to the legacy rig, with the reason in [reasons].
 */
object EyeFitting {
	private const val ALPHA = 96

	class Result(val fits: Map<Side, EyeFit>, val reasons: Map<Side, String>)

	/**
	 * The fits of [layers]' eyes. [toRig] carries canvas points into the rig's aligned space; [faceCenterX] tells the
	 * inner corner from the outer one and splits a white drawn for both eyes on one layer.
	 */
	fun fit(layers: List<ClassifiedLayer>, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>): Result {
		val fits = LinkedHashMap<Side, EyeFit>()
		val reasons = LinkedHashMap<Side, String>()
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			// Side.LEFT is the character's left, on the canvas's right.
			fun onSide(x: Float) = if (side == Side.LEFT) x >= faceCenterX else x < faceCenterX
			fun candidates(tag: SemanticTag) = layers.filter {
				it.opaquePixels > 0 && it.semantic.tag == tag && it.semantic.role == null &&
					(it.semantic.side == side || it.semantic.side == Side.NONE && onSide(it.centroidX))
			}
			val white = candidates(SemanticTag.EYEWHITE).maxByOrNull { it.bounds.width * it.bounds.height }
			if (white == null) { reasons[side] = "no eye white"; continue }
			val columns = columns(white, ::onSide, white.semantic.side == Side.NONE)
			if (columns.size < 6) { reasons[side] = "the eye white is too small"; continue }
			val fit = fromColumns(side, columns, faceCenterX, toRig,
				candidates(SemanticTag.EYE_CLOSE).maxByOrNull { it.opaquePixels }, candidates(SemanticTag.IRIDES).maxByOrNull { it.opaquePixels })
			val problem = check(fit)
			if (problem != null) reasons[side] = problem else fits[side] = fit
		}
		return Result(fits, reasons)
	}

	/** Per opaque canvas column x of [layer], its topmost and bottommost opaque canvas y. */
	private fun columns(layer: ClassifiedLayer, onSide: (Float) -> Boolean, split: Boolean): List<Triple<Float, Float, Float>> {
		val raster = layer.source.raster
		val rgba = raster.rgba
		val out = ArrayList<Triple<Float, Float, Float>>()
		for (x in 0 until raster.width) {
			val canvasX = layer.source.bounds.left + x + 0.5f
			if (split && !onSide(canvasX)) continue
			var top = -1; var bottom = -1
			for (y in 0 until raster.height) if ((rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) >= ALPHA) { if (top < 0) top = y; bottom = y }
			if (top >= 0) out += Triple(canvasX, layer.source.bounds.top + top.toFloat(), layer.source.bounds.top + bottom + 1f)
		}
		return out
	}

	/** Per canvas column of [layer], its alpha-weighted centre y, for a drawn line. */
	private fun centreLine(layer: ClassifiedLayer): List<Pair<Float, Float>> {
		val raster = layer.source.raster
		val rgba = raster.rgba
		val out = ArrayList<Pair<Float, Float>>()
		for (x in 0 until raster.width) {
			var sum = 0f; var weighted = 0f
			for (y in 0 until raster.height) {
				val a = (rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff).toFloat()
				if (a < ALPHA) continue
				sum += a; weighted += (y + 0.5f) * a
			}
			if (sum > 0f) out += (layer.source.bounds.left + x + 0.5f) to (layer.source.bounds.top + weighted / sum)
		}
		return out
	}

	private fun fromColumns(
		side: Side, columns: List<Triple<Float, Float, Float>>, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>,
		closedLayer: ClassifiedLayer?, irisLayer: ClassifiedLayer?,
	): EyeFit {
		// Smooth each edge with a running median over a few columns: anti-aliased tips and stray pixels drop out.
		fun median(values: List<Float>, at: Int, reach: Int) = values.subList(max(0, at - reach), min(values.size, at + reach + 1)).sorted().let { it[it.size / 2] }
		val reach = max(1, columns.size / 40)
		// Then a running mean over a twelfth of the eye, so a lid edge is one smooth arc rather than a staircase of pixels.
		fun mean(values: List<Float>, at: Int, reach: Int) = values.subList(max(0, at - reach), min(values.size, at + reach + 1)).average().toFloat()
		val smooth = max(1, columns.size / 24)
		val tops = columns.indices.map { median(columns.map { it.second }, it, reach) }.let { m -> m.indices.map { mean(m, it, smooth) } }
		val bottoms = columns.indices.map { median(columns.map { it.third }, it, reach) }.let { m -> m.indices.map { mean(m, it, smooth) } }
		// The corners: the middle of the white at its two extreme columns, over the outermost few percent.
		val band = max(1, columns.size / 30)
		fun corner(range: IntRange) = range.map { columns[it].first }.average().toFloat() to range.map { (tops[it] + bottoms[it]) / 2f }.average().toFloat()
		val leftCorner = corner(0 until band)
		val rightCorner = corner(columns.size - band until columns.size)
		val leftInner = abs(leftCorner.first - faceCenterX) < abs(rightCorner.first - faceCenterX)
		val innerCanvas = if (leftInner) leftCorner else rightCorner
		val outerCanvas = if (leftInner) rightCorner else leftCorner
		val inner = toRig(innerCanvas.first, innerCanvas.second)
		val outer = toRig(outerCanvas.first, outerCanvas.second)
		val width = hypot(outer.first - inner.first, outer.second - inner.second).coerceAtLeast(1e-3f)
		val frame = EyeFit(side, inner.first, inner.second, (outer.first - inner.first) / width, (outer.second - inner.second) / width, width,
			emptyList(), emptyList(), null, null)
		// An edge sampled at u from inner to outer: the column at that canvas x, carried into the eye's frame.
		fun edge(values: List<Float>): List<Float> = (0 until EyeFit.SAMPLES).map { k ->
			val x = innerCanvas.first + (outerCanvas.first - innerCanvas.first) * EyeFit.u(k)
			val i = columns.indexOfFirst { it.first >= x }.let { if (it < 0) columns.lastIndex else it }
			val point = toRig(columns[i].first, values[i])
			frame.toEye(point.first, point.second).second
		}
		val upper = edge(tops).toMutableList()
		val lower = edge(bottoms).toMutableList()
		// The corners are where the two edges meet.
		upper[0] = 0f; lower[0] = 0f; upper[EyeFit.SAMPLES - 1] = 0f; lower[EyeFit.SAMPLES - 1] = 0f
		val closed = closedLayer?.let { layer ->
			val line = centreLine(layer).map { (x, y) -> toRig(x, y).let { frame.toEye(it.first, it.second) } }.sortedBy { it.first }
			// Only a line spanning most of the eye stands for its closed lid.
			if (line.size < 4 || line.last().first - line.first().first < 0.6f) null
			else (0 until EyeFit.SAMPLES).map { k ->
				val u = EyeFit.u(k)
				when {
					u <= line.first().first -> line.first().second * (u / line.first().first.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
					u >= line.last().first -> line.last().second * ((1f - u) / (1f - line.last().first).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
					else -> line.first { it.first >= u }.second
				}
			}
		}
		return frame.copy(upper = upper, lower = lower, closed = closed, iris = irisLayer?.bounds)
	}

	/** Why [fit] cannot be used, or null. */
	private fun check(fit: EyeFit): String? {
		if (fit.width < 6f) return "the eye is narrower than 6 px"
		val inner = 2 until EyeFit.SAMPLES - 2
		val opening = inner.maxOf { fit.lower[it] - fit.upper[it] }
		if (opening < 0.12f || opening > 1.3f) return "the eye opens ${"%.2f".format(opening)} of its width"
		if (inner.any { fit.upper[it] >= fit.lower[it] }) return "the upper lid edge is not above the lower one"
		if (abs(fit.axisY) > 0.6f) return "the corners lie too steep"
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
