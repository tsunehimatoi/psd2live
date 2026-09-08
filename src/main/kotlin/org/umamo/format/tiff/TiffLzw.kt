// Ported from TwelveMonkeys imageio-tiff LZWDecoder (BSD-3-Clause, Copyright (c) 2012 Harald Kuhr).
// TIFF LZW (compression 5), including the old libTiff bit-reversed variant; see CREDITS.md.

package org.umamo.format.tiff

private const val CLEAR_CODE = 256
private const val EOI_CODE = 257
private const val MIN_BITS = 9
private const val MAX_BITS = 12
private const val TABLE_SIZE = 1 shl MAX_BITS

/**
 * A dictionary entry: a byte string built as a reverse linked list, so a new string is the previous
 * string plus one trailing byte without copying.  [firstChar] is cached for fast forward access.
 */
private class LzwString(
	val value: Byte,
	val firstChar: Byte,
	val length: Int,
	val previous: LzwString?,
) {
	/**
	 * Builds the single-byte string for [code].
	 *
	 * @param Byte code The byte value.
	 */
	constructor(code: Byte) : this(code, code, 1, null)

	/**
	 * Returns this string with [next] appended.
	 *
	 * @param Byte next The byte to append.
	 * @return LzwString The concatenated string.
	 */
	fun concatenate(next: Byte): LzwString = LzwString(next, this.firstChar, length + 1, this)

	/**
	 * Writes this string into [out] starting at [offset], back-filling from the tail (the linked list
	 * runs newest-to-oldest).
	 *
	 * @param ByteArray out The destination buffer.
	 * @param Int offset     The write offset.
	 * @return Int The offset just past the written bytes (offset + length, clamped to out.size).
	 */
	fun writeTo(out: ByteArray, offset: Int): Int {
		if (length == 0) {
			return offset
		}
		var entry: LzwString? = this
		var index = length - 1
		while (entry != null && index >= 0) {
			val position = offset + index
			if (position < out.size) {
				out[position] = entry.value
			}
			entry = entry.previous
			index--
		}
		return minOf(offset + length, out.size)
	}
}

/**
 * Decodes a TIFF LZW strip/tile into [expectedSize] bytes, auto-detecting the old libTiff
 * bit-reversed stream variant from the leading bytes.
 *
 * @param ByteArray input The compressed strip/tile bytes.
 * @param Int expectedSize The decompressed byte count.
 * @return ByteArray The decompressed bytes (zero-padded if the stream ends early).
 */
internal fun decodeLzw(input: ByteArray, expectedSize: Int): ByteArray {
	val oldBitReversed = isOldBitReversedStream(input)
	val decoder = LzwDecoder(input, oldBitReversed)
	return decoder.decode(expectedSize)
}

/**
 * True if [input] looks like an old libTiff bit-reversed LZW stream: the first CLEAR_CODE (256) is
 * stored LSB-first, so the leading bytes are 0x00 then an odd byte.
 *
 * @param ByteArray input The compressed bytes.
 * @return Boolean Whether the stream is the bit-reversed variant.
 */
private fun isOldBitReversedStream(input: ByteArray): Boolean {
	if (input.size < 2) {
		return false
	}
	val one = input[0].toInt() and 0xFF
	val two = input[1].toInt() and 0xFF
	return one == 0 && (two and 0x1) == 1
}

/**
 * TIFF LZW decoder mirroring TwelveMonkeys' spec / compatibility bit-order variants and its
 * early-change code-size growth.
 */
