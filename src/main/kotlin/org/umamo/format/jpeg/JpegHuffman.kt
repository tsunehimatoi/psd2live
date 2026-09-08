// Baseline JPEG entropy decoding: the MSB-first bit reader with 0xFF00 byte de-stuffing (T.81 F.1.2.1)
// and the canonical Huffman DECODE procedure (T.81 Annex F, Figures F.12 / F.16).

package org.umamo.format.jpeg

/**
 * A canonical JPEG Huffman table, built from a DHT segment's per-length code counts and value list.
 *
 * Decoding follows T.81 Figure F.16: walk one bit at a time, growing the code until it falls within
 * the [minCode]/[maxCode] range recorded for the current code length.
 */
internal class JpegHuffmanTable(codeCountsPerLength: IntArray, private val values: IntArray) {
	// Indexed by code length 1..16; index 0 is unused so the spec's 1-based lengths read directly.
	private val minCode = IntArray(17)
	private val maxCode = IntArray(17)
	private val valuePointer = IntArray(17)

	init {
		// T.81 Figure F.15: assign canonical codes in increasing length, then increasing value order.
		var code = 0
		var valueIndex = 0
		for (codeLength in 1..16) {
			val countAtLength = codeCountsPerLength[codeLength - 1]
			if (countAtLength == 0) {
				// No codes of this length; the sentinel makes the DECODE comparison always fall through.
				maxCode[codeLength] = -1
			} else {
				valuePointer[codeLength] = valueIndex
				minCode[codeLength] = code
				valueIndex += countAtLength
				code += countAtLength
				maxCode[codeLength] = code - 1
			}
			code = code shl 1
		}
	}

	/**
	 * Decodes one Huffman-coded symbol.
	 *
	 * @param JpegBitReader reader The entropy-coded bit source.
	 * @return Int The decoded symbol value.
	 */
	fun decode(reader: JpegBitReader): Int {
		var code = reader.readBit()
		var codeLength = 1
		while (code > maxCode[codeLength]) {
			code = (code shl 1) or reader.readBit()
			codeLength++
			require(codeLength <= 16) { "Invalid JPEG Huffman code (longer than 16 bits)" }
		}
		val index = valuePointer[codeLength] + code - minCode[codeLength]
		require(index in values.indices) { "Invalid JPEG Huffman code (value index $index out of range)" }
		return values[index]
	}
}

/**
 * An MSB-first bit reader over JPEG entropy-coded data.
 *
 * Handles the 0xFF00 stuffed-zero convention (T.81 F.1.2.3).  On reaching a real marker or the end of
 * the buffer the reader pads with zero bits rather than failing, matching libjpeg's behavior for
 * truncated scans; [markerReached] reports that the entropy segment has ended.
 */
internal class JpegBitReader(private val data: ByteArray, position: Int) {
	/** The read cursor, left pointing at the 0xFF of a marker once one is reached. */
	var position: Int = position
		private set

	/** True once a marker or the end of the buffer has been reached and bits are being zero-padded. */
	var markerReached: Boolean = false
		private set

	private var bitBuffer = 0
	private var bitCount = 0

	/**
	 * Reads the next entropy-coded bit, MSB first.
	 *
	 * @return Int The bit value (0 or 1); zero once the entropy segment has ended.
	 */
	fun readBit(): Int {
		if (bitCount == 0) {
			if (!fillBitBuffer()) {
				return 0
			}
		}
		bitCount--
		return (bitBuffer shr bitCount) and 1
	}

	/**
	 * Reads [count] bits as an unsigned value, MSB first (the T.81 RECEIVE procedure, Figure F.17).
	 *
	 * @param Int count The number of bits to read (0..16).
	 * @return Int The bits as an unsigned integer.
	 */
	fun readBits(count: Int): Int {
		var value = 0
		for (bitIndex in 0 until count) {
			value = (value shl 1) or readBit()
		}
		return value
	}

	/**
	 * Loads the next entropy-coded byte into the bit buffer, de-stuffing 0xFF00.
	 *
	 * @return Boolean False when a marker or the buffer end stopped the fill (bits are zero-padded).
	 */
	private fun fillBitBuffer(): Boolean {
		if (position >= data.size) {
			markerReached = true
			return false
		}
		var nextByte = data[position].toInt() and 0xFF
		if (nextByte == 0xFF) {
			// Skip any fill bytes preceding a marker (T.81 B.1.1.2 permits 0xFF padding).
			var lookahead = position + 1
			while (lookahead < data.size && (data[lookahead].toInt() and 0xFF) == 0xFF) {
				lookahead++
			}
			if (lookahead >= data.size) {
				position = data.size
				markerReached = true
				return false
			}
			val following = data[lookahead].toInt() and 0xFF
			if (following != 0x00) {
				// A real marker: leave the cursor on its 0xFF so the caller can identify it.
				position = lookahead - 1
				markerReached = true
				return false
			}
			// A stuffed zero: the 0xFF is literal data and the 0x00 is dropped.
			position = lookahead + 1
			nextByte = 0xFF
		} else {
			position++
		}
		bitBuffer = nextByte
		bitCount = 8
		return true
	}

	/**
	 * Discards buffered bits and consumes the next restart marker, per T.81 F.2.1.3.1.
	 *
	 * @return Boolean Whether a restart marker was found and consumed.
	 */
	fun restart(): Boolean {
		bitCount = 0
		markerReached = false
		// The marker should sit at the cursor once bits are dropped, but tolerate stray fill bytes.
		var scan = position
		while (scan + 1 < data.size) {
			if ((data[scan].toInt() and 0xFF) == 0xFF) {
				val marker = data[scan + 1].toInt() and 0xFF
				if (marker in JpegConstants.MARKER_RST0..JpegConstants.MARKER_RST7) {
					position = scan + 2
					return true
				}
				if (marker != 0xFF && marker != 0x00) {
					// Some other marker: the scan is over, leave the cursor for the caller.
					position = scan
					markerReached = true
					return false
				}
			}
			scan++
		}
		position = data.size
		markerReached = true
		return false
	}
}

/**
 * Sign-extends a [length]-bit Huffman-coded difference value (the T.81 EXTEND procedure, Figure F.12).
 *
 * @param Int value  The raw magnitude bits from RECEIVE.
 * @param Int length The number of bits read.
 * @return Int The signed coefficient difference.
 */
internal fun extendJpegValue(value: Int, length: Int): Int {
	if (length == 0) {
		return 0
	}
	// Values whose top bit is clear encode the negative half of the range.
	return if (value < (1 shl (length - 1))) value - (1 shl length) + 1 else value
}