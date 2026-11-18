package io.github.psd2live.core.fit

import kotlin.math.abs

/** The opening's width, height, and the two lip centers can change independently in a side view. */
internal object MouthTurnFit {
	data class Shape(val width: Float, val height: Float, val upper: Float, val lower: Float) {
		fun toward(other: Shape, amount: Float) = Shape(
			width + (other.width - width) * amount,
			height + (other.height - height) * amount,
			upper + (other.upper - upper) * amount,
			lower + (other.lower - lower) * amount,
		)
	}

	private data class Surface(val base: Float, val width: Float, val opening: Float, val both: Float,
	                           val widthSquared: Float, val openingSquared: Float) {
		fun at(w: Float, h: Float) = base + width * w + opening * h + both * w * h +
			widthSquared * w * w + openingSquared * h * h
	}

	private class Calibration(val width: Surface, val height: Surface, val upper: Surface, val lower: Surface) {
		fun at(w: Float, h: Float) = Shape(width.at(w, h), height.at(w, h), upper.at(w, h), lower.at(w, h))
	}

	private val neutral = Shape(1f, 1f, 0f, 0f)

	// Calibration surfaces on normalized mouth width in the face and opening height in the mouth.
	// The lips' offsets are fractions of the turned width. Corners receive none of these offsets.
	private val halfTurn = Calibration(
		width = Surface(.93518f, -.00047f, .00519f, -.00376f, .00036f, .00337f),
		height = Surface(1.00602f, -.00586f, .01349f, -.00610f, -.00063f, .00650f),
		upper = Surface(.03654f, .00240f, -.00276f, -.00107f, .00022f, .00152f),
		lower = Surface(-.01865f, .01102f, -.01911f, .00668f, .00185f, -.00817f),
	)
	private val fullTurn = Calibration(
		width = Surface(.85582f, .00075f, .04169f, -.01959f, .00768f, .00589f),
		height = Surface(1.00545f, -.00962f, .01702f, -.00749f, -.00190f, .00967f),
		upper = Surface(.07531f, .00163f, -.00844f, -.00019f, -.00158f, .00422f),
		lower = Surface(-.04412f, .01503f, -.03308f, .01060f, .00088f, -.00927f),
	)

	fun shape(widthInFace: Float, openingRatio: Float, yaw: Float, strength: Float): Shape {
		val w = ((widthInFace - .16f) / .10f).coerceIn(-1f, 1f)
		val h = ((openingRatio - .72f) / .36f).coerceIn(-1f, 1f)
		val atHalf = halfTurn.at(w, h)
		val fraction = (abs(yaw) / FaceTurn.TURN_END).coerceAtMost(1f)
		val fitted = if (fraction <= .5f) neutral.toward(atHalf, fraction * 2f)
		else atHalf.toward(fullTurn.at(w, h), (fraction - .5f) * 2f)
		return neutral.toward(fitted, strength)
	}
}
