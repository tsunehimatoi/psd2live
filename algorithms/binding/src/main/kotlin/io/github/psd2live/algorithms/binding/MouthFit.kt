package io.github.psd2live.algorithms.binding

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
 * The open mouth read off the art in the head's frame, as with an eye fit: the first axis runs along the head's
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
	val layered: LayeredMouthFrame? = null,
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
