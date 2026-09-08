// Ported from TwelveMonkeys imageio-webp lossless transforms (BSD-3-Clause, Copyright (c) 2022 Harald
// Kuhr, Simon Kammermeier): PredictorTransform / ColorTransform / SubtractGreenTransform /
// ColorIndexingTransform and their mode constants; see CREDITS.md.

package org.umamo.format.webp

import kotlin.math.abs

// VP8L transform types (order of the 2-bit transform selector).
internal object WebpTransformType {
	const val PREDICTOR = 0
	const val COLOR = 1
	const val SUBTRACT_GREEN = 2
	const val COLOR_INDEXING = 3
}

// VP8L spatial predictor modes.
private object PredictorMode {
	const val BLACK = 0
	const val L = 1
	const val T = 2
	const val TR = 3
	const val TL = 4
	const val AVG_L_TR_T = 5
	const val AVG_L_TL = 6
	const val AVG_L_T = 7
	const val AVG_TL_T = 8
	const val AVG_T_TR = 9
	const val AVG_L_TL_T_TR = 10
	const val SELECT = 11
	const val CLAMP_ADD_SUB_FULL = 12
	const val CLAMP_ADD_SUB_HALF = 13
}

/** One inverse VP8L transform, applied to the decoded image in place. */
internal interface WebpTransform {
	/**
	 * Applies this transform's inverse to [raster] in place.
	 *
	 * @param WebpRaster raster The full-size decoded image.
	 */
	fun applyInverse(raster: WebpRaster)
}

/** Inverse subtract-green: add the green sample back to red and blue. */
internal class SubtractGreenTransform : WebpTransform {
	override fun applyInverse(raster: WebpRaster) {
		val rgba = ByteArray(4)
		for (y in 0 until raster.height) {
			for (x in 0 until raster.width) {
				raster.getPixel(x, y, rgba)
				rgba[0] = ((rgba[0] + rgba[1]) and 0xff).toByte()
				rgba[2] = ((rgba[2] + rgba[1]) and 0xff).toByte()
				raster.setPixel(x, y, rgba)
			}
		}
	}
}

/** Inverse color-indexing: expand each packed palette index back to its RGBA color. */
internal class ColorIndexingTransform(private val colorTable: ByteArray, private val bits: Int) : WebpTransform {
	override fun applyInverse(raster: WebpRaster) {
		val rgba = ByteArray(4)
		val componentSize = 8 shr bits
		val packed = 1 shl bits
		val componentMask = (1 shl componentSize) - 1
		for (y in 0 until raster.height) {
			// Reversed so packed source samples are not overwritten before they are read.
			for (x in raster.width - 1 downTo 0) {
				val sourceColumn = x / packed
				val componentOffset = componentSize * (x % packed)
				val sample = raster.getSample(sourceColumn, y, 1)
				val index = (sample shr componentOffset) and componentMask
				colorTable.copyInto(rgba, 0, index * 4, index * 4 + 4)
				raster.setPixel(x, y, rgba)
			}
		}
	}
}

/** Inverse cross-color transform: add the per-block color deltas back to red and blue. */
internal class ColorTransform(private val data: WebpRaster, private val bits: Int) : WebpTransform {
	override fun applyInverse(raster: WebpRaster) {
		val transformPixel = ByteArray(4)
		val rgba = ByteArray(4)
		for (y in 0 until raster.height) {
			for (x in 0 until raster.width) {
				data.getPixel(x shr bits, y shr bits, transformPixel)
				val greenToRed = transformPixel[2]
				val greenToBlue = transformPixel[1]
				val redToBlue = transformPixel[0]

				raster.getPixel(x, y, rgba)
				var tmpRed = rgba[0].toInt()
				var tmpBlue = rgba[2].toInt()
				tmpRed += colorTransformDelta(greenToRed, rgba[1])
				tmpBlue += colorTransformDelta(greenToBlue, rgba[1])
				tmpBlue += colorTransformDelta(redToBlue, tmpRed.toByte())
				rgba[0] = (tmpRed and 0xff).toByte()
				rgba[2] = (tmpBlue and 0xff).toByte()
				raster.setPixel(x, y, rgba)
			}
		}
	}
}

/**
 * A color-transform delta: (t * c) >> 5 in signed 8-bit arithmetic.
 *
 * @param Byte transform The transform coefficient.
 * @param Byte channel   The channel value.
 * @return Int The signed delta.
 */
private fun colorTransformDelta(transform: Byte, channel: Byte): Int = (transform.toInt() * channel.toInt()) shr 5

