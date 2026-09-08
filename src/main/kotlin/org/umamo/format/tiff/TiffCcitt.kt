// CCITT fax decoder, ported from TwelveMonkeys imageio-tiff CCITTFaxDecoderStream (BSD-3-Clause,
// Copyright (c) 2012 Harald Kuhr, Oliver Schmidtmer).  The Huffman code tables, the 1D/2D row
// decoders, and the changing-element bookkeeping follow that class; the stream plumbing is replaced
// by a whole-strip ByteArray decode.  See CREDITS.md.

package org.umamo.format.tiff

/**
 * Signals that the coded data ran out mid-row.  Short strips are legal in the wild (encoders pad the
 * declared row count), so the caller zero-fills the remaining rows rather than failing.
 */
private class CcittEndOfInput : RuntimeException("Unexpected end of CCITT stream")

/** One node of a CCITT Huffman code tree; interior nodes carry children, leaves carry a run value. */
private class CcittNode {
	var left: CcittNode? = null
	var right: CcittNode? = null
	var value: Int = 0
	var isLeaf: Boolean = false

	/**
	 * Attaches [node] as the child selected by [bit].
	 *
	 * @param Boolean bit    The branch to set: false = left (0), true = right (1).
	 * @param CcittNode node The child to attach.
	 */
	fun set(bit: Boolean, node: CcittNode) {
		if (!bit) {
			left = node
		} else {
			right = node
		}
	}

	/**
	 * Follows the branch selected by [bit].
	 *
	 * @param Boolean bit The branch to walk: false = left (0), true = right (1).
	 * @return CcittNode? The child, or null when the code is not in the tree.
	 */
	fun walk(bit: Boolean): CcittNode? = if (bit) right else left
}

/** A CCITT Huffman code tree, built by inserting (bit-depth, code path) -> value entries. */
private class CcittTree {
	val root = CcittNode()

	/**
	 * Inserts a leaf carrying [value] at the code given by the low [depth] bits of [path].
	 *
	 * @param Int depth The code length in bits.
	 * @param Int path  The code bits, MSB first within [depth].
	 * @param Int value The run length (or a VALUE_* sentinel) the code decodes to.
	 */
	fun fill(depth: Int, path: Int, value: Int) {
		val leaf = CcittNode()
		leaf.value = value
		leaf.isLeaf = true
		fill(depth, path, leaf)
	}

	/**
	 * Inserts [node] at the code given by the low [depth] bits of [path], creating interior nodes.
	 *
	 * @param Int depth      The code length in bits.
	 * @param Int path       The code bits, MSB first within [depth].
	 * @param CcittNode node The node to attach at the code's leaf position.
	 */
	fun fill(depth: Int, path: Int, node: CcittNode) {
		var current = root
		for (bitIndex in 0 until depth) {
			val bitPosition = depth - 1 - bitIndex
			val isSet = ((path shr bitPosition) and 1) == 1
			var next = current.walk(isSet)
			if (next == null) {
				next = if (bitIndex == depth - 1) node else CcittNode()
				current.set(isSet, next)
			} else {
				require(!next.isLeaf) { "CCITT code table node is a leaf, no other code may follow" }
			}
			current = next
		}
	}
}

/**
 * The static CCITT code tables: terminating/makeup run codes for white and black, the EOL-only tree,
 * and the T.4/T.6 2D mode tree.  Tables are from ITU-T T.4 as tabulated by TwelveMonkeys.
 */
private object CcittTables {
	// Sentinel leaf values; all negative so they never collide with a real run length.
	const val VALUE_EOL = -2000
	const val VALUE_FILL = -1000
	const val VALUE_PASSMODE = -3000
	const val VALUE_HMODE = -4000