private class LzwDecoder(
	private val input: ByteArray,
	private val oldBitReversed: Boolean,
) {
	private val table = arrayOfNulls<LzwString>(if (oldBitReversed) TABLE_SIZE + 1024 else TABLE_SIZE)
	private var tableLength = 0
	private var bitsPerCode = 0
	private var oldCode = CLEAR_CODE
	private var maxCode = 0
	private var bitMask = 0
	private var maxString = 0
	private var eofReached = false
	private var nextData = 0
	private var nextBits = 0
	private var source = 0

	init {
		for (byteValue in 0..255) {
			table[byteValue] = LzwString(byteValue.toByte())
		}
		reset()
	}

	/**
	 * Re-initializes the table to the 258 fixed entries and the minimum code size.
	 */
	private fun reset() {
		tableLength = 258
		bitsPerCode = MIN_BITS
		bitMask = (1 shl bitsPerCode) - 1
		maxCode = maxCode()
		maxString = 1
	}

	/**
	 * The largest code before a code-size increase: spec uses early change (bitMask - 1), the old
	 * bit-reversed variant does not (bitMask).
	 *
	 * @return Int The threshold code.
	 */
	private fun maxCode(): Int = if (oldBitReversed) bitMask else bitMask - 1

	/**
	 * Reads the next byte of [input], or -1 at end.
	 *
	 * @return Int The byte in 0..255, or -1.
	 */
	private fun readByte(): Int = if (source < input.size) input[source++].toInt() and 0xFF else -1

	/**
	 * Reads the next LZW code in the active bit order, or [EOI_CODE] at end of input.
	 *
	 * @return Int The code.
	 */
	private fun getNextCode(): Int {
		if (eofReached) {
			return EOI_CODE
		}
		var read = readByte()
		if (read < 0) {
			eofReached = true
			return EOI_CODE
		}
		if (oldBitReversed) {
			nextData = nextData or (read shl nextBits)
			nextBits += 8
			if (nextBits < bitsPerCode) {
				read = readByte()
				if (read < 0) {
					eofReached = true
					return EOI_CODE
				}
				nextData = nextData or (read shl nextBits)
				nextBits += 8
			}
			val code = nextData and bitMask
			nextData = nextData ushr bitsPerCode
			nextBits -= bitsPerCode
			return code
		} else {
			nextData = (nextData shl 8) or read
			nextBits += 8
			if (nextBits < bitsPerCode) {
				read = readByte()
				if (read < 0) {
					eofReached = true
					return EOI_CODE
				}
				nextData = (nextData shl 8) or read
				nextBits += 8
			}
			val code = (nextData shr (nextBits - bitsPerCode)) and bitMask
			nextBits -= bitsPerCode
			return code
		}
	}

	/**
	 * Adds [string] to the table, growing the code size when the table passes [maxCode].
	 *
	 * @param LzwString string The new dictionary entry.
	 */
	private fun addStringToTable(string: LzwString) {
		if (tableLength >= table.size) {
			throw IllegalArgumentException("TIFF LZW table overflow (more than $MAX_BITS bits per code)")
		}
		table[tableLength++] = string
		if (tableLength > maxCode) {
			bitsPerCode++
			if (bitsPerCode > MAX_BITS) {
				bitsPerCode = MAX_BITS
			}
			bitMask = (1 shl bitsPerCode) - 1
			maxCode = maxCode()
		}
		if (string.length > maxString) {
			maxString = string.length
		}
	}

	/**
	 * Runs the decode loop into a buffer of [expectedSize] bytes.
	 *
	 * @param Int expectedSize The decompressed byte count.
	 * @return ByteArray The decompressed bytes.
	 */
	fun decode(expectedSize: Int): ByteArray {
		val out = ByteArray(expectedSize)
		var position = 0
		var code = getNextCode()
		while (code != EOI_CODE) {
			if (code == CLEAR_CODE) {
				reset()
				code = getNextCode()
				if (code == EOI_CODE) {
					break
				}
				val entry = table[code] ?: throw IllegalArgumentException("Corrupted TIFF LZW code $code")
				position = entry.writeTo(out, position)
			} else {
				table[oldCode] ?: throw IllegalArgumentException("Corrupted TIFF LZW code $oldCode")
				if (code < tableLength) {
					val entry = table[code]!!
					position = entry.writeTo(out, position)
					addStringToTable(table[oldCode]!!.concatenate(entry.firstChar))
				} else {
					val outString = table[oldCode]!!.concatenate(table[oldCode]!!.firstChar)
					position = outString.writeTo(out, position)
					addStringToTable(outString)
				}
			}
			oldCode = code
			if (position >= expectedSize) {
				break
			}
			code = getNextCode()
		}
		return out
	}
}