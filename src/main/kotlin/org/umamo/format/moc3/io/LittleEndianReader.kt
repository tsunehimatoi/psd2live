package org.umamo.format.moc3.io

/**
 * Little-endian, non-obfuscated reader over a byte array.
 *
 * EN: MOC3 is a flat little-endian blob - no obfuscation, no compression (unlike CMO3's
 *     big-endian, XOR'd CAFF container). All multi-byte integers and floats are little-endian.
 * JA: MOC3 はリトルエンディアンの素のバイナリ（難読化・圧縮なし）。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public class LittleEndianReader(private val data: ByteArray) {
	/** Current read cursor, in bytes from the start of [data]. */
	public var position: Int = 0
		private set

	/** Total length of the backing buffer. */
	public val size: Int get() = data.size

	/**
	 * Moves the cursor to an absolute byte offset.
	 *
	 * @param Int offset Absolute position to seek to.
	 */
	public fun seek(offset: Int) {
		position = offset
	}

	/**
	 * Advances the cursor by [count] bytes without interpreting them.
	 *
	 * @param Int count Number of bytes to skip.
	 */
	public fun skip(count: Int) {
		position += count
	}

	/**
	 * Reads one unsigned byte (0..255).
	 *
	 * @return Int The byte value in 0..255.
	 */
	public fun readU8(): Int {
		val value = data[position].toInt() and 0xFF
		position += 1
		return value
	}

	/**
	 * Reads a little-endian unsigned 16-bit integer (0..65535).
	 *
	 * @return Int The decoded value.
	 */
	public fun readU16(): Int {
		val low = data[position].toInt() and 0xFF
		val high = data[position + 1].toInt() and 0xFF
		position += 2
		return (high shl 8) or low
	}

	/**
	 * Reads a little-endian signed 32-bit integer.
	 *
	 * @return Int The decoded value.
	 */
	public fun readInt32(): Int {
		val byte0 = data[position].toInt() and 0xFF
		val byte1 = data[position + 1].toInt() and 0xFF
		val byte2 = data[position + 2].toInt() and 0xFF
		val byte3 = data[position + 3].toInt() and 0xFF
		position += 4
		return byte0 or (byte1 shl 8) or (byte2 shl 16) or (byte3 shl 24)
	}

	/**
	 * Reads a little-endian unsigned 32-bit integer as a [Long] (0..4294967295).
	 *
	 * EN: Section offsets and counts are stored as u32; in practice they fit in a signed Int (files
	 *     are < 2 GiB), but this avoids surprises near the boundary.
	 *
	 * @return Long The decoded value.
	 */
	public fun readU32(): Long = readInt32().toLong() and 0xFFFFFFFFL

	/**
	 * Reads a little-endian 32-bit IEEE-754 float.
	 *
	 * @return Float The decoded value.
	 */
	public fun readFloat32(): Float = Float.fromBits(readInt32())

	/**
	 * Reads [count] raw bytes.
	 *
	 * @param Int count Number of bytes to read.
	 * @return ByteArray A freshly allocated copy of the bytes.
	 */
	public fun readBytes(count: Int): ByteArray {
		val out = data.copyOfRange(position, position + count)
		position += count
		return out
	}

	/**
	 * Reads a fixed-width [width]-byte record as a NUL-terminated ASCII/UTF-8 string.
	 *
	 * EN: MOC3 IDs are 64-byte records: the identifier bytes, a NUL, then zero padding.
	 *
	 * @param Int width Record width in bytes (e.g. 64).
	 * @return String The string up to the first NUL.
	 */
	public fun readFixedString(width: Int): String {
		val raw = readBytes(width)
		var end = 0
		while (end < raw.size && raw[end].toInt() != 0) end++
		return raw.decodeToString(0, end)
	}
}