	// T.4 black run codes, grouped by code length starting at 2 bits.
	private val BLACK_CODES =
		arrayOf(
			shortArrayOf(0x2, 0x3), // 2 bits
			shortArrayOf(0x2, 0x3), // 3 bits
			shortArrayOf(0x2, 0x3), // 4 bits
			shortArrayOf(0x3), // 5 bits
			shortArrayOf(0x4, 0x5), // 6 bits
			shortArrayOf(0x4, 0x5, 0x7), // 7 bits
			shortArrayOf(0x4, 0x7), // 8 bits
			shortArrayOf(0x18), // 9 bits
			shortArrayOf(0x17, 0x18, 0x37, 0x8, 0xf), // 10 bits
			shortArrayOf(0x17, 0x18, 0x28, 0x37, 0x67, 0x68, 0x6c, 0x8, 0xc, 0xd), // 11 bits
			shortArrayOf( // 12 bits
				0x12,
				0x13,
				0x14,
				0x15,
				0x16,
				0x17,
				0x1c,
				0x1d,
				0x1e,
				0x1f,
				0x24,
				0x27,
				0x28,
				0x2b,
				0x2c,
				0x33,
				0x34,
				0x35,
				0x37,
				0x38,
				0x52,
				0x53,
				0x54,
				0x55,
				0x56,
				0x57,
				0x58,
				0x59,
				0x5a,
				0x5b,
				0x64,
				0x65,
				0x66,
				0x67,
				0x68,
				0x69,
				0x6a,
				0x6b,
				0x6c,
				0x6d,
				0xc8,
				0xc9,
				0xca,
				0xcb,
				0xcc,
				0xcd,
				0xd2,
				0xd3,
				0xd4,
				0xd5,
				0xd6,
				0xd7,
				0xda,
				0xdb,
			),
			shortArrayOf( // 13 bits
				0x4a,
				0x4b,
				0x4c,
				0x4d,
				0x52,
				0x53,
				0x54,
				0x55,
				0x5a,
				0x5b,
				0x64,
				0x65,
				0x6c,
				0x6d,
				0x72,
				0x73,
				0x74,
				0x75,
				0x76,
				0x77,
			),
		)

	private val BLACK_RUN_LENGTHS =
		arrayOf(
			shortArrayOf(3, 2), // 2 bits
			shortArrayOf(1, 4), // 3 bits
			shortArrayOf(6, 5), // 4 bits
			shortArrayOf(7), // 5 bits
			shortArrayOf(9, 8), // 6 bits
			shortArrayOf(10, 11, 12), // 7 bits
			shortArrayOf(13, 14), // 8 bits
			shortArrayOf(15), // 9 bits
			shortArrayOf(16, 17, 0, 18, 64), // 10 bits
			shortArrayOf(24, 25, 23, 22, 19, 20, 21, 1792, 1856, 1920), // 11 bits
			shortArrayOf( // 12 bits
				1984,
				2048,
				2112,
				2176,
				2240,
				2304,
				2368,
				2432,
				2496,
				2560,
				52,
				55,
				56,
				59,
				60,
				320,
				384,
				448,
				53,
				54,
				50,
				51,
				44,
				45,
				46,
				47,
				57,
				58,
				61,
				256,
				48,
				49,
				62,
				63,
				30,
				31,
				32,
				33,
				40,
				41,
				128,
				192,
				26,
				27,
				28,
				29,
				34,
				35,
				36,
				37,
				38,
				39,
				42,
				43,
			),
			shortArrayOf( // 13 bits
				640,
				704,
				768,
				832,
				1280,
				1344,
				1408,
				1472,
				1536,
				1600,
				1664,
				1728,
				512,
				576,
				896,
				960,
				1024,
				1088,
				1152,
				1216,
			),
		)

