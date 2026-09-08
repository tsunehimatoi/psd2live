// Ported from TwelveMonkeys imageio-webp lossless HuffmanTable / HuffmanCodeGroup / HuffmanInfo
// (BSD-3-Clause, Copyright (c) 2022 Harald Kuhr, Simon Kammermeier).  Two-level canonical Huffman
// decode for VP8L; see CREDITS.md.

package org.umamo.format.webp

private const val LEVEL1_BITS = 8

// Symbols of the code-length (L) code, in the order their lengths are read.
private val L_CODE_ORDER = intArrayOf(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

/**
 * One VP8L Huffman tree as a two-level lookup table.  The direct level-1 table (256 entries) resolves
 * codes up to 8 bits; longer codes indirect into a level-2 table.  Each entry packs code length in
 * the high 16 bits and the symbol in the low 16 bits.  Bits are consumed least-significant-first, so
 * a code appears in the stream as the bit-reversed suffix of the lookup index.
 */
internal class WebpHuffmanTable {
	private val level1 = IntArray(1 shl LEVEL1_BITS)
	private val level2 = ArrayList<IntArray>()

	/**
	 * Builds the table by reading the encoded symbol lengths from [reader].
	 *
	 * @param WebpLsbBitReader reader The VP8L bit reader.
	 * @param Int alphabetSize        The number of symbols this tree decodes.
	 */
	constructor(reader: WebpLsbBitReader, alphabetSize: Int) {
		val simpleLengthCode = reader.readBit() == 1
		if (simpleLengthCode) {
			val symbolNum = reader.readBit() + 1
			val first8Bits = reader.readBit() == 1
			val symbol1 = reader.readBits(if (first8Bits) 8 else 1).toInt()
			if (symbolNum == 2) {
				val symbol2 = reader.readBits(8).toInt()
				var index = 0
				while (index < (1 shl LEVEL1_BITS)) {
					level1[index] = (1 shl 16) or symbol1
					level1[index + 1] = (1 shl 16) or symbol2
					index += 2
				}
			} else {
				level1.fill(symbol1)
			}
		} else {
			val numLCodeLengths = (reader.readBits(4) + 4).toInt()
			val lCodeLengths = ShortArray(L_CODE_ORDER.size)
			var numPosCodeLens = 0
			for (lengthIndex in 0 until numLCodeLengths) {
				val length = reader.readBits(3).toShort()
				lCodeLengths[L_CODE_ORDER[lengthIndex]] = length
				if (length > 0) {
					numPosCodeLens++
				}
			}
			val codeLengths = readCodeLengths(reader, lCodeLengths, alphabetSize, numPosCodeLens)
			buildFromLengths(codeLengths, positiveLengthCount(codeLengths))
		}
	}

	/**
	 * Builds the table directly from explicit code lengths (used for the code-length sub-tree).
	 *
	 * @param ShortArray codeLengths The per-symbol bit lengths.
	 * @param Int numPosCodeLens     The count of non-zero lengths.
	 */
	private constructor(codeLengths: ShortArray, numPosCodeLens: Int) {
		buildFromLengths(codeLengths, numPosCodeLens)
	}

	/**
	 * Reads and decodes the next symbol from [reader].
	 *
	 * @param WebpLsbBitReader reader The bit reader.
	 * @return Int The decoded symbol.
	 */
	fun readSymbol(reader: WebpLsbBitReader): Int {
		val index = reader.peekBits(LEVEL1_BITS).toInt()
		var lengthAndSymbol = level1[index]
		var length = lengthAndSymbol ushr 16
		if (length > LEVEL1_BITS) {
			reader.readBits(LEVEL1_BITS)
			val level2Index = reader.peekBits(length - LEVEL1_BITS).toInt()
			lengthAndSymbol = level2[lengthAndSymbol and 0xffff][level2Index]
			length = lengthAndSymbol ushr 16
		}
		reader.readBits(length)
		return lengthAndSymbol and 0xffff
	}

	/**
	 * Populates the two-level lookup from per-symbol code lengths (canonical-code construction).
	 *
	 * @param ShortArray codeLengths The per-symbol bit lengths.
	 * @param Int numPosCodeLens     The count of non-zero lengths.
	 */
	private fun buildFromLengths(codeLengths: ShortArray, numPosCodeLens: Int) {
		val lengthsAndSymbols = IntArray(numPosCodeLens)
		var index = 0
		for (symbol in codeLengths.indices) {
			if (codeLengths[symbol].toInt() != 0) {
				lengthsAndSymbols[index++] = (codeLengths[symbol].toInt() shl 16) or symbol
			}
		}

		// Special case: a single code decodes every index to the same symbol.
		if (numPosCodeLens == 1) {
			level1.fill(lengthsAndSymbols[0] and 0xffff)
			return
		}

		// Sorting the packed (length, symbol) pairs orders first by length, then symbol.
		lengthsAndSymbols.sort()

		val count = IntArray(16)
		for (lengthAndSymbol in lengthsAndSymbols) {
			count[lengthAndSymbol ushr 16]++
		}

		// The next code, in the bit-reversed order it appears in the stream.
		var code = 0
		var step = 2
		index = 0
		var length = 1
		while (length <= LEVEL1_BITS) {
			while (count[length] > 0) {
				val lengthAndSymbol = lengthsAndSymbols[index++]
				var entry = code
				while (entry < level1.size) {
					level1[entry] = lengthAndSymbol
					entry += step
				}
				code = nextCode(code, length)
				count[length]--
			}
			length++
			step = step shl 1
		}

		val rootMask = (1 shl LEVEL1_BITS) - 1
		var rootEntry = -1
		var currentTable: IntArray? = null
		step = 2
		length = LEVEL1_BITS + 1
		while (length <= 15) {
			while (count[length] > 0) {
				val lengthAndSymbol = lengthsAndSymbols[index++]
				if ((code and rootMask) != rootEntry) {
					val level2Bits = nextTableBitSize(count, length, LEVEL1_BITS)
					currentTable = IntArray(1 shl level2Bits)
					rootEntry = code and rootMask
					level2.add(currentTable)
					level1[rootEntry] = ((LEVEL1_BITS + level2Bits) shl 16) or (level2.size - 1)
				}
				val value = ((length - LEVEL1_BITS) shl 16) or (lengthAndSymbol and 0xffff)
				var entry = code ushr LEVEL1_BITS
				val table = currentTable!!
				while (entry < table.size) {
					table[entry] = value
					entry += step
				}
				code = nextCode(code, length)
				count[length]--
			}
			length++
			step = step shl 1
		}
	}

	/**
	 * Reads the actual per-symbol code lengths, themselves Huffman-coded by the code-length tree, with
	 * the 16/17/18 repeat operators.
	 *
	 * @param WebpLsbBitReader reader   The bit reader.
	 * @param ShortArray lCodeLengths   The code-length tree's own lengths.
	 * @param Int alphabetSize          The target alphabet size.
	 * @param Int numPosCodeLens        The count of non-zero L-code lengths.
	 * @return ShortArray The decoded per-symbol lengths.
	 */
	private fun readCodeLengths(reader: WebpLsbBitReader, lCodeLengths: ShortArray, alphabetSize: Int, numPosCodeLens: Int): ShortArray {
		val lengthTree = WebpHuffmanTable(lCodeLengths, numPosCodeLens)

		var codedSymbols =
			if (reader.readBit() == 1) {
				val maxSymbolBitLength = (2 + 2 * reader.readBits(3)).toInt()
				(2 + reader.readBits(maxSymbolBitLength)).toInt()
			} else {
				alphabetSize
			}

		val codeLengths = ShortArray(alphabetSize)
		var previousLength: Short = 8
		var symbol = 0
		while (symbol < alphabetSize && codedSymbols > 0) {
			val length = lengthTree.readSymbol(reader)
			if (length < 16) {
				codeLengths[symbol] = length.toShort()
				if (length != 0) {
					previousLength = length.toShort()
				}
			} else {
				var repeatSymbol: Short = 0
				val extraBits: Int
				val repeatOffset: Int
				when (length) {
					16 -> {
						repeatSymbol = previousLength
						extraBits = 2
						repeatOffset = 3
					}

					17 -> {
						extraBits = 3
						repeatOffset = 3
					}

					18 -> {
						extraBits = 7
						repeatOffset = 11
					}

					else -> throw IllegalArgumentException("WebP Huffman: decoded code length > 18")
				}
				val repeatCount = (reader.readBits(extraBits) + repeatOffset).toInt()
				require(symbol + repeatCount <= alphabetSize) { "WebP Huffman: code-length repeat overflows alphabet" }
				codeLengths.fill(repeatSymbol, symbol, symbol + repeatCount)
				symbol += repeatCount - 1
			}
			symbol++
			codedSymbols--
		}
		return codeLengths
	}
}

/**
 * The count of non-zero entries in [codeLengths].
 *
 * @param ShortArray codeLengths The lengths.
 * @return Int The positive count.
 */
private fun positiveLengthCount(codeLengths: ShortArray): Int {
	var count = 0
	for (length in codeLengths) {
		if (length.toInt() != 0) {
			count++
		}
	}
	return count
}

/**
 * Advances a bit-reversed canonical code by one for the given length: reverse(reverse(code) + 1).
 *
 * @param Int code   The current bit-reversed code.
 * @param Int length The code length in bits.
 * @return Int The next bit-reversed code.
 */
private fun nextCode(code: Int, length: Int): Int {
	val inverted = code.inv() and ((1 shl length) - 1)
	val step = inverted.takeHighestOneBit()
	return (code and (step - 1)) or step
}

/**
 * The number of bits a level-2 sub-table needs, given the remaining code counts.
 *
 * @param IntArray count The per-length code counts.
 * @param Int length     The current code length.
 * @param Int rootBits   The level-1 bit width.
 * @return Int The level-2 bit width.
 */
private fun nextTableBitSize(count: IntArray, length: Int, rootBits: Int): Int {
	var currentLength = length
	var left = 1 shl (currentLength - rootBits)
	while (currentLength < 15) {
		left -= count[currentLength]
		if (left <= 0) {
			break
		}
		currentLength++
		left = left shl 1
	}
	return currentLength - rootBits
}

/** The five Huffman trees of one VP8L Huffman group. */
internal class WebpHuffmanCodeGroup(reader: WebpLsbBitReader, colorCacheBits: Int) {
	val mainCode = WebpHuffmanTable(reader, 256 + 24 + if (colorCacheBits > 0) 1 shl colorCacheBits else 0)
	val redCode = WebpHuffmanTable(reader, 256)
	val blueCode = WebpHuffmanTable(reader, 256)
	val alphaCode = WebpHuffmanTable(reader, 256)
	val distanceCode = WebpHuffmanTable(reader, 40)
}

/**
 * The Huffman-group selection for an image: the optional meta-code raster (per-block group index) and
 * the groups it selects among.
 */
internal class WebpHuffmanInfo(
	val huffmanMetaCodes: WebpRaster?,
	val metaCodeBits: Int,
	val huffmanGroups: Array<WebpHuffmanCodeGroup>,
)