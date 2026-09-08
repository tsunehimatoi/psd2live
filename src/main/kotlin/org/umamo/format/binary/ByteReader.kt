package org.umamo.format.binary

/**
 * A little-or-big-endian random-access reader over a [ByteArray], shared by the codecs that parse
 * structured binary headers (TIFF's II/MM directories, WebP's little-endian RIFF chunks, PSD's
 * big-endian records).
 *
 * Also the common-code replacement for `java.nio.ByteBuffer`, which is why it carries both a cursor
 * and absolute reads: it defaults to big-endian and its [position] behaves like ByteBuffer's, so a
 * big-endian ByteBuffer parser ports across without restructuring.  Offsets are Int (files under
 * 2 GiB); 32-bit fields are read as Long to stay unsigned-safe, with [u32AsInt] for the common
 * in-range case.  [position] advances on the sequential read* methods; the at-offset methods do not
 * move it.
 */
internal class ByteReader(
	val bytes: ByteArray,
	var position: Int = 0,
	var littleEndian: Boolean = false,
) {
	/**
	 * Reads an unsigned byte at [at] without moving [position].
	 *
	 * @param Int at Absolute offset.
	 * @return Int The value in 0..255.
	 */
	fun u8(at: Int): Int = bytes[at].toInt() and 0xFF

	/**
	 * Reads an unsigned 16-bit value at [at] in the current byte order, without moving [position].
	 *
	 * @param Int at Absolute offset of the first byte.
	 * @return Int The value in 0..65535.
	 */
	fun u16(at: Int): Int =
		if (littleEndian) {
			u8(at) or (u8(at + 1) shl 8)
		} else {
			(u8(at) shl 8) or u8(at + 1)
		}

	/**
	 * Reads an unsigned 32-bit value at [at] in the current byte order (as a Long), without moving
	 * [position].
	 *
	 * @param Int at Absolute offset of the first byte.
	 * @return Long The value in 0..4294967295.
	 */
	fun u32(at: Int): Long =
		if (littleEndian) {
			(u8(at).toLong()) or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)
		} else {
			(u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
		}

	/**
	 * Reads an unsigned 32-bit value at [at] as an Int (for offsets/counts within the 2 GiB range).
	 *
	 * @param Int at Absolute offset of the first byte.
	 * @return Int The value truncated to Int.
	 */
	fun u32AsInt(at: Int): Int = u32(at).toInt()

	/**
	 * Reads an unsigned byte at [position], then advances by one.
	 *
	 * @return Int The value in 0..255.
	 */
	fun readU8(): Int = u8(position).also { position += 1 }

	/**
	 * Reads an unsigned 16-bit value at [position], then advances by two.
	 *
	 * @return Int The value in 0..65535.
	 */
	fun readU16(): Int = u16(position).also { position += 2 }

	/**
	 * Reads an unsigned 32-bit value at [position] (as a Long), then advances by four.
	 *
	 * @return Long The value in 0..4294967295.
	 */
	fun readU32(): Long = u32(position).also { position += 4 }

	/**
	 * Reads an unsigned 32-bit value at [position] as an Int, then advances by four.
	 *
	 * @return Int The value truncated to Int.
	 */
	fun readU32AsInt(): Int = u32AsInt(position).also { position += 4 }

	/**
	 * Reads a signed 16-bit value at [position], then advances by two.
	 *
	 * @return Int The sign-extended value in -32768..32767.
	 */
	fun readI16(): Int = readU16().toShort().toInt()

	/**
	 * Reads [count] bytes at [position], then advances by that many.
	 *
	 * @param Int count Number of bytes to copy.
	 * @return ByteArray The bytes read.
	 */
	fun readBytes(count: Int): ByteArray = bytes.copyOfRange(position, position + count).also { position += count }

	/**
	 * Reads [count] bytes at [at] and decodes them as US-ASCII, without moving [position].
	 *
	 * For the fixed 4-character tags binary formats use as signatures and keys (PSD's `8BIM`/`lyid`,
	 * RIFF's `WEBP`).  Strictly ASCII, via [decodeAscii]: any byte above 0x7F becomes U+FFFD rather
	 * than a Latin-1 character, so a corrupt tag cannot compare equal to something it is not.  NOT for
	 * arbitrary text - a format's human-readable strings (a PSD layer name, say) carry a real encoding
	 * and must be decoded with it; see [readBytes] plus `decodeToString()` for UTF-8.
	 *
	 * @param Int at    Absolute offset of the first byte.
	 * @param Int count Number of bytes to decode.
	 * @return String The decoded text.
	 */
	fun asciiAt(at: Int, count: Int): String = decodeAscii(bytes, at, count)

	/**
	 * Reads [count] bytes at [position] and decodes them as US-ASCII, then advances by that many.
	 *
	 * Carries [asciiAt]'s constraints: fixed tags only, never arbitrary text.
	 *
	 * @param Int count Number of bytes to decode.
	 * @return String The decoded text.
	 */
	fun readAscii(count: Int): String = asciiAt(position, count).also { position += count }
}