	// T.4 white run codes, grouped by code length starting at 4 bits.
	private val WHITE_CODES =
		arrayOf(
			shortArrayOf(0x7, 0x8, 0xb, 0xc, 0xe, 0xf), // 4 bits
			shortArrayOf(0x12, 0x13, 0x14, 0x1b, 0x7, 0x8), // 5 bits
			shortArrayOf(0x17, 0x18, 0x2a, 0x2b, 0x3, 0x34, 0x35, 0x7, 0x8), // 6 bits
			shortArrayOf(0x13, 0x17, 0x18, 0x24, 0x27, 0x28, 0x2b, 0x3, 0x37, 0x4, 0x8, 0xc), // 7 bits
			shortArrayOf( // 8 bits
				0x12,
				0x13,
				0x14,
				0x15,
				0x16,
				0x17,
				0x1a,
				0x1b,
				0x2,
				0x24,
				0x25,
				0x28,
				0x29,
				0x2a,
				0x2b,
				0x2c,
				0x2d,
				0x3,
				0x32,
				0x33,
				0x34,
				0x35,
				0x36,
				0x37,
				0x4,
				0x4a,
				0x4b,
				0x5,
				0x52,
				0x53,
				0x54,
				0x55,
				0x58,
				0x59,
				0x5a,
				0x5b,
				0x64,
				0x65,
				0x67,
				0x68,
				0xa,
				0xb,
			),
			shortArrayOf( // 9 bits
				0x98,
				0x99,
				0x9a,
				0x9b,
				0xcc,
				0xcd,
				0xd2,
				0xd3,
				0xd4,
				0xd5,
				0xd6,
				0xd7,
				0xd8,
				0xd9,
				0xda,
				0xdb,
			),
			shortArrayOf(), // 10 bits
			shortArrayOf(0x8, 0xc, 0xd), // 11 bits
			shortArrayOf(0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x1c, 0x1d, 0x1e, 0x1f), // 12 bits
		)

	private val WHITE_RUN_LENGTHS =
		arrayOf(
			shortArrayOf(2, 3, 4, 5, 6, 7), // 4 bits
			shortArrayOf(128, 8, 9, 64, 10, 11), // 5 bits
			shortArrayOf(192, 1664, 16, 17, 13, 14, 15, 1, 12), // 6 bits
			shortArrayOf(26, 21, 28, 27, 18, 24, 25, 22, 256, 23, 20, 19), // 7 bits
			shortArrayOf( // 8 bits
				33,
				34,
				35,
				36,
				37,
				38,
				31,
				32,
				29,
				53,
				54,
				39,
				40,
				41,
				42,
				43,
				44,
				30,
				61,
				62,
				63,
				0,
				320,
				384,
				45,
				59,
				60,
				46,
				49,
				50,
				51,
				52,
				55,
				56,
				57,
				58,
				448,
				512,
				640,
				576,
				47,
				48,
			),
			shortArrayOf( // 9 bits
				1472,
				1536,
				1600,
				1728,
				704,
				768,
				832,
				896,
				960,
				1024,
				1088,
				1152,
				1216,
				1280,
				1344,
				1408,
			),
			shortArrayOf(), // 10 bits
			shortArrayOf(1792, 1856, 1920), // 11 bits
			shortArrayOf(1984, 2048, 2112, 2176, 2240, 2304, 2368, 2432, 2496, 2560), // 12 bits
		)

	val blackRunTree = CcittTree()
	val whiteRunTree = CcittTree()
	val eolOnlyTree = CcittTree()
	val codeTree = CcittTree()

	init {
		val endOfLine = CcittNode()
		endOfLine.isLeaf = true
		endOfLine.value = VALUE_EOL

		// A run of fill zeros loops back on itself until the terminating 1 bit completes the EOL code.
		val fill = CcittNode()
		fill.value = VALUE_FILL
		fill.left = fill
		fill.right = endOfLine

		eolOnlyTree.fill(12, 0, fill)
		eolOnlyTree.fill(12, 1, endOfLine)

		for (lengthIndex in BLACK_CODES.indices) {
			for (codeIndex in BLACK_CODES[lengthIndex].indices) {
				blackRunTree.fill(lengthIndex + 2, BLACK_CODES[lengthIndex][codeIndex].toInt(), BLACK_RUN_LENGTHS[lengthIndex][codeIndex].toInt())
			}
		}
		blackRunTree.fill(12, 0, fill)
		blackRunTree.fill(12, 1, endOfLine)

		for (lengthIndex in WHITE_CODES.indices) {
			for (codeIndex in WHITE_CODES[lengthIndex].indices) {
				whiteRunTree.fill(lengthIndex + 4, WHITE_CODES[lengthIndex][codeIndex].toInt(), WHITE_RUN_LENGTHS[lengthIndex][codeIndex].toInt())
			}
		}
		whiteRunTree.fill(12, 0, fill)
		whiteRunTree.fill(12, 1, endOfLine)

		// T.4 table 4 / T.6 2D mode codes.
		codeTree.fill(4, 1, VALUE_PASSMODE)
		codeTree.fill(3, 1, VALUE_HMODE)
		codeTree.fill(1, 1, 0) // V(0)
		codeTree.fill(3, 3, 1) // V_R(1)
		codeTree.fill(6, 3, 2) // V_R(2)
		codeTree.fill(7, 3, 3) // V_R(3)
		codeTree.fill(3, 2, -1) // V_L(1)
		codeTree.fill(6, 2, -2) // V_L(2)
		codeTree.fill(7, 2, -3) // V_L(3)
	}
}