/** Inverse spatial predictor transform: add each pixel's predicted value back. */
internal class PredictorTransform(private val data: WebpRaster, private val bits: Int) : WebpTransform {
	override fun applyInverse(raster: WebpRaster) {
		val width = raster.width
		val height = raster.height
		val rgba = ByteArray(4)
		val predictor = ByteArray(4)
		val predictor2 = ByteArray(4)
		val predictor3 = ByteArray(4)

		// (0,0) uses the BLACK predictor: add 0xff to alpha only.
		raster.getPixel(0, 0, rgba)
		rgba[3] = (rgba[3] + 0xff).toByte()
		raster.setPixel(0, 0, rgba)

		// Top row (x,0) predicts from the left.
		for (x in 1 until width) {
			raster.getPixel(x, 0, rgba)
			raster.getPixel(x - 1, 0, predictor)
			addPixels(rgba, predictor)
			raster.setPixel(x, 0, rgba)
		}

		// Left column (0,y) predicts from above.
		for (y in 1 until height) {
			raster.getPixel(0, y, rgba)
			raster.getPixel(0, y - 1, predictor)
			addPixels(rgba, predictor)
			raster.setPixel(0, y, rgba)
		}

		for (y in 1 until height) {
			for (x in 1 until width) {
				val mode = data.getSample(x shr bits, y shr bits, 1)
				raster.getPixel(x, y, rgba)

				val leftX = x - 1
				val topY = y - 1
				val topRightX = if (x == width - 1) 0 else x + 1
				val topRightY = if (x == width - 1) y else topY

				when (mode) {
					PredictorMode.BLACK -> rgba[3] = (rgba[3] + 0xff).toByte()
					PredictorMode.L -> {
						raster.getPixel(leftX, y, predictor)
						addPixels(rgba, predictor)
					}

					PredictorMode.T -> {
						raster.getPixel(x, topY, predictor)
						addPixels(rgba, predictor)
					}

					PredictorMode.TR -> {
						raster.getPixel(topRightX, topRightY, predictor)
						addPixels(rgba, predictor)
					}

					PredictorMode.TL -> {
						raster.getPixel(leftX, topY, predictor)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_L_TR_T -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(topRightX, topRightY, predictor2)
						average2(predictor, predictor2)
						raster.getPixel(x, topY, predictor2)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_L_TL -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(leftX, topY, predictor2)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_L_T -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(x, topY, predictor2)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_TL_T -> {
						raster.getPixel(leftX, topY, predictor)
						raster.getPixel(x, topY, predictor2)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_T_TR -> {
						raster.getPixel(x, topY, predictor)
						raster.getPixel(topRightX, topRightY, predictor2)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.AVG_L_TL_T_TR -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(leftX, topY, predictor2)
						average2(predictor, predictor2)
						raster.getPixel(x, topY, predictor2)
						raster.getPixel(topRightX, topRightY, predictor3)
						average2(predictor2, predictor3)
						average2(predictor, predictor2)
						addPixels(rgba, predictor)
					}

					PredictorMode.SELECT -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(x, topY, predictor2)
						raster.getPixel(leftX, topY, predictor3)
						addPixels(rgba, select(predictor, predictor2, predictor3))
					}

					PredictorMode.CLAMP_ADD_SUB_FULL -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(x, topY, predictor2)
						raster.getPixel(leftX, topY, predictor3)
						clampAddSubtractFull(predictor, predictor2, predictor3)
						addPixels(rgba, predictor)
					}

					PredictorMode.CLAMP_ADD_SUB_HALF -> {
						raster.getPixel(leftX, y, predictor)
						raster.getPixel(x, topY, predictor2)
						average2(predictor, predictor2)
						raster.getPixel(leftX, topY, predictor2)
						clampAddSubtractHalf(predictor, predictor2)
						addPixels(rgba, predictor)
					}
				}
				raster.setPixel(x, y, rgba)
			}
		}
	}
}

/**
 * Adds a predictor pixel into [rgba] component-wise (mod 256).
 *
 * @param ByteArray rgba      The pixel to update.
 * @param ByteArray predictor The predictor pixel.
 */
private fun addPixels(rgba: ByteArray, predictor: ByteArray) {
	rgba[0] = (rgba[0] + predictor[0]).toByte()
	rgba[1] = (rgba[1] + predictor[1]).toByte()
	rgba[2] = (rgba[2] + predictor[2]).toByte()
	rgba[3] = (rgba[3] + predictor[3]).toByte()
}

/**
 * Averages two pixels component-wise into [first].
 *
 * @param ByteArray first  The first pixel (overwritten with the average).
 * @param ByteArray second The second pixel.
 */
private fun average2(first: ByteArray, second: ByteArray) {
	first[0] = (((first[0].toInt() and 0xff) + (second[0].toInt() and 0xff)) / 2).toByte()
	first[1] = (((first[1].toInt() and 0xff) + (second[1].toInt() and 0xff)) / 2).toByte()
	first[2] = (((first[2].toInt() and 0xff) + (second[2].toInt() and 0xff)) / 2).toByte()
	first[3] = (((first[3].toInt() and 0xff) + (second[3].toInt() and 0xff)) / 2).toByte()
}

