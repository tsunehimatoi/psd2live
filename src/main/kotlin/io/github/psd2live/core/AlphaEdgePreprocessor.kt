package io.github.psd2live.core

import kotlin.math.ceil

/** Converts soft/feathered source alpha into the binary mask used to trace a mesh. */
internal object AlphaEdgePreprocessor {
	/** Uses the same two rounded [1,2,1] passes on both axes. The source texture stays untouched. */
	fun geometryMask(width: Int, height: Int, rgba: ByteArray, threshold: Int): ByteArray {
		val pixelCount = width * height
		val horizontal = ByteArray(pixelCount)
		val blurred = ByteArray(pixelCount)
		for (y in 0 until height) {
			val row = y * width
			val last = (row + width - 1) * 4 + 3
			var source = row * 4 + 3
			var center = rgba[source].toInt() and 0xff
			var left = center
			for (x in 0 until width) {
				val right = rgba[minOf(source + 4, last)].toInt() and 0xff
				horizontal[row + x] = ((left + center * 2 + right + 2) / 4).toByte()
				left = center
				center = right
				source += 4
			}
		}
		val histogram = IntArray(256)
		for (y in 0 until height) {
			val row = y * width
			val top = maxOf(0, y - 1) * width
			val bottom = minOf(y + 1, height - 1) * width
			for (x in 0 until width) {
				val index = row + x
				val value = ((horizontal[top + x].toInt() and 0xff) +
					(horizontal[index].toInt() and 0xff) * 2 +
					(horizontal[bottom + x].toInt() and 0xff) + 2) / 4
				blurred[index] = value.toByte()
				histogram[value]++
			}
		}

		// The painted core sets the relative threshold even if its opacity stays below 255.
		val nonzero = pixelCount - histogram[0]
		val rank = ceil(nonzero * 0.95).toInt()
		var cumulative = 0
		var percentile95 = 1
		while (percentile95 < 255) {
			cumulative += histogram[percentile95]
			if (cumulative >= rank) break
			percentile95++
		}
		// If the requested threshold is above the blurred core, the source test below
		// preserves every original pixel at that threshold.
		val hardThreshold = maxOf(threshold, (percentile95 * 0.45 + 0.5).toInt())
		val result = ByteArray(pixelCount * 4)
		for (index in 0 until pixelCount) {
			val offset = index * 4 + 3
			val alpha = rgba[offset].toInt() and 0xff
			// Keep bright hairlines and never bridge pixels that are transparent in the source.
			if (alpha >= threshold && (alpha >= hardThreshold || (blurred[index].toInt() and 0xff) >= hardThreshold))
				result[offset] = -1
		}
		return result
	}
}