/**
 * Decodes one CCITT-coded strip into packed 1-bit-per-pixel rows where a set bit means black.
 *
 * The output bit sense (1 = black) matches TIFF PhotometricInterpretation WhiteIsZero, which is what
 * fax-derived TIFFs declare; the caller's photometric handling inverts for BlackIsZero.
 *
 * @param ByteArray input     The coded bytes for one strip.
 * @param Int columns         Pixels per row.
 * @param Int rows            Rows in this strip.
 * @param Int type            COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE, _CCITT_T4, or _CCITT_T6.
 * @param Long options        T4Options / T6Options, or 0 for Modified Huffman.
 * @param Boolean byteAligned Whether each row restarts on a byte boundary (true for type 2).
 * @return ByteArray The packed rows, ((columns + 7) / 8) bytes per row, zero-filled past any short strip.
 */
internal fun decodeCcitt(input: ByteArray, columns: Int, rows: Int, type: Int, options: Long, byteAligned: Boolean): ByteArray {
	require(columns > 0) { "CCITT columns must be positive, was $columns" }
	require(rows > 0) { "CCITT rows must be positive, was $rows" }
	return CcittFaxDecoder(input, columns, rows, type, options, byteAligned).decode()
}

/**
 * Detects a Group 3 (T.4) stream that carries no EOL codes and is therefore really Modified Huffman
 * RLE.  Some encoders mislabel the compression tag; TwelveMonkeys applies the same probe.
 *
 * @param ByteArray input    The coded bytes.
 * @param Int encodedType    The compression code from the TIFF tag.
 * @return Int The compression code to actually decode with.
 */
internal fun findCcittCompressionType(input: ByteArray, encodedType: Int): Int {
	if (encodedType != TiffConstants.COMPRESSION_CCITT_T4) {
		return encodedType
	}
	if (input.size < 2) {
		return encodedType
	}
	val first = input[0].toInt() and 0xFF
	val second = input[1].toInt() and 0xFF
	// A well-formed T.4 stream opens with an EOL (000000000001), possibly byte-aligned.
	if (first == 0 && ((second shr 4) == 1 || second == 1)) {
		return encodedType
	}

	// Otherwise scan a bounded window for an EOL; absent one, treat the strip as Modified Huffman RLE.
	val limitBytes = minOf(512, input.size)
	var window = (((first shl 8) or second) shr 4)
	var streamByte = second
	for (bitIndex in 12 until limitBytes * 8) {
		if (bitIndex % 8 == 0) {
			streamByte = input[bitIndex / 8].toInt() and 0xFF
		}
		window = (window shl 1) + ((streamByte shr (7 - (bitIndex % 8))) and 0x01)
		if ((window and 0xFFF) == 1) {
			return TiffConstants.COMPRESSION_CCITT_T4
		}
	}
	return TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE
}

/**
 * The CCITT Modified Huffman RLE / Group 3 (T.4) / Group 4 (T.6) row decoder.
 *
 * Rows are decoded as runs of changing elements; 2D modes code the current row as deltas against the
 * previous row's changing elements.  Uncompressed mode (a rarely used T.4/T.6 option) is rejected.
 */
