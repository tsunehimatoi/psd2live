package io.github.psd2live.core

import kotlin.math.ceil
import kotlin.math.max

/** Converts soft/feathered source alpha into a stable binary mask for geometry extraction only. */
internal object AlphaEdgePreprocessor {
	internal data class Result(
		val rgba: ByteArray,
		val hardThreshold: Int,
		val opaquePixels: Int,
	)

	/**
	 * Uses a separable [1,2,1] Gaussian followed by a layer-relative hard threshold. The original
	 * texture is never modified; this mask exists only for contour extraction and mesh occupancy.
	 */
	fun process(
		width: Int,
		height: Int,
		rgba: ByteArray,
		minimumThreshold: Int,
	): Result? {
		if (width <= 0 || height <= 0) return null
		val pixelCount = width * height
		val horizontal = ByteArray(pixelCount)
		val blurred = ByteArray(pixelCount)
		fun sourceAlpha(x: Int, y: Int): Int =
			rgba[(y * width + x) * 4 + 3].toInt() and 0xff

		for (y in 0 until height) {
			for (x in 0 until width) {
				val left = sourceAlpha(max(0, x - 1), y)
				val center = sourceAlpha(x, y)
				val right = sourceAlpha((x + 1).coerceAtMost(width - 1), y)
				horizontal[y * width + x] = ((left + center * 2 + right + 2) / 4).toByte()
			}
		}
		val histogram = IntArray(256)
		var nonzero = 0
		for (y in 0 until height) {
			val top = max(0, y - 1)
			val bottom = (y + 1).coerceAtMost(height - 1)
			for (x in 0 until width) {
				val value = (
					(horizontal[top * width + x].toInt() and 0xff) +
						(horizontal[y * width + x].toInt() and 0xff) * 2 +
						(horizontal[bottom * width + x].toInt() and 0xff) + 2
					) / 4
				blurred[y * width + x] = value.toByte()
				if (value > 0) {
					histogram[value]++
					nonzero++
				}
			}
		}
		if (nonzero == 0) return null

		// The 95th percentile represents the layer's actual painted core even when its maximum
		// opacity is intentionally below 255. Taking 45% of that level discards broad feather tails
		// without erasing semi-transparent artwork.
		val percentileRank = ceil(nonzero * 0.95).toInt().coerceAtLeast(1)
		var cumulative = 0
		var percentile95 = 255
		for (alpha in 1..255) {
			cumulative += histogram[alpha]
			if (cumulative >= percentileRank) {
				percentile95 = alpha
				break
			}
		}
		val hardThreshold = max(minimumThreshold.coerceIn(1, 255), (percentile95 * 0.45 + 0.5).toInt())
		val binaryRgba = ByteArray(pixelCount * 4)
		var opaquePixels = 0
		for (index in 0 until pixelCount) {
			if ((blurred[index].toInt() and 0xff) >= hardThreshold) {
				binaryRgba[index * 4 + 3] = 0xff.toByte()
				opaquePixels++
			}
		}
		if (opaquePixels == 0) return null
		return Result(binaryRgba, hardThreshold, opaquePixels)
	}
}
