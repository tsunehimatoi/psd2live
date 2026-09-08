// WebP RIFF container + VP8L header parsing, from TwelveMonkeys imageio-webp WebPImageReader.readHeader
// (BSD-3-Clause, Copyright (c) 2020 Harald Kuhr) reimplemented over a ByteArray; see CREDITS.md.

package org.umamo.format.webp

import org.umamo.format.binary.ByteReader

/**
 * The parsed VP8L header: image dimensions and the byte offset where the LSB-first VP8L bit stream
 * begins (just past the 1-byte signature and 4-byte packed dimensions).
 */
internal class Vp8lHeader(val width: Int, val height: Int, val bitStreamStart: Int)

// VP8L bitstream signature byte, per the WebP container spec.
private const val VP8L_SIGNATURE = 0x2f

/**
 * Locates the VP8L chunk (directly, or inside a VP8X extended container) and parses its header.
 * Lossy VP8, animation, and other unsupported layouts throw a clear error.
 *
 * @param ByteArray bytes The complete `.webp` file.
 * @return Vp8lHeader The dimensions and bit-stream start offset.
 */
internal fun parseVp8lHeader(bytes: ByteArray): Vp8lHeader {
	require(bytes.size >= 12) { "WebP too short for a RIFF header" }
	require(fourCC(bytes, 0) == "RIFF" && fourCC(bytes, 8) == "WEBP") { "Not a WebP (bad RIFF/WEBP magic)" }
	val reader = ByteReader(bytes, littleEndian = true)

	var chunkOffset = 12
	val firstFourCC = fourCC(bytes, chunkOffset)
	if (firstFourCC == "VP8X") {
		// Extended container: skip the 10-byte VP8X chunk, then scan sub-chunks for VP8L.
		val vp8xSize = reader.u32AsInt(chunkOffset + 4)
		chunkOffset += 8 + vp8xSize + (vp8xSize and 1)
		while (chunkOffset + 8 <= bytes.size) {
			val subFourCC = fourCC(bytes, chunkOffset)
			val subSize = reader.u32AsInt(chunkOffset + 4)
			when (subFourCC) {
				"VP8L" -> return readVp8lHeader(reader, chunkOffset + 8)
				"VP8 " -> throw IllegalArgumentException("Lossy VP8 WebP is not supported (VP8L only)")
			}
			chunkOffset += 8 + subSize + (subSize and 1)
		}
		throw IllegalArgumentException("WebP VP8X container has no supported VP8L image")
	}

	return when (firstFourCC) {
		"VP8L" -> readVp8lHeader(reader, chunkOffset + 8)
		"VP8 " -> throw IllegalArgumentException("Lossy VP8 WebP is not supported (VP8L only)")
		else -> throw IllegalArgumentException("Unsupported WebP chunk '$firstFourCC'")
	}
}

/**
 * Reads the VP8L signature and packed 14+14+1+3-bit dimensions at [dataStart].
 *
 * @param ByteReader reader The little-endian file reader.
 * @param Int dataStart     Offset of the VP8L chunk data (the signature byte).
 * @return Vp8lHeader The dimensions and bit-stream start.
 */
private fun readVp8lHeader(reader: ByteReader, dataStart: Int): Vp8lHeader {
	require(dataStart + 5 <= reader.bytes.size) { "Truncated VP8L header" }
	require(reader.u8(dataStart) == VP8L_SIGNATURE) { "Bad VP8L signature" }
	// WebP VP8L: 1-byte signature, then 14 bits (width-1) | 14 bits (height-1) | 1 bit alpha | 3 bits version.
	val packed = reader.u32(dataStart + 1)
	val width = (packed and 0x3FFF).toInt() + 1
	val height = ((packed shr 14) and 0x3FFF).toInt() + 1
	return Vp8lHeader(width, height, dataStart + 5)
}

/**
 * Reads a 4-character chunk tag at [at].
 *
 * @param ByteArray bytes The buffer.
 * @param Int at          Offset.
 * @return String The 4 ASCII characters.
 */
private fun fourCC(bytes: ByteArray, at: Int): String =
	buildString {
		for (index in 0 until 4) {
			append((bytes[at + index].toInt() and 0xFF).toChar())
		}
	}