private class CcittFaxDecoder(
	private val input: ByteArray,
	private val columns: Int,
	private val rows: Int,
	private val type: Int,
	options: Long,
	private val byteAligned: Boolean,
) {
	private val optionG32D: Boolean
	private val optionUncompressed: Boolean

	private val decodedRow = ByteArray((columns + 7) / 8)
	private var changesReferenceRow = IntArray(columns + 2)
	private var changesCurrentRow = IntArray(columns + 2)
	private var changesReferenceRowCount = 0
	private var changesCurrentRowCount = 0
	private var lastChangingElement = 0

	private var bitPosition = 0

	init {
		when (type) {
			TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE -> {
				optionG32D = false
				optionUncompressed = false
			}

			TiffConstants.COMPRESSION_CCITT_T4 -> {
				optionG32D = (options and TiffConstants.GROUP3OPT_2DENCODING.toLong()) != 0L
				optionUncompressed = (options and TiffConstants.GROUP3OPT_UNCOMPRESSED.toLong()) != 0L
			}

			TiffConstants.COMPRESSION_CCITT_T6 -> {
				optionG32D = false
				optionUncompressed = (options and TiffConstants.GROUP4OPT_UNCOMPRESSED.toLong()) != 0L
			}

			else -> throw IllegalArgumentException("Unsupported CCITT compression $type (only Modified Huffman RLE / T.4 / T.6)")
		}
		require(!optionUncompressed) { "CCITT Group 3/4 uncompressed mode is not supported" }
	}

	/**
	 * Decodes every row of the strip.
	 *
	 * @return ByteArray The packed 1-bit rows; rows past a truncated stream are left zero (white).
	 */
	fun decode(): ByteArray {
		val rowBytes = decodedRow.size
		val output = ByteArray(rowBytes * rows)
		for (rowIndex in 0 until rows) {
			try {
				decodeRow()
			} catch (_: CcittEndOfInput) {
				// A short strip: the remaining rows stay white, matching TwelveMonkeys' padding behavior.
				break
			}
			decodedRow.copyInto(output, rowIndex * rowBytes, 0, rowBytes)
		}
		return output
	}

	/**
	 * Decodes a single row into [decodedRow], expanding the row's changing elements into packed bits.
	 */
	private fun decodeRow() {
		when (type) {
			TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE -> {
				if (byteAligned) {
					alignToByte()
				}
				decode1D()
			}

			TiffConstants.COMPRESSION_CCITT_T4 -> {
				if (byteAligned) {
					alignToByte()
				}
				skipToEndOfLine()
				if (!optionG32D || readBit()) {
					decode1D()
				} else {
					decode2D()
				}
			}

			TiffConstants.COMPRESSION_CCITT_T6 -> {
				if (byteAligned) {
					alignToByte()
				}
				decode2D()
			}
		}

		var index = 0
		var white = true
		lastChangingElement = 0

		for (changeIndex in 0..changesCurrentRowCount) {
			var nextChange = columns
			if (changeIndex != changesCurrentRowCount) {
				nextChange = changesCurrentRow[changeIndex]
			}
			if (nextChange > columns) {
				nextChange = columns
			}

			var byteIndex = index / 8

			while (index % 8 != 0 && (nextChange - index) > 0) {
				decodedRow[byteIndex] = (decodedRow[byteIndex].toInt() or (if (white) 0 else 1 shl (7 - (index % 8)))).toByte()
				index++
			}

			if (index % 8 == 0) {
				byteIndex = index / 8
				val wholeByte = (if (white) 0x00 else 0xFF).toByte()
				while ((nextChange - index) > 7) {
					decodedRow[byteIndex] = wholeByte
					index += 8
					byteIndex++
				}
			}

			while ((nextChange - index) > 0) {
				if (index % 8 == 0) {
					decodedRow[byteIndex] = 0
				}
				decodedRow[byteIndex] = (decodedRow[byteIndex].toInt() or (if (white) 0 else 1 shl (7 - (index % 8)))).toByte()
				index++
			}

			white = !white
		}

		require(index == columns) { "CCITT run lengths do not sum to the scan line width: $index != $columns" }
	}

	/** Decodes a 1D (Modified Huffman) row into the current changing-element list. */
	private fun decode1D() {
		var index = 0
		var white = true
		changesCurrentRowCount = 0

		do {
			val completeRun = decodeRun(if (white) CcittTables.whiteRunTree else CcittTables.blackRunTree)
			index += completeRun
			changesCurrentRow[changesCurrentRowCount++] = index
			white = !white
		} while (index < columns)
	}

	/** Decodes a 2D (T.4 2D / T.6) row, coding changing elements against the reference row. */
	private fun decode2D() {
		changesReferenceRowCount = changesCurrentRowCount
		val swap = changesCurrentRow
		changesCurrentRow = changesReferenceRow
		changesReferenceRow = swap

		var white = true
		var index = 0
		changesCurrentRowCount = 0

		mode@ while (index < columns) {
			var node = CcittTables.codeTree.root
			while (true) {
				node = node.walk(readBit()) ?: continue@mode
				if (!node.isLeaf) {
					continue
				}
				when (node.value) {
					CcittTables.VALUE_HMODE -> {
						// Horizontal mode: two runs coded with the 1D tables.
						index += decodeRun(if (white) CcittTables.whiteRunTree else CcittTables.blackRunTree)
						changesCurrentRow[changesCurrentRowCount++] = index
						index += decodeRun(if (white) CcittTables.blackRunTree else CcittTables.whiteRunTree)
						changesCurrentRow[changesCurrentRowCount++] = index
					}

					CcittTables.VALUE_PASSMODE -> {
						val passChangingElement = nextChangingElement(index, white) + 1
						index =
							if (passChangingElement >= changesReferenceRowCount) {
								columns
							} else {
								changesReferenceRow[passChangingElement]
							}
					}

					else -> {
						// Vertical mode: the change sits within +/-3 of the reference row's change.
						val verticalChangingElement = nextChangingElement(index, white)
						index =
							if (verticalChangingElement >= changesReferenceRowCount || verticalChangingElement == -1) {
								columns + node.value
							} else {
								changesReferenceRow[verticalChangingElement] + node.value
							}
						changesCurrentRow[changesCurrentRowCount++] = index
						white = !white
					}
				}
				continue@mode
			}
		}
	}

	/**
	 * Finds the reference row's next changing element to the right of [a0] with the opposite color.
	 *
	 * @param Int a0        The current position on the coding line.
	 * @param Boolean white Whether the current run is white.
	 * @return Int The index into the reference row, or -1 when none remains.
	 */
	private fun nextChangingElement(a0: Int, white: Boolean): Int {
		// Changing elements alternate color, so the search starts on the matching parity.
		var start = (lastChangingElement and 0xFFFFFFFE.toInt()) + (if (white) 0 else 1)
		if (start > 2) {
			start -= 2
		}
		if (a0 == 0) {
			return start
		}
		var elementIndex = start
		while (elementIndex < changesReferenceRowCount) {
			if (a0 < changesReferenceRow[elementIndex]) {
				lastChangingElement = elementIndex
				return elementIndex
			}
			elementIndex += 2
		}
		return -1
	}

	/**
	 * Decodes one complete run: any number of makeup codes (>= 64) plus one terminating code.
	 *
	 * @param CcittTree tree The white or black run tree to decode with.
	 * @return Int The total run length in pixels.
	 */
	private fun decodeRun(tree: CcittTree): Int {
		var total = 0
		var node = tree.root
		while (true) {
			node = node.walk(readBit()) ?: throw IllegalArgumentException("Unknown code in CCITT Huffman RLE stream")
			if (!node.isLeaf) {
				continue
			}
			total += node.value
			when {
				// Makeup code: another code follows to complete the run.
				node.value >= 64 -> node = tree.root
				node.value >= 0 -> return total
				// EOL or fill encountered mid-run: end the line here.
				else -> return columns
			}
		}
	}

	/** Consumes coded bits up to and including the next EOL code (T.4 rows are EOL-prefixed). */
	private fun skipToEndOfLine() {
		endOfLine@ while (true) {
			var node = CcittTables.eolOnlyTree.root
			while (true) {
				node = node.walk(readBit()) ?: continue@endOfLine
				if (node.isLeaf) {
					return
				}
			}
		}
	}

	/** Advances the bit cursor to the next byte boundary (EncodedByteAlign / Modified Huffman rows). */
	private fun alignToByte() {
		bitPosition = (bitPosition + 7) and 0xFFFFFFF8.toInt()
	}

	/**
	 * Reads the next coded bit, MSB first.
	 *
	 * @return Boolean The bit value.
	 */
	private fun readBit(): Boolean {
		val byteIndex = bitPosition ushr 3
		if (byteIndex >= input.size) {
			throw CcittEndOfInput()
		}
		val bit = (input[byteIndex].toInt() shr (7 - (bitPosition and 7))) and 1
		bitPosition++
		return bit != 0
	}
}