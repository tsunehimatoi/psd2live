package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dense layered lip contour and inner-mouth frame extracted from layered mouth artwork (either independent cel lip
 * strokes or painted upper/lower lip skin patches over an inner mouth opening).
 */
class LayeredMouthFrame(
	val hasLines: Boolean,
	val centerX: Float,
	val centerY: Float,
	val exX: Float,
	val exY: Float,
	val frameWidth: Float,
	val upperWorld65: FloatArray,
	val lowerWorld65: FloatArray,
	val upperLocal65: FloatArray,
	val lowerLocal65: FloatArray,
	val lineNorm: Float,
	val openColsX: FloatArray,
	val openTopY: FloatArray,
	val openBotY: FloatArray,
	val upperLipBoundsLocal: Bounds?,
	val lowerLipBoundsLocal: Bounds?,
	val tongueBoundsLocal: Bounds?,
	val hUtVis0: Float,
	val utSideReserve: Float,
	val hLtVis0: Float,
) {
	val eyX: Float get() = -exY
	val eyY: Float get() = exX

	fun toLocalPx(rigX: Float, rigY: Float): Pair<Float, Float> {
		val dx = rigX - centerX
		val dy = rigY - centerY
		return (dx * exX + dy * exY) to (dx * eyX + dy * eyY)
	}

	fun localDeltaToRig(dxLoc: Float, dyLoc: Float): Pair<Float, Float> =
		(dxLoc * exX + dyLoc * eyX) to (dxLoc * exY + dyLoc * eyY)

	fun localPxToRig(xLoc: Float, yLoc: Float): Pair<Float, Float> =
		(centerX + xLoc * exX + yLoc * eyX) to (centerY + xLoc * exY + yLoc * eyY)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is LayeredMouthFrame) return false
		return hasLines == other.hasLines &&
			centerX == other.centerX && centerY == other.centerY &&
			exX == other.exX && exY == other.exY &&
			frameWidth == other.frameWidth &&
			upperWorld65.contentEquals(other.upperWorld65) &&
			lowerWorld65.contentEquals(other.lowerWorld65) &&
			upperLocal65.contentEquals(other.upperLocal65) &&
			lowerLocal65.contentEquals(other.lowerLocal65) &&
			lineNorm == other.lineNorm &&
			openColsX.contentEquals(other.openColsX) &&
			openTopY.contentEquals(other.openTopY) &&
			openBotY.contentEquals(other.openBotY) &&
			upperLipBoundsLocal == other.upperLipBoundsLocal &&
			lowerLipBoundsLocal == other.lowerLipBoundsLocal &&
			tongueBoundsLocal == other.tongueBoundsLocal &&
			hUtVis0 == other.hUtVis0 &&
			utSideReserve == other.utSideReserve &&
			hLtVis0 == other.hLtVis0
	}

	override fun hashCode(): Int {
		var h = hasLines.hashCode()
		h = 31 * h + centerX.hashCode()
		h = 31 * h + centerY.hashCode()
		h = 31 * h + frameWidth.hashCode()
		h = 31 * h + upperLocal65.contentHashCode()
		h = 31 * h + lowerLocal65.contentHashCode()
		h = 31 * h + lineNorm.hashCode()
		h = 31 * h + hUtVis0.hashCode()
		h = 31 * h + utSideReserve.hashCode()
		h = 31 * h + hLtVis0.hashCode()
		return h
	}
}

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
	internal val layered: LayeredMouthFrame? = null,
) {
	val cornerV: Float get() = upper.last()
	val hasLines: Boolean get() = layered?.hasLines ?: true
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
		fit.layered?.let { frame ->
			return if (frame.hasLines && frame.lineNorm > 0f) frame.lineNorm * (frame.frameWidth / fit.width.coerceAtLeast(1e-4f)) else null
		}
		val lines = layers.filter { it.opaquePixels > 0 && it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
			(it.semantic.role == PartRole.UPPER_LINE || it.semantic.role == PartRole.LOWER_LINE) }
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
		val lines = setOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE)
		val mouths = layers.filter { it.opaquePixels > 0 && !it.source.clipped && (it.semantic.role == null || it.semantic.role in lines) &&
			it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && !io.github.psd2live.core.MouthLipLayer.isLipId(it.source.id.raw) }
		val main = mouths.filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null }.maxByOrNull { it.opaquePixels }
			?: mouths.filter { it.semantic.role == null }.maxByOrNull { it.opaquePixels } ?: return Result(null, "no mouth", emptyList())
		val own = mouths.filter { it === main || it.semantic.variant == main.semantic.variant && it.semantic.side == main.semantic.side &&
			it.bounds.left < main.bounds.right && main.bounds.left < it.bounds.right && it.bounds.top < main.bounds.bottom && main.bounds.top < it.bounds.bottom }
		val edges = own.filter { it.semantic.role in lines }.takeIf { drawn -> drawn.any { it.semantic.role == PartRole.UPPER_LINE } &&
			drawn.any { it.semantic.role == PartRole.LOWER_LINE } } ?: own
		val points = ArrayList<Pair<Float, Float>>()
		val upperLine = ArrayList<Pair<Float, Float>>(); val lowerLine = ArrayList<Pair<Float, Float>>()
		for (layer in edges) {
			val raster = layer.source.raster; val bounds = layer.source.bounds
			val sx = bounds.width / raster.width.coerceAtLeast(1).toFloat(); val sy = bounds.height / raster.height.coerceAtLeast(1).toFloat()
			val lineOut = when (layer.semantic.role) {
				PartRole.UPPER_LINE -> upperLine
				PartRole.LOWER_LINE -> lowerLine
				else -> null
			}
			for (y in 0 until raster.height) for (x in 0 until raster.width) {
				if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < 96) continue
				val p = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
				points += p; lineOut?.add(p)
			}
		}
		if (points.size < 24) return Result(null, "the mouth is too small", own)
		val (lx, ly) = level
		fun along(p: Pair<Float, Float>) = p.first * lx + p.second * ly
		val lo = points.minOf(::along); val hi = points.maxOf(::along)
		if (hi - lo < 6f) return Result(null, "the mouth is too narrow", own)
		fun corner(near: Float): Pair<Float, Float> {
			val band = points.filter { abs(along(it) - near) <= (hi - lo) * 0.03f }
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
			return List(n) { k ->
				when (k) {
					0 -> 0f
					n - 1 -> cornerV
					else -> (raw[k - 1] + 2f * raw[k] + raw[k + 1]) / 4f
				}
			}
		}
		val baseFit = frame.copy(upper = line(tops), lower = line(bottoms))
		if (baseFit.opening < 0.12f) return Result(null, "the mouth is drawn closed", own)
		if (baseFit.opening > 1.6f) return Result(null, "the mouth is taller than it is wide", own)
		val layeredFrame = buildLayeredFrame(main, layers, level, toRig)
		return Result(baseFit.copy(layered = layeredFrame), null, own)
	}

	private fun buildLayeredFrame(
		main: ClassifiedLayer,
		allLayers: List<ClassifiedLayer>,
		level: Pair<Float, Float>,
		toRig: (Float, Float) -> Pair<Float, Float>,
	): LayeredMouthFrame? {
		val related = allLayers.filter {
			it.opaquePixels > 0 &&
				it.semantic.variant == main.semantic.variant &&
				it.semantic.side == main.semantic.side &&
				it.semantic.tag in FittedMouth.TAGS &&
				!io.github.psd2live.core.MouthLipLayer.isLipId(it.source.id.raw)
		}
		val upperLineLayer = related.filter { it.semantic.role == PartRole.UPPER_LINE }.maxByOrNull { it.opaquePixels }
		val lowerLineLayer = related.filter { it.semantic.role == PartRole.LOWER_LINE }.maxByOrNull { it.opaquePixels }
		val upperLipLayers = related.filter { it.semantic.role == PartRole.UPPER_LIP }
		val lowerLipLayers = related.filter { it.semantic.role == PartRole.LOWER_LIP }

		val hasLines = upperLineLayer != null && lowerLineLayer != null
		val hasLipSkin = upperLipLayers.isNotEmpty() && lowerLipLayers.isNotEmpty()
		if (!hasLines && !hasLipSkin) return null

		val uDense = MouthThreeLayerModel.DEFAULT.uDense
		val stations = uDense.size

		val upperWorld65: FloatArray
		val lowerWorld65: FloatArray
		val linePx: Float
		if (hasLines) {
			val upStroke = extractMedialStroke(upperLineLayer!!, level, toRig, uDense) ?: return null
			val loStroke = extractMedialStroke(lowerLineLayer!!, level, toRig, uDense) ?: return null
			upperWorld65 = upStroke.first
			lowerWorld65 = loStroke.first
			linePx = 0.5f * (upStroke.second + loStroke.second)
		} else {
			val rims = extractOpeningRims(main, level, toRig, uDense) ?: return null
			upperWorld65 = rims.first
			lowerWorld65 = rims.second
			linePx = 0f
		}

		val lastIdx = (stations - 1) * 2
		val ax = 0.5f * (upperWorld65[0] + lowerWorld65[0])
		val ay = 0.5f * (upperWorld65[1] + lowerWorld65[1])
		val bx = 0.5f * (upperWorld65[lastIdx] + lowerWorld65[lastIdx])
		val by = 0.5f * (upperWorld65[lastIdx + 1] + lowerWorld65[lastIdx + 1])
		val frameWidth = hypot(bx - ax, by - ay).coerceAtLeast(1f)
		val exX = (bx - ax) / frameWidth
		val exY = (by - ay) / frameWidth
		val eyX = -exY
		val eyY = exX
		val centerX = 0.5f * (ax + bx)
		val centerY = 0.5f * (ay + by)

		val upperLocal65 = FloatArray(stations * 2)
		val lowerLocal65 = FloatArray(stations * 2)
		for (i in 0 until stations) {
			val udx = upperWorld65[i * 2] - centerX
			val udy = upperWorld65[i * 2 + 1] - centerY
			upperLocal65[i * 2] = (udx * exX + udy * exY) / frameWidth
			upperLocal65[i * 2 + 1] = (udx * eyX + udy * eyY) / frameWidth

			val ldx = lowerWorld65[i * 2] - centerX
			val ldy = lowerWorld65[i * 2 + 1] - centerY
			lowerLocal65[i * 2] = (ldx * exX + ldy * exY) / frameWidth
			lowerLocal65[i * 2 + 1] = (ldx * eyX + ldy * eyY) / frameWidth
		}

		val (openColsX, openTopY, openBotY) = extractOpeningColumnsLocal(main, centerX, centerY, exX, exY, eyX, eyY, toRig)
			?: return null

		fun projectLayerBoundsLocal(candidates: List<ClassifiedLayer>): Bounds? {
			if (candidates.isEmpty()) return null
			var minX = Float.POSITIVE_INFINITY
			var minY = Float.POSITIVE_INFINITY
			var maxX = Float.NEGATIVE_INFINITY
			var maxY = Float.NEGATIVE_INFINITY
			for (l in candidates) {
				val b = l.bounds
				for ((cx, cy) in listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)) {
					val (rx, ry) = toRig(cx, cy)
					val dx = rx - centerX; val dy = ry - centerY
					val lx = dx * exX + dy * exY
					val ly = dx * eyX + dy * eyY
					if (lx < minX) minX = lx
					if (lx > maxX) maxX = lx
					if (ly < minY) minY = ly
					if (ly > maxY) maxY = ly
				}
			}
			return if (minX.isFinite()) Bounds(minX, minY, maxX, maxY) else null
		}

		val upperLipBoundsLocal = projectLayerBoundsLocal(upperLipLayers)
		val lowerLipBoundsLocal = projectLayerBoundsLocal(lowerLipLayers)
		val tongueBoundsLocal = projectLayerBoundsLocal(related.filter { it.semantic.tag == SemanticTag.TONGUE })

		val mid = stations / 2
		val u0MidYPx = upperLocal65[mid * 2 + 1] * frameWidth
		val l0MidYPx = lowerLocal65[mid * 2 + 1] * frameWidth
		val h0Px = max(l0MidYPx - u0MidYPx, 1f)

		val uxMono0 = FloatArray(stations)
		val u0YPx = FloatArray(stations)
		for (i in 0 until stations) {
			val xPx = upperLocal65[i * 2] * frameWidth
			uxMono0[i] = if (i == 0) xPx else max(uxMono0[i - 1], xPx)
			u0YPx[i] = upperLocal65[i * 2 + 1] * frameWidth
		}

		var hUtVis0 = 0.20f * h0Px
		var utSideReserve = 0.12f * frameWidth
		val upperTeethLayers = related.filter { it.semantic.tag == SemanticTag.TOOTH_T }
		if (upperTeethLayers.isNotEmpty()) {
			val midYs = ArrayList<Float>()
			val nBins = 16
			val binLo = -0.42f * frameWidth
			val binHi = 0.42f * frameWidth
			val binStep = (binHi - binLo) / nBins
			val binYs = Array(nBins) { ArrayList<Float>() }
			for (tl in upperTeethLayers) {
				val raster = tl.source.raster; val bounds = tl.source.bounds
				val sx = bounds.width / raster.width.coerceAtLeast(1).toFloat()
				val sy = bounds.height / raster.height.coerceAtLeast(1).toFloat()
				for (y in 0 until raster.height) for (x in 0 until raster.width) {
					if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) <= 64) continue
					val (rx, ry) = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
					val dx = rx - centerX; val dy = ry - centerY
					val lx = dx * exX + dy * exY
					val ly = dx * eyX + dy * eyY
					if (abs(lx) <= 0.12f * frameWidth) midYs += ly
					if (lx >= binLo && lx < binHi) {
						val bi = ((lx - binLo) / binStep).toInt().coerceIn(0, nBins - 1)
						binYs[bi] += ly
					}
				}
			}
			if (midYs.isNotEmpty()) {
				val utBotMid = percentile(midYs, 0.98f)
				hUtVis0 = max(1f, utBotMid - u0MidYPx)
			}
			var minReserve = Float.POSITIVE_INFINITY
			for (bi in 0 until nBins) {
				val bCenter = binLo + (bi + 0.5f) * binStep
				if (binYs[bi].size >= 5 && abs(bCenter) >= 0.18f * frameWidth) {
					val topY = percentile(binYs[bi], 0.02f)
					val uY = interp1D(bCenter, uxMono0, u0YPx)
					minReserve = min(minReserve, max(0f, uY - topY))
				}
			}
			if (minReserve.isFinite()) utSideReserve = minReserve
		}

		var hLtVis0 = 0.10f * h0Px
		val lowerTeethLayers = related.filter { it.semantic.tag == SemanticTag.TOOTH_B }
		if (lowerTeethLayers.isNotEmpty()) {
			val midYs = ArrayList<Float>()
			for (tl in lowerTeethLayers) {
				val raster = tl.source.raster; val bounds = tl.source.bounds
				val sx = bounds.width / raster.width.coerceAtLeast(1).toFloat()
				val sy = bounds.height / raster.height.coerceAtLeast(1).toFloat()
				for (y in 0 until raster.height) for (x in 0 until raster.width) {
					if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) <= 64) continue
					val (rx, ry) = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
					val dx = rx - centerX; val dy = ry - centerY
					val lx = dx * exX + dy * exY
					val ly = dx * eyX + dy * eyY
					if (abs(lx) <= 0.12f * frameWidth) midYs += ly
				}
			}
			if (midYs.isNotEmpty()) {
				val ltTopMid = percentile(midYs, 0.02f)
				hLtVis0 = max(1f, l0MidYPx - ltTopMid)
			}
		}

		return LayeredMouthFrame(
			hasLines = hasLines,
			centerX = centerX,
			centerY = centerY,
			exX = exX,
			exY = exY,
			frameWidth = frameWidth,
			upperWorld65 = upperWorld65,
			lowerWorld65 = lowerWorld65,
			upperLocal65 = upperLocal65,
			lowerLocal65 = lowerLocal65,
			lineNorm = linePx / frameWidth,
			openColsX = openColsX,
			openTopY = openTopY,
			openBotY = openBotY,
			upperLipBoundsLocal = upperLipBoundsLocal,
			lowerLipBoundsLocal = lowerLipBoundsLocal,
			tongueBoundsLocal = tongueBoundsLocal,
			hUtVis0 = hUtVis0,
			utSideReserve = utSideReserve,
			hLtVis0 = hLtVis0,
		)
	}

	/**
	 * Extracts a smooth 1D medial stroke ridge curve and median stroke thickness from an independent lip-line layer.
	 */
	private fun extractMedialStroke(
		layer: ClassifiedLayer,
		level: Pair<Float, Float>,
		toRig: (Float, Float) -> Pair<Float, Float>,
		uDense: FloatArray,
	): Pair<FloatArray, Float>? {
		val raster = layer.source.raster
		val bounds = layer.source.bounds
		val rw = raster.width
		val rh = raster.height
		if (rw <= 0 || rh <= 0) return null

		val pad = 4
		val w = rw + 2 * pad
		val h = rh + 2 * pad
		val alpha = FloatArray(w * h)
		val lum = FloatArray(w * h)
		val opaqueLums = ArrayList<Float>()
		for (y in 0 until rh) {
			for (x in 0 until rw) {
				val idx = (y * rw + x) * 4
				val a = (raster.rgba[idx + 3].toInt() and 0xff) / 255f
				if (a <= 0f) continue
				val r = (raster.rgba[idx].toInt() and 0xff) / 255f
				val g = (raster.rgba[idx + 1].toInt() and 0xff) / 255f
				val b = (raster.rgba[idx + 2].toInt() and 0xff) / 255f
				val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
				val dst = (y + pad) * w + (x + pad)
				alpha[dst] = a
				lum[dst] = l
				if (a > 0.65f) opaqueLums += l
			}
		}
		val weight = alpha.copyOf()
		if (opaqueLums.size >= 8) {
			val dark = percentile(opaqueLums, 0.15f)
			val light = percentile(opaqueLums, 0.85f)
			if (light - dark >= 0.055f) {
				val colLums = ArrayList<Float>(h)
				for (x in 0 until w) {
					var colMaxA = 0f
					for (y in 0 until h) colMaxA = max(colMaxA, alpha[y * w + x])
					val aCut = max(0.18f, 0.65f * colMaxA)
					colLums.clear()
					for (y in 0 until h) if (alpha[y * w + x] > aCut) colLums += lum[y * w + x]
					if (colLums.size < 2) continue
					val locDark = percentile(colLums, 0.20f)
					val locLight = percentile(colLums, 0.85f)
					val cutoff = locDark + max(0.035f, 0.28f * (locLight - locDark))
					for (y in 0 until h) {
						val idx = y * w + x
						val factor = ((cutoff + 0.035f - lum[idx]) / 0.035f).coerceIn(0f, 1f)
						weight[idx] *= factor
					}
				}
			}
		}

		var maxWeight = 0f
		for (v in weight) if (v > maxWeight) maxWeight = v
		if (maxWeight <= 0.08f) return null
		val thresh = max(0.08f, 0.16f * maxWeight)

		// 8-connected components on (weight > thresh)
		val labels = IntArray(w * h)
		data class CompInfo(val id: Int, val area: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
		val comps = ArrayList<CompInfo>()
		val queue = IntArray(w * h)
		var nextId = 1
		for (i in 0 until w * h) {
			if (weight[i] <= thresh || labels[i] != 0) continue
			val cid = nextId++
			labels[i] = cid
			var head = 0; var tail = 0
			queue[tail++] = i
			var minX = i % w; var maxX = minX
			var minY = i / w; var maxY = minY
			while (head < tail) {
				val cur = queue[head++]
				val cx = cur % w; val cy = cur / w
				if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
				if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
				for (dy in -1..1) for (dx in -1..1) {
					if (dx == 0 && dy == 0) continue
					val nx = cx + dx; val ny = cy + dy
					if (nx in 0 until w && ny in 0 until h) {
						val ni = ny * w + nx
						if (weight[ni] > thresh && labels[ni] == 0) {
							labels[ni] = cid
							queue[tail++] = ni
						}
					}
				}
			}
			comps += CompInfo(cid, tail, minX, maxX, minY, maxY)
		}
		if (comps.isEmpty()) return null
		val mainArea = comps.maxOf { it.area }
		val selected = comps.filter {
			it.area >= 0.03f * mainArea && max(it.maxX - it.minX + 1, it.maxY - it.minY + 1) >= 0.08f * rw
		}.ifEmpty { listOf(comps.maxBy { it.area }) }.sortedBy { it.minX }

		val centersX = ArrayList<Float>()
		val centersY = ArrayList<Float>()
		val thicknesses = ArrayList<Float>()
		val sx = bounds.width / rw.coerceAtLeast(1).toFloat()
		val sy = bounds.height / rh.coerceAtLeast(1).toFloat()

		for (comp in selected) {
			val mask = BooleanArray(w * h) { labels[it] == comp.id }
			val dist = exactDistanceTransformL2(mask, w, h)
			var nNodes = 0
			for (m in mask) if (m) nNodes++
			if (nNodes < 3) continue
			val xs = IntArray(nNodes)
			val ys = IntArray(nNodes)
			val vid = IntArray(w * h) { -1 }
			var maxDist = 1e-6f
			var maxCompW = 1e-6f
			var ptr = 0
			for (i in 0 until w * h) {
				if (!mask[i]) continue
				vid[i] = ptr
				xs[ptr] = i % w
				ys[ptr] = i / w
				if (dist[i] > maxDist) maxDist = dist[i]
				if (weight[i] > maxCompW) maxCompW = weight[i]
				ptr++
			}
			val score = FloatArray(nNodes) { k ->
				val idx = ys[k] * w + xs[k]
				val dn = dist[idx] / maxDist
				dn * dn * (weight[idx] / maxCompW)
			}

			// Build 8-neighbor adjacency lists (max degree 8)
			val deg = IntArray(nNodes)
			val nbr = IntArray(nNodes * 8)
			val geoCost = FloatArray(nNodes * 8)
			val ridgeCost = FloatArray(nNodes * 8)
			val dirs = arrayOf(
				intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
				intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
			)
			for (u in 0 until nNodes) {
				val ux = xs[u]; val uy = ys[u]
				var d = 0
				for (dir in dirs) {
					val nx = ux + dir[0]; val ny = uy + dir[1]
					if (nx !in 0 until w || ny !in 0 until h) continue
					val v = vid[ny * w + nx]
					if (v < 0) continue
					val step = if (dir[0] != 0 && dir[1] != 0) 1.4142135f else 1f
					val slot = u * 8 + d
					nbr[slot] = v
					geoCost[slot] = step
					ridgeCost[slot] = step / (0.015f + 0.5f * (score[u] + score[v]))
					d++
				}
				deg[u] = d
			}

			val (d0, _) = dijkstraGrid(nNodes, deg, nbr, geoCost, 0)
			var tip0 = 0
			for (k in 1 until nNodes) if (d0[k].isFinite() && d0[k] > d0[tip0]) tip0 = k
			val (d1, _) = dijkstraGrid(nNodes, deg, nbr, geoCost, tip0)
			var tip1 = tip0
			for (k in 0 until nNodes) if (d1[k].isFinite() && d1[k] > d1[tip1]) tip1 = k

			val p0Rig = toRig(bounds.left + (xs[tip0] - pad + 0.5f) * sx, bounds.top + (ys[tip0] - pad + 0.5f) * sy)
			val p1Rig = toRig(bounds.left + (xs[tip1] - pad + 0.5f) * sx, bounds.top + (ys[tip1] - pad + 0.5f) * sy)
			val along0 = p0Rig.first * level.first + p0Rig.second * level.second
			val along1 = p1Rig.first * level.first + p1Rig.second * level.second
			val start = if (along0 <= along1) tip0 else tip1
			val end = if (along0 <= along1) tip1 else tip0

			val (_, pred) = dijkstraGrid(nNodes, deg, nbr, ridgeCost, start)
			val pathIdx = ArrayList<Int>()
			var cur = end
			while (cur >= 0) {
				pathIdx += cur
				if (cur == start) break
				cur = pred[cur]
			}
			pathIdx.reverse()
			if (pathIdx.size < 2) continue

			val rawX = FloatArray(pathIdx.size) { xs[pathIdx[it]] + 0.5f }
			val rawY = FloatArray(pathIdx.size) { ys[pathIdx[it]] + 0.5f }
			val arc = FloatArray(pathIdx.size)
			for (i in 1 until pathIdx.size) {
				arc[i] = arc[i - 1] + hypot(rawX[i] - rawX[i - 1], rawY[i] - rawY[i - 1])
			}
			val totalArc = arc.last()
			if (totalArc < 3f) continue

			val mCount = max(33, ceil(totalArc / 2f).toInt())
			val ptsX = FloatArray(mCount)
			val ptsY = FloatArray(mCount)
			for (m in 0 until mCount) {
				val s = totalArc * m / (mCount - 1).toFloat()
				ptsX[m] = interp1D(s, arc, rawX)
				ptsY[m] = interp1D(s, arc, rawY)
			}
			val smX = gaussianSmooth1D(ptsX, 5f)
			val smY = gaussianSmooth1D(ptsY, 5f)
			for (m in 0 until mCount) {
				val taper = (min(m, mCount - 1 - m) / 10f).coerceIn(0f, 1f)
				ptsX[m] = ptsX[m] * (1f - taper) + smX[m] * taper
				ptsY[m] = ptsY[m] * (1f - taper) + smY[m] * taper
			}

			val win = min(6, mCount - 1)
			val maxR = min(90f, maxDist * 2.4f + 6f)
			val nRay = (4f * maxR).toInt() + 1
			val rSteps = FloatArray(nRay) { -maxR + 2f * maxR * it / (nRay - 1).coerceAtLeast(1) }

			for (m in 0 until mCount) {
				val iPrev = max(0, m - win)
				val iNext = min(mCount - 1, m + win)
				val tdx = ptsX[iNext] - ptsX[iPrev]
				val tdy = ptsY[iNext] - ptsY[iPrev]
				val tLen = hypot(tdx, tdy).coerceAtLeast(1e-8f)
				val nx = -tdy / tLen
				val ny = tdx / tLen

				val localR = max(4f, dist[ptsY[m].toInt().coerceIn(0, h - 1) * w + ptsX[m].toInt().coerceIn(0, w - 1)] + 4f)
				val wRay = FloatArray(nRay)
				var peakIdx = 0
				var peakVal = -1f
				for (ri in 0 until nRay) {
					val rx = ptsX[m] + rSteps[ri] * nx - 0.5f
					val ry = ptsY[m] + rSteps[ri] * ny - 0.5f
					val wv = sampleMaskedBilinear(weight, mask, w, h, rx, ry)
					wRay[ri] = wv
					if (abs(rSteps[ri]) <= localR && wv > peakVal) {
						peakVal = wv
						peakIdx = ri
					}
				}
				var cx = ptsX[m]
				var cy = ptsY[m]
				var th = 2f
				if (peakVal > 0.05f) {
					val goodCut = max(0.08f, 0.20f * peakVal)
					var i0 = peakIdx; var i1 = peakIdx
					while (i0 > 0 && wRay[i0 - 1] > goodCut) i0--
					while (i1 + 1 < nRay && wRay[i1 + 1] > goodCut) i1++
					var sumW = 0f; var sumRW = 0f
					for (ri in i0..i1) {
						sumW += wRay[ri]
						sumRW += rSteps[ri] * wRay[ri]
					}
					val rc = if (sumW > 1e-6f) sumRW / sumW else 0f
					cx = ptsX[m] + rc * nx
					cy = ptsY[m] + rc * ny
					val e0 = toRig(bounds.left + (ptsX[m] + rSteps[i0] * nx - pad) * sx, bounds.top + (ptsY[m] + rSteps[i0] * ny - pad) * sy)
					val e1 = toRig(bounds.left + (ptsX[m] + rSteps[i1] * nx - pad) * sx, bounds.top + (ptsY[m] + rSteps[i1] * ny - pad) * sy)
					th = hypot(e1.first - e0.first, e1.second - e0.second)
				}
				val worldPt = toRig(bounds.left + (cx - pad) * sx, bounds.top + (cy - pad) * sy)
				centersX += worldPt.first
				centersY += worldPt.second
				thicknesses += th
			}
		}

		if (centersX.size < 2) return null
		// Ensure overall left-to-right ordering along head level
		val firstAlong = centersX.first() * level.first + centersY.first() * level.second
		val lastAlong = centersX.last() * level.first + centersY.last() * level.second
		if (firstAlong > lastAlong) {
			centersX.reverse()
			centersY.reverse()
		}

		val cxArr = centersX.toFloatArray()
		val cyArr = centersY.toFloatArray()
		val sampled = resampleArcLength(cxArr, cyArr, uDense)
		val medianTh = if (thicknesses.isEmpty()) 2f else percentile(thicknesses, 0.5f)
		return sampled to medianTh
	}

	private fun extractOpeningRims(
		opening: ClassifiedLayer,
		level: Pair<Float, Float>,
		toRig: (Float, Float) -> Pair<Float, Float>,
		uDense: FloatArray,
	): Pair<FloatArray, FloatArray>? {
		val raster = opening.source.raster
		val bounds = opening.source.bounds
		val rw = raster.width
		val rh = raster.height
		if (rw <= 0 || rh <= 0) return null
		val sx = bounds.width / rw.coerceAtLeast(1).toFloat()
		val sy = bounds.height / rh.coerceAtLeast(1).toFloat()

		val xsRig = ArrayList<Float>()
		val topRigX = ArrayList<Float>(); val topRigY = ArrayList<Float>()
		val botRigX = ArrayList<Float>(); val botRigY = ArrayList<Float>()

		for (c in 0 until rw) {
			var yFirst = -1
			var yLast = -1
			for (y in 0 until rh) {
				val a = (raster.rgba[(y * rw + c) * 4 + 3].toInt() and 0xff) / 255f
				if (a > 0.35f) {
					if (yFirst < 0) yFirst = y
					yLast = y
				}
			}
			if (yFirst >= 0) {
				val tp = toRig(bounds.left + (c + 0.5f) * sx, bounds.top + (yFirst + 0.5f) * sy)
				val bp = toRig(bounds.left + (c + 0.5f) * sx, bounds.top + (yLast + 0.5f) * sy)
				xsRig += tp.first * level.first + tp.second * level.second
				topRigX += tp.first; topRigY += tp.second
				botRigX += bp.first; botRigY += bp.second
			}
		}
		if (topRigX.size < 4) return null
		if (xsRig.first() > xsRig.last()) {
			topRigX.reverse(); topRigY.reverse()
			botRigX.reverse(); botRigY.reverse()
		}
		val smTopX = gaussianSmooth1D(topRigX.toFloatArray(), 2.5f)
		val smTopY = gaussianSmooth1D(topRigY.toFloatArray(), 2.5f)
		val smBotX = gaussianSmooth1D(botRigX.toFloatArray(), 2.5f)
		val smBotY = gaussianSmooth1D(botRigY.toFloatArray(), 2.5f)
		return resampleArcLength(smTopX, smTopY, uDense) to resampleArcLength(smBotX, smBotY, uDense)
	}

	private fun extractOpeningColumnsLocal(
		opening: ClassifiedLayer,
		centerX: Float,
		centerY: Float,
		exX: Float,
		exY: Float,
		eyX: Float,
		eyY: Float,
		toRig: (Float, Float) -> Pair<Float, Float>,
	): Triple<FloatArray, FloatArray, FloatArray>? {
		val raster = opening.source.raster
		val bounds = opening.source.bounds
		val rw = raster.width
		val rh = raster.height
		if (rw <= 0 || rh <= 0) return null
		val sx = bounds.width / rw.coerceAtLeast(1).toFloat()
		val sy = bounds.height / rh.coerceAtLeast(1).toFloat()

		// Fast exact raster column scan when lip axis is aligned with horizontal rig X
		if (abs(exY) < 1e-3f && exX > 0f) {
			val colsX = ArrayList<Float>()
			val topY = ArrayList<Float>()
			val botY = ArrayList<Float>()
			for (c in 0 until rw) {
				var yFirst = -1; var yLast = -1
				for (y in 0 until rh) {
					val a = (raster.rgba[(y * rw + c) * 4 + 3].toInt() and 0xff) / 255f
					if (a > 0.15f) {
						if (yFirst < 0) yFirst = y
						yLast = y
					}
				}
				if (yFirst >= 0) {
					val tp = toRig(bounds.left + (c + 0.5f) * sx, bounds.top + (yFirst + 0.5f) * sy)
					val bp = toRig(bounds.left + (c + 0.5f) * sx, bounds.top + (yLast + 0.5f) * sy)
					colsX += (tp.first - centerX) * exX + (tp.second - centerY) * exY
					topY += (tp.first - centerX) * eyX + (tp.second - centerY) * eyY
					botY += (bp.first - centerX) * eyX + (bp.second - centerY) * eyY
				}
			}
			if (colsX.size < 2) return null
			return Triple(colsX.toFloatArray(), topY.toFloatArray(), botY.toFloatArray())
		}

		// General rotated frame binning
		var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
		val lxList = ArrayList<Float>(); val lyList = ArrayList<Float>()
		for (y in 0 until rh) for (x in 0 until rw) {
			val a = (raster.rgba[(y * rw + x) * 4 + 3].toInt() and 0xff) / 255f
			if (a <= 0.15f) continue
			val (rx, ry) = toRig(bounds.left + (x + 0.5f) * sx, bounds.top + (y + 0.5f) * sy)
			val lx = (rx - centerX) * exX + (ry - centerY) * exY
			val ly = (rx - centerX) * eyX + (ry - centerY) * eyY
			lxList += lx; lyList += ly
			if (lx < minX) minX = lx; if (lx > maxX) maxX = lx
		}
		if (lxList.size < 4 || maxX - minX < 2f) return null
		val nBins = max(8, ceil(maxX - minX).toInt())
		val step = (maxX - minX) / nBins
		val tops = FloatArray(nBins) { Float.POSITIVE_INFINITY }
		val bots = FloatArray(nBins) { Float.NEGATIVE_INFINITY }
		for (i in lxList.indices) {
			val bi = ((lxList[i] - minX) / step).toInt().coerceIn(0, nBins - 1)
			if (lyList[i] < tops[bi]) tops[bi] = lyList[i]
			if (lyList[i] > bots[bi]) bots[bi] = lyList[i]
		}
		val colsX = ArrayList<Float>(); val topY = ArrayList<Float>(); val botY = ArrayList<Float>()
		for (bi in 0 until nBins) {
			if (tops[bi] <= bots[bi]) {
				colsX += minX + (bi + 0.5f) * step
				topY += tops[bi]
				botY += bots[bi]
			}
		}
		if (colsX.size < 2) return null
		return Triple(colsX.toFloatArray(), topY.toFloatArray(), botY.toFloatArray())
	}

	private fun resampleArcLength(xs: FloatArray, ys: FloatArray, uDense: FloatArray): FloatArray {
		val n = xs.size
		val arc = FloatArray(n)
		for (i in 1 until n) arc[i] = arc[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
		val total = arc.last().coerceAtLeast(1e-6f)
		for (i in 0 until n) arc[i] /= total
		val out = FloatArray(uDense.size * 2)
		for (i in uDense.indices) {
			val u = uDense[i]
			out[i * 2] = interp1D(u, arc, xs)
			out[i * 2 + 1] = interp1D(u, arc, ys)
		}
		return out
	}

	internal fun interp1D(x: Float, xp: FloatArray, fp: FloatArray): Float {
		if (xp.isEmpty()) return 0f
		if (x <= xp.first()) return fp.first()
		if (x >= xp.last()) return fp.last()
		var lo = 0
		var hi = xp.lastIndex
		while (lo + 1 < hi) {
			val mid = (lo + hi) ushr 1
			if (xp[mid] <= x) lo = mid else hi = mid
		}
		val span = xp[hi] - xp[lo]
		if (span <= 1e-8f) return fp[lo]
		val t = ((x - xp[lo]) / span).coerceIn(0f, 1f)
		return fp[lo] + (fp[hi] - fp[lo]) * t
	}

	private fun gaussianSmooth1D(values: FloatArray, sigma: Float): FloatArray {
		val radius = max(1, ceil(3f * sigma).toInt())
		val kernel = FloatArray(2 * radius + 1)
		var sum = 0f
		for (i in -radius..radius) {
			val w = exp(-(i * i) / (2f * sigma * sigma))
			kernel[i + radius] = w
			sum += w
		}
		for (i in kernel.indices) kernel[i] /= sum
		return FloatArray(values.size) { idx ->
			var acc = 0f
			for (k in -radius..radius) {
				val j = (idx + k).coerceIn(0, values.lastIndex)
				acc += values[j] * kernel[k + radius]
			}
			acc
		}
	}

	private fun sampleMaskedBilinear(weight: FloatArray, mask: BooleanArray, w: Int, h: Int, x: Float, y: Float): Float {
		if (x < 0f || y < 0f || x > w - 1f || y > h - 1f) return 0f
		val x0 = x.toInt().coerceIn(0, w - 1)
		val y0 = y.toInt().coerceIn(0, h - 1)
		val x1 = (x0 + 1).coerceAtMost(w - 1)
		val y1 = (y0 + 1).coerceAtMost(h - 1)
		val fx = x - x0
		val fy = y - y0
		fun at(px: Int, py: Int): Float {
			val i = py * w + px
			return if (mask[i]) weight[i] else 0f
		}
		val v00 = at(x0, y0); val v10 = at(x1, y0)
		val v01 = at(x0, y1); val v11 = at(x1, y1)
		return (v00 * (1f - fx) + v10 * fx) * (1f - fy) + (v01 * (1f - fx) + v11 * fx) * fy
	}

	/** Exact 2D Euclidean distance transform inside [mask] (distance to nearest `false` pixel). */
	private fun exactDistanceTransformL2(mask: BooleanArray, w: Int, h: Int): FloatArray {
		val inf = 1e12f
		val colDist2 = FloatArray(w * h)
		for (x in 0 until w) {
			var d = inf
			for (y in 0 until h) {
				d = if (!mask[y * w + x]) 0f else d + 1f
				colDist2[y * w + x] = d * d
			}
			d = inf
			for (y in h - 1 downTo 0) {
				d = if (!mask[y * w + x]) 0f else d + 1f
				val d2 = d * d
				if (d2 < colDist2[y * w + x]) colDist2[y * w + x] = d2
			}
		}
		// 1D lower envelope along each row
		val out = FloatArray(w * h)
		val v = IntArray(w)
		val z = FloatArray(w + 1)
		for (y in 0 until h) {
			val rowOff = y * w
			var k = 0
			v[0] = 0
			z[0] = -inf
			z[1] = inf
			for (q in 1 until w) {
				var s: Float
				while (true) {
					val vk = v[k]
					s = ((colDist2[rowOff + q] + q * q) - (colDist2[rowOff + vk] + vk * vk)) / (2f * (q - vk))
					if (s > z[k]) break
					k--
				}
				k++
				v[k] = q
				z[k] = s
				z[k + 1] = inf
			}
			k = 0
			for (q in 0 until w) {
				while (z[k + 1] < q) k++
				val dx = (q - v[k]).toFloat()
				out[rowOff + q] = if (!mask[rowOff + q]) 0f else sqrt(dx * dx + colDist2[rowOff + v[k]])
			}
		}
		return out
	}

	private fun dijkstraGrid(
		n: Int,
		deg: IntArray,
		nbr: IntArray,
		edgeCost: FloatArray,
		source: Int,
	): Pair<FloatArray, IntArray> {
		val dist = FloatArray(n) { Float.POSITIVE_INFINITY }
		val pred = IntArray(n) { -1 }
		dist[source] = 0f
		val heapNode = IntArray(n * 8 + 4)
		val heapDist = FloatArray(n * 8 + 4)
		var size = 0

		fun push(node: Int, d: Float) {
			var i = size++
			while (i > 0) {
				val p = (i - 1) ushr 1
				if (heapDist[p] <= d) break
				heapNode[i] = heapNode[p]
				heapDist[i] = heapDist[p]
				i = p
			}
			heapNode[i] = node
			heapDist[i] = d
		}

		push(source, 0f)
		while (size > 0) {
			val u = heapNode[0]
			val du = heapDist[0]
			size--
			if (size > 0) {
				val lastN = heapNode[size]
				val lastD = heapDist[size]
				var i = 0
				while (true) {
					val l = (i shl 1) + 1
					if (l >= size) break
					val r = l + 1
					val c = if (r < size && heapDist[r] < heapDist[l]) r else l
					if (lastD <= heapDist[c]) break
					heapNode[i] = heapNode[c]
					heapDist[i] = heapDist[c]
					i = c
				}
				heapNode[i] = lastN
				heapDist[i] = lastD
			}
			if (du > dist[u]) continue
			val base = u * 8
			for (e in 0 until deg[u]) {
				val v = nbr[base + e]
				val nd = du + edgeCost[base + e]
				if (nd < dist[v]) {
					dist[v] = nd
					pred[v] = u
					push(v, nd)
				}
			}
		}
		return dist to pred
	}

	private fun percentile(values: List<Float>, q: Float): Float {
		val sorted = values.sorted()
		val pos = (q.coerceIn(0f, 1f) * sorted.lastIndex)
		val i = pos.toInt().coerceIn(0, sorted.lastIndex)
		val j = (i + 1).coerceAtMost(sorted.lastIndex)
		val f = pos - i
		return sorted[i] + (sorted[j] - sorted[i]) * f
	}
}
