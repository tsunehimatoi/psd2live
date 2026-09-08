// Ported from TwelveMonkeys imageio-tiff HorizontalDeDifferencingStream (BSD-3-Clause,
// Copyright (c) 2013 Harald Kuhr).  TIFF horizontal differencing predictor (2); see CREDITS.md.

package org.umamo.format.tiff

/**
 * Un-applies the TIFF horizontal differencing predictor (Predictor 2) to one decompressed row in
 * place, starting at [rowOffset] in [row]: each sample is the running sum of the stored deltas along
 * its row (TIFF 6.0 §14).
 *
 * For planar data this is called with samplesPerPixel = 1.  Sub-byte samples (1/2/4 bit) accumulate
 * within each byte; 8/16-bit samples accumulate per sample using the file's byte order.
 *
 * @param ByteArray row        The decompressed buffer (modified in place).
 * @param Int rowOffset        Byte offset of this row within [row].
 * @param Int columns          Pixels in the row.
 * @param Int samplesPerPixel  Samples per pixel in this plane (1 for planar).
 * @param Int bitsPerSample    Bits per sample (1/2/4/8/16).
 * @param Boolean littleEndian The TIFF byte order, for 16-bit samples.
 */
internal fun applyHorizontalPredictor(row: ByteArray, rowOffset: Int, columns: Int, samplesPerPixel: Int, bitsPerSample: Int, littleEndian: Boolean) {
	when (bitsPerSample) {
		1 -> {
			var accumulator = 0
			val byteCount = (columns + 7) / 8
			for (byteRelative in 0 until byteCount) {
				val byteIndex = rowOffset + byteRelative
				val original = row[byteIndex].toInt()
				accumulator += (original shr 7) and 0x1
				var temp = (accumulator shl 7) and 0x80
				accumulator += (original shr 6) and 0x1
				temp = temp or ((accumulator shl 6) and 0x40)
				accumulator += (original shr 5) and 0x1
				temp = temp or ((accumulator shl 5) and 0x20)
				accumulator += (original shr 4) and 0x1
				temp = temp or ((accumulator shl 4) and 0x10)
				accumulator += (original shr 3) and 0x1
				temp = temp or ((accumulator shl 3) and 0x08)
				accumulator += (original shr 2) and 0x1
				temp = temp or ((accumulator shl 2) and 0x04)
				accumulator += (original shr 1) and 0x1
				temp = temp or ((accumulator shl 1) and 0x02)
				accumulator += original and 0x1
				row[byteIndex] = (temp or (accumulator and 0x1)).toByte()
			}
		}

		2 -> {
			var accumulator = 0
			val byteCount = (columns + 3) / 4
			for (byteRelative in 0 until byteCount) {
				val byteIndex = rowOffset + byteRelative
				val original = row[byteIndex].toInt()
				accumulator += (original shr 6) and 0x3
				var temp = (accumulator shl 6) and 0xc0
				accumulator += (original shr 4) and 0x3
				temp = temp or ((accumulator shl 4) and 0x30)
				accumulator += (original shr 2) and 0x3
				temp = temp or ((accumulator shl 2) and 0x0c)
				accumulator += original and 0x3
				row[byteIndex] = (temp or (accumulator and 0x3)).toByte()
			}
		}

		4 -> {
			var accumulator = 0
			val byteCount = (columns + 1) / 2
			for (byteRelative in 0 until byteCount) {
				val byteIndex = rowOffset + byteRelative
				val original = row[byteIndex].toInt()
				accumulator += (original shr 4) and 0xf
				var temp = (accumulator shl 4) and 0xf0
				accumulator += original and 0x0f
				row[byteIndex] = (temp or (accumulator and 0xf)).toByte()
			}
		}

		8 -> {
			for (column in 1 until columns) {
				for (sampleIndex in 0 until samplesPerPixel) {
					val offset = rowOffset + column * samplesPerPixel + sampleIndex
					row[offset] = (row[offset - samplesPerPixel] + row[offset]).toByte()
				}
			}
		}

		16 -> {
			for (column in 1 until columns) {
				for (sampleIndex in 0 until samplesPerPixel) {
					val offset = column * samplesPerPixel + sampleIndex
					val previous = read16(row, rowOffset + 2 * (offset - samplesPerPixel), littleEndian)
					val current = read16(row, rowOffset + 2 * offset, littleEndian)
					write16(row, rowOffset + 2 * offset, (previous + current) and 0xFFFF, littleEndian)
				}
			}
		}

		else -> throw IllegalArgumentException("Unsupported TIFF predictor bits per sample $bitsPerSample")
	}
}

/**
 * Reads a 16-bit unsigned sample from [row] at [at] in the given byte order.
 *
 * @param ByteArray row        The row bytes.
 * @param Int at               Byte offset.
 * @param Boolean littleEndian The byte order.
 * @return Int The value in 0..65535.
 */
private fun read16(row: ByteArray, at: Int, littleEndian: Boolean): Int {
	val low = row[at].toInt() and 0xFF
	val high = row[at + 1].toInt() and 0xFF
	return if (littleEndian) {
		low or (high shl 8)
	} else {
		(low shl 8) or high
	}
}

/**
 * Writes a 16-bit sample [value] into [row] at [at] in the given byte order.
 *
 * @param ByteArray row        The row bytes.
 * @param Int at               Byte offset.
 * @param Int value            The value to write.
 * @param Boolean littleEndian The byte order.
 */
private fun write16(row: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
	if (littleEndian) {
		row[at] = value.toByte()
		row[at + 1] = (value ushr 8).toByte()
	} else {
		row[at] = (value ushr 8).toByte()
		row[at + 1] = value.toByte()
	}
}