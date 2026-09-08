// JPEG decoder, written from ITU-T T.81 (the JPEG specification).  Section references in this package
// cite T.81; the fixed-point IDCT, upsampling filters, color-conversion constants, and progressive
// scan decoders follow the Independent JPEG Group's reference implementation (jidctint.c / jdsample.c
// / jdcolor.c / jdphuff.c) so output matches libjpeg exactly.

package org.umamo.format.jpeg

/**
 * JPEG marker codes (T.81 Table B.1) and the zig-zag coefficient order (T.81 Figure A.6).
 */
internal object JpegConstants {
	// Start Of Frame markers.  SOF0/SOF1/SOF2 (sequential and progressive Huffman) are decodable; the
	// rest are arithmetic-coded, differential, or lossless and are rejected.
	const val MARKER_SOF0 = 0xC0 // Baseline DCT.
	const val MARKER_SOF1 = 0xC1 // Extended sequential DCT, Huffman.
	const val MARKER_SOF2 = 0xC2 // Progressive DCT, Huffman.
	const val MARKER_SOF3 = 0xC3 // Lossless (sequential).
	const val MARKER_SOF5 = 0xC5 // Differential sequential DCT.
	const val MARKER_SOF6 = 0xC6 // Differential progressive DCT.
	const val MARKER_SOF7 = 0xC7 // Differential lossless.
	const val MARKER_SOF9 = 0xC9 // Extended sequential DCT, arithmetic.
	const val MARKER_SOF10 = 0xCA // Progressive DCT, arithmetic.
	const val MARKER_SOF11 = 0xCB // Lossless, arithmetic.
	const val MARKER_SOF13 = 0xCD // Differential sequential DCT, arithmetic.
	const val MARKER_SOF14 = 0xCE // Differential progressive DCT, arithmetic.
	const val MARKER_SOF15 = 0xCF // Differential lossless, arithmetic.

	const val MARKER_DHT = 0xC4 // Define Huffman Table.
	const val MARKER_DAC = 0xCC // Define Arithmetic Coding conditioning.
	const val MARKER_SOI = 0xD8 // Start Of Image.
	const val MARKER_EOI = 0xD9 // End Of Image.
	const val MARKER_SOS = 0xDA // Start Of Scan.
	const val MARKER_DQT = 0xDB // Define Quantization Table.
	const val MARKER_DNL = 0xDC // Define Number of Lines.
	const val MARKER_DRI = 0xDD // Define Restart Interval.
	const val MARKER_APP0 = 0xE0 // JFIF lives here.
	const val MARKER_APP14 = 0xEE // Adobe color-transform marker lives here.
	const val MARKER_APP15 = 0xEF
	const val MARKER_COM = 0xFE // Comment.

	const val MARKER_RST0 = 0xD0 // Restart markers RST0..RST7.
	const val MARKER_RST7 = 0xD7

	/** Samples per DCT block edge; the JPEG block is always 8x8 (T.81 A.3.3). */
	const val BLOCK_SIZE = 8

	/** Coefficients per DCT block. */
	const val BLOCK_COEFFICIENTS = 64

	/**
	 * Maps a zig-zag coefficient index to its natural (row-major) index within an 8x8 block.
	 *
	 * DQT tables and entropy-coded coefficients both arrive in zig-zag order; storing them through
	 * this table puts both in natural order, which is what the IDCT indexes.
	 */
	val NATURAL_ORDER =
		intArrayOf(
			0,
			1,
			8,
			16,
			9,
			2,
			3,
			10,
			17,
			24,
			32,
			25,
			18,
			11,
			4,
			5,
			12,
			19,
			26,
			33,
			40,
			48,
			41,
			34,
			27,
			20,
			13,
			6,
			7,
			14,
			21,
			28,
			35,
			42,
			49,
			56,
			57,
			50,
			43,
			36,
			29,
			22,
			15,
			23,
			30,
			37,
			44,
			51,
			58,
			59,
			52,
			45,
			38,
			31,
			39,
			46,
			53,
			60,
			61,
			54,
			47,
			55,
			62,
			63,
		)

	/**
	 * True when [marker] is any Start Of Frame code (the SOFn range excluding DHT/DAC/RSTn).
	 *
	 * @param Int marker The marker code (the byte following 0xFF).
	 * @return Boolean Whether the marker starts a frame.
	 */
	fun isStartOfFrame(marker: Int): Boolean =
		marker == MARKER_SOF0 ||
			marker == MARKER_SOF1 ||
			marker == MARKER_SOF2 ||
			marker == MARKER_SOF3 ||
			marker == MARKER_SOF5 ||
			marker == MARKER_SOF6 ||
			marker == MARKER_SOF7 ||
			marker == MARKER_SOF9 ||
			marker == MARKER_SOF10 ||
			marker == MARKER_SOF11 ||
			marker == MARKER_SOF13 ||
			marker == MARKER_SOF14 ||
			marker == MARKER_SOF15

	/**
	 * Names an undecodable Start Of Frame, for the rejection message.
	 *
	 * Only reachable for frame types outside SOF0/SOF1/SOF2, so every case here is arithmetic-coded,
	 * differential, or lossless.
	 *
	 * @param Int marker The SOFn marker code.
	 * @return String The frame type's name.
	 */
	fun startOfFrameDescription(marker: Int): String =
		when (marker) {
			MARKER_SOF3 -> "lossless JPEG (SOF3)"
			MARKER_SOF5 -> "differential sequential JPEG (SOF5)"
			MARKER_SOF6 -> "differential progressive JPEG (SOF6)"
			MARKER_SOF7 -> "differential lossless JPEG (SOF7)"
			MARKER_SOF9 -> "arithmetic-coded sequential JPEG (SOF9)"
			MARKER_SOF10 -> "arithmetic-coded progressive JPEG (SOF10)"
			MARKER_SOF11 -> "arithmetic-coded lossless JPEG (SOF11)"
			MARKER_SOF13 -> "arithmetic-coded differential sequential JPEG (SOF13)"
			MARKER_SOF14 -> "arithmetic-coded differential progressive JPEG (SOF14)"
			MARKER_SOF15 -> "arithmetic-coded differential lossless JPEG (SOF15)"
			else -> "JPEG frame type 0x${marker.toString(16).uppercase()}"
		}
}