/**
 * The Select predictor: returns whichever of the left/top pixels is closer (Manhattan) to the
 * gradient estimate left + top - topLeft.
 *
 * @param ByteArray left    The left pixel.
 * @param ByteArray top     The top pixel.
 * @param ByteArray topLeft The top-left pixel.
 * @return ByteArray Either [left] or [top].
 */
private fun select(left: ByteArray, top: ByteArray, topLeft: ByteArray): ByteArray {
	val predictedAlpha = addSubtractFull(left[3], top[3], topLeft[3])
	val predictedRed = addSubtractFull(left[0], top[0], topLeft[0])
	val predictedGreen = addSubtractFull(left[1], top[1], topLeft[1])
	val predictedBlue = addSubtractFull(left[2], top[2], topLeft[2])
	val distanceLeft = manhattanDistance(left, predictedAlpha, predictedRed, predictedGreen, predictedBlue)
	val distanceTop = manhattanDistance(top, predictedAlpha, predictedRed, predictedGreen, predictedBlue)
	return if (distanceLeft < distanceTop) left else top
}

/**
 * The Manhattan distance from a pixel to the predicted A/R/G/B estimate.
 *
 * @param ByteArray rgba The pixel to measure.
 * @param Int predictedAlpha The estimate's alpha.
 * @param Int predictedRed   The estimate's red.
 * @param Int predictedGreen The estimate's green.
 * @param Int predictedBlue  The estimate's blue.
 * @return Int The summed absolute per-component difference.
 */
private fun manhattanDistance(rgba: ByteArray, predictedAlpha: Int, predictedRed: Int, predictedGreen: Int, predictedBlue: Int): Int =
	abs(predictedAlpha - (rgba[3].toInt() and 0xff)) + abs(predictedRed - (rgba[0].toInt() and 0xff)) +
		abs(predictedGreen - (rgba[1].toInt() and 0xff)) + abs(predictedBlue - (rgba[2].toInt() and 0xff))

/**
 * Clamps a value to an 8-bit sample.
 *
 * @param Int value The value to clamp.
 * @return Int The value in 0..255.
 */
private fun clamp(value: Int): Int = maxOf(0, minOf(value, 255))

/**
 * The gradient estimate for one component: left + top - topLeft, unsigned.
 *
 * @param Byte left    The left neighbour's component.
 * @param Byte top     The top neighbour's component.
 * @param Byte topLeft The top-left neighbour's component.
 * @return Int The estimate, which may fall outside 0..255.
 */
private fun addSubtractFull(left: Byte, top: Byte, topLeft: Byte): Int = (left.toInt() and 0xff) + (top.toInt() and 0xff) - (topLeft.toInt() and 0xff)

/**
 * The half-gradient estimate for one component: average + (average - topLeft) / 2, unsigned.
 *
 * @param Byte average The left/top average's component.
 * @param Byte topLeft The top-left neighbour's component.
 * @return Int The estimate, which may fall outside 0..255.
 */
private fun addSubtractHalf(average: Byte, topLeft: Byte): Int =
	(average.toInt() and 0xff) + ((average.toInt() and 0xff) - (topLeft.toInt() and 0xff)) / 2

/**
 * The clamped full add-subtract predictor, written back into [left].
 *
 * @param ByteArray left    The left neighbour; overwritten with the prediction.
 * @param ByteArray top     The top neighbour.
 * @param ByteArray topLeft The top-left neighbour.
 */
private fun clampAddSubtractFull(left: ByteArray, top: ByteArray, topLeft: ByteArray) {
	left[0] = clamp(addSubtractFull(left[0], top[0], topLeft[0])).toByte()
	left[1] = clamp(addSubtractFull(left[1], top[1], topLeft[1])).toByte()
	left[2] = clamp(addSubtractFull(left[2], top[2], topLeft[2])).toByte()
	left[3] = clamp(addSubtractFull(left[3], top[3], topLeft[3])).toByte()
}

/**
 * The clamped half add-subtract predictor, written back into [average].
 *
 * @param ByteArray average The left/top average; overwritten with the prediction.
 * @param ByteArray topLeft The top-left neighbour.
 */
private fun clampAddSubtractHalf(average: ByteArray, topLeft: ByteArray) {
	average[0] = clamp(addSubtractHalf(average[0], topLeft[0])).toByte()
	average[1] = clamp(addSubtractHalf(average[1], topLeft[1])).toByte()
	average[2] = clamp(addSubtractHalf(average[2], topLeft[2])).toByte()
	average[3] = clamp(addSubtractHalf(average[3], topLeft[3])).toByte()
}