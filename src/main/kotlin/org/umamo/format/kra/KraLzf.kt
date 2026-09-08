package org.umamo.format.kra

/*
 * Krita tile (de)serialisation primitives: the LZF decompressor and the color "delinearisation"
 * that together reverse what Krita writes for each 64x64 tile.
 *
 * Krita compresses tile pixels with a fast LZF variant (a Lempel-Ziv byte coder), and - before
 * compressing - reorders the interleaved pixel bytes into a planar layout (all of channel 0, then
 * all of channel 1, and so on) because runs of one channel compress far better. Reading is the
 * mirror: LZF-decompress, then de-interleave back to packed pixels.
 *
 * Pure Kotlin with no platform APIs, so it lives below the ZIP/XML orchestration and is unit
 * testable on its own. Ported verbatim from Krita's GPL source.
 *
 * Krita タイルの復号プリミティブ。LZF 伸長と平面→インターリーブ変換。プラットフォーム非依存。
 */

// KRA: kis_tile_compressor_2.cpp - first payload byte selects raw vs LZF-compressed tile data.
internal const val KRA_RAW_DATA_FLAG: Int = 0
internal const val KRA_COMPRESSED_DATA_FLAG: Int = 1

/**
 * Decompresses one LZF stream into output, a direct port of Krita's lzff_decompress.
 *
 * The coder emits a stream of control bytes. A control byte whose value is below 32 starts a
 * literal run of value-plus-one raw bytes; otherwise its top 3 bits are a back-reference length and
 * its low 5 bits are the high bits of a back-reference distance (the next byte carries the low 8
 * bits, and a length of 7 means "read one more byte and add it"). Back-references copy
 * already-decoded bytes, so output must be written strictly left to right.
 *
 * @param ByteArray input          Buffer holding the compressed stream.
 * @param Int inputOffset          Index in input where the stream starts (skip the flag byte).
 * @param Int inputLength          Number of compressed bytes to read from inputOffset.
 * @param ByteArray output         Destination for the decoded bytes (sized to the exact tile size).
 * @return Int the number of bytes written to output; 0 on a malformed or overrunning stream.
 */
internal fun lzfDecompress(
	input: ByteArray,
	inputOffset: Int,
	inputLength: Int,
	output: ByteArray,
): Int {
	var inputPos = inputOffset
	// KRA: kis_lzf_compression.cpp:176 - ip_limit = ip + length - 1 (last byte is never a header).
	val inputLimit = inputOffset + inputLength - 1
	var outputPos = 0
	val outputLimit = output.size

	while (inputPos < inputLimit) {
		// KRA: kis_lzf_compression.cpp:182-184 - control byte; all three values read the same byte.
		val control = input[inputPos].toInt() and 0xFF
		inputPos++
		val literalRunLength = control + 1 // The "ctrl" value: byte count when this is a literal run.
		var backReferenceLength = control ushr 5

		if (literalRunLength < 33) {
			// Literal copy of literalRunLength raw bytes. KRA: kis_lzf_compression.cpp:186-208.
			if (outputPos + literalRunLength > outputLimit) {
				return 0
			}
			var remaining = literalRunLength
			while (remaining > 0) {
				output[outputPos] = input[inputPos]
				outputPos++
				inputPos++
				remaining--
			}
		} else {
			// Back reference. KRA: kis_lzf_compression.cpp:209-232.
			backReferenceLength -= 1
			// ref = op - ofs; ref-- where ofs = (control & 31) << 8.
			var referencePos = outputPos - ((control and 31) shl 8) - 1
			if (backReferenceLength == 6) {
				backReferenceLength += input[inputPos].toInt() and 0xFF
				inputPos++
			}
			referencePos -= input[inputPos].toInt() and 0xFF
			inputPos++
			if (outputPos + backReferenceLength + 3 > outputLimit) {
				return 0
			}
			if (referencePos < 0) {
				return 0
			}
			// 3 bytes always, then backReferenceLength more (the length is biased by 3).
			output[outputPos] = output[referencePos]
			outputPos++
			referencePos++
			output[outputPos] = output[referencePos]
			outputPos++
			referencePos++
			output[outputPos] = output[referencePos]
			outputPos++
			referencePos++
			while (backReferenceLength > 0) {
				output[outputPos] = output[referencePos]
				outputPos++
				referencePos++
				backReferenceLength--
			}
		}
	}
	return outputPos
}

/**
 * Reverses Krita's color "linearisation": turns a planar buffer (channel 0 for every pixel, then
 * channel 1, and so on) back into packed interleaved pixels (b,g,r,a,b,g,r,a and onward).
 *
 * Mirror of KisAbstractCompression::delinearizeColors: with stride equal to dataSize / pixelSize
 * (the pixel count), output byte pixel * pixelSize + channel reads input byte channelstride + pixel.
 * Only compressed tiles are linearised; raw tiles are already interleaved.
 *
 * @param ByteArray planarInput    The de-compressed, planar tile bytes.
 * @param ByteArray packedOutput   Destination for interleaved pixels (same length as planarInput).
 * @param Int dataSize             Total byte count (pixelSize * tileWidth * tileHeight).
 * @param Int pixelSize            Bytes per pixel (channel count times bytes per channel).
 */
internal fun delinearizeColors(
	planarInput: ByteArray,
	packedOutput: ByteArray,
	dataSize: Int,
	pixelSize: Int,
) {
	// KRA: kis_abstract_compression.cpp:38-66 - stride is the per-channel run length (pixel count).
	val strideSize = dataSize / pixelSize
	var outputPos = 0
	var pixelStartByte = 0
	while (outputPos < dataSize) {
		var inputPos = pixelStartByte
		for (channelIndex in 0 until pixelSize) {
			packedOutput[outputPos] = planarInput[inputPos]
			outputPos++
			inputPos += strideSize
		}
		pixelStartByte++
	}
}