// Reimplements TwelveMonkeys imageio-webp LSBBitReader (BSD-3-Clause, Copyright (c) 2020 Harald Kuhr,
// Simon Kammermeier) over a ByteArray instead of an ImageInputStream; see CREDITS.md.

package org.umamo.format.webp

/**
 * A least-significant-bit-first bit reader over a [ByteArray], as VP8L requires: within a byte the
 * low bit is consumed first, and successive bytes fill higher result bits.  Reading past the end
 * yields zero bits (a truncated stream degrades rather than throwing).
 */
internal class WebpLsbBitReader(private val bytes: ByteArray, startByte: Int) {
	private var bitPosition: Long = startByte.toLong() * 8

	/**
	 * Reads [count] bits LSB-first and advances.
	 *
	 * @param Int count Number of bits (0..64).
	 * @return Long The bits, first-read bit in the least-significant position.
	 */
	fun readBits(count: Int): Long {
		var result = 0L
		var shift = 0
		var remaining = count
		while (remaining > 0) {
			val bytePosition = (bitPosition ushr 3).toInt()
			val bitInByte = (bitPosition and 7L).toInt()
			val take = minOf(8 - bitInByte, remaining)
			val byteValue = if (bytePosition < bytes.size) (bytes[bytePosition].toInt() and 0xFF) ushr bitInByte else 0
			val mask = (1 shl take) - 1
			result = result or ((byteValue and mask).toLong() shl shift)
			shift += take
			remaining -= take
			bitPosition += take
		}
		return result
	}

	/**
	 * Reads [count] bits without advancing (VP8L peeks up to the level-1 Huffman index width).
	 *
	 * @param Int count Number of bits.
	 * @return Long The peeked bits.
	 */
	fun peekBits(count: Int): Long {
		val saved = bitPosition
		val value = readBits(count)
		bitPosition = saved
		return value
	}

	/**
	 * Reads a single bit and advances.
	 *
	 * @return Int The bit (0 or 1).
	 */
	fun readBit(): Int = readBits(1).toInt()
}