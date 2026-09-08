package org.umamo.format.png

/*
 * PNG scanline filtering (PNG spec §9 Filtering).  Each decoded scanline is preceded by a filter-type
 * byte; the filter predicts each byte from already-reconstructed neighbours and stores the residual,
 * so decoding adds the prediction back.  All arithmetic is modulo 256 (byte truncation).
 */

// PNG spec §9.2 Filter types.
internal const val FILTER_NONE = 0
internal const val FILTER_SUB = 1
internal const val FILTER_UP = 2
internal const val FILTER_AVERAGE = 3
internal const val FILTER_PAETH = 4

/**
 * Reconstructs one scanline in place, undoing the filter of type [filterType].
 *
 * @param Int filterType     The filter byte (0=None, 1=Sub, 2=Up, 3=Average, 4=Paeth).
 * @param ByteArray current   The filtered bytes of this scanline; overwritten with the reconstruction.
 * @param ByteArray previous  The already-reconstructed previous scanline (all zero for the first row).
 * @param Int bytesPerPixel   The filter stride: bytes per complete pixel, rounded up to at least 1.
 */
internal fun unfilterScanline(filterType: Int, current: ByteArray, previous: ByteArray, bytesPerPixel: Int) {
	when (filterType) {
		FILTER_NONE -> {
			// Recon(x) = Filt(x).
		}

		FILTER_SUB -> {
			for (index in current.indices) {
				val left = if (index >= bytesPerPixel) current[index - bytesPerPixel].toInt() and 0xFF else 0
				current[index] = ((current[index].toInt() and 0xFF) + left).toByte()
			}
		}

		FILTER_UP -> {
			for (index in current.indices) {
				val above = previous[index].toInt() and 0xFF
				current[index] = ((current[index].toInt() and 0xFF) + above).toByte()
			}
		}

		FILTER_AVERAGE -> {
			for (index in current.indices) {
				val left = if (index >= bytesPerPixel) current[index - bytesPerPixel].toInt() and 0xFF else 0
				val above = previous[index].toInt() and 0xFF
				current[index] = ((current[index].toInt() and 0xFF) + ((left + above) ushr 1)).toByte()
			}
		}

		FILTER_PAETH -> {
			for (index in current.indices) {
				val left = if (index >= bytesPerPixel) current[index - bytesPerPixel].toInt() and 0xFF else 0
				val above = previous[index].toInt() and 0xFF
				val aboveLeft = if (index >= bytesPerPixel) previous[index - bytesPerPixel].toInt() and 0xFF else 0
				current[index] = ((current[index].toInt() and 0xFF) + paethPredictor(left, above, aboveLeft)).toByte()
			}
		}

		else -> throw IllegalArgumentException("unknown PNG filter type $filterType")
	}
}

/**
 * The Paeth predictor (PNG spec §9.4): picks the neighbour (left, above, or above-left) closest to
 * the linear estimate left + above - aboveLeft.
 *
 * @param Int left       The reconstructed byte to the left (a in the spec).
 * @param Int above      The reconstructed byte above (b in the spec).
 * @param Int aboveLeft  The reconstructed byte above-left (c in the spec).
 * @return Int The predicted byte value (0..255).
 */
internal fun paethPredictor(left: Int, above: Int, aboveLeft: Int): Int {
	val estimate = left + above - aboveLeft
	val distanceLeft = kotlin.math.abs(estimate - left)
	val distanceAbove = kotlin.math.abs(estimate - above)
	val distanceAboveLeft = kotlin.math.abs(estimate - aboveLeft)
	return when {
		distanceLeft <= distanceAbove && distanceLeft <= distanceAboveLeft -> left
		distanceAbove <= distanceAboveLeft -> above
		else -> aboveLeft
	}
}