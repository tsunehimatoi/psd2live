package org.umamo.format.cmo3.io

/**
 * Big-endian, XOR-deobfuscating reader over a byte array.
 *
 * EN: All multi-byte integers
 *     are big-endian; an obfuscation key XORs values by width (byte=key&0xFF, short=key&0xFFFF,
 *     int=key, long=(key<<32)|key). A key of 0 means "not obfuscated".
 * JA: 多バイト整数はビッグエンディアン。難読化キーで幅ごとに XOR する。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container: CAFF</a>
 */
public class BinaryReader(private val data: ByteArray) {
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
	 * EN: CAFF "skip(n)" is a literal reserved gap, NOT alignment padding.
	 *
	 * @param Int count Number of bytes to skip.
	 */
	public fun skip(count: Int) {
		position += count
	}

	/**
	 * Reads one unsigned byte (0..255), optionally XOR-deobfuscated.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Int The byte value in 0..255.
	 */
	public fun readU8(key: Int): Int {
		val raw = data[position].toInt() and 0xFF
		position += 1
		return raw xor (key and 0xFF)
	}

	/**
	 * Reads a big-endian signed 16-bit integer.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Short The decoded value.
	 */
	public fun readInt16(key: Int): Short {
		val high = data[position].toInt() and 0xFF
		val low = data[position + 1].toInt() and 0xFF
		position += 2
		val combined = (high shl 8) or low
		return (combined xor (key and 0xFFFF)).toShort()
	}

	/**
	 * Reads a big-endian signed 32-bit integer.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Int The decoded value.
	 */
	public fun readInt32(key: Int): Int {
		val byte0 = data[position].toInt() and 0xFF
		val byte1 = data[position + 1].toInt() and 0xFF
		val byte2 = data[position + 2].toInt() and 0xFF
		val byte3 = data[position + 3].toInt() and 0xFF
		position += 4
		val combined = (byte0 shl 24) or (byte1 shl 16) or (byte2 shl 8) or byte3
		return combined xor key
	}

	/**
	 * Reads a big-endian signed 64-bit integer.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Long The decoded value.
	 */
	public fun readInt64(key: Int): Long {
		var combined = 0L
		for (byteIndex in 0 until 8) {
			combined = (combined shl 8) or (data[position + byteIndex].toLong() and 0xFFL)
		}
		position += 8
		// CMO3: the editor's 64-bit mask ORs the SIGN-EXTENDED key (see BinaryWriter.writeInt64);
		// a negative key floods the high dword with ones.
		val mask = (key.toLong() shl 32) or key.toLong()
		return combined xor mask
	}

	/**
	 * Reads a boolean stored as a single byte (non-zero = true).
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Boolean The decoded flag.
	 */
	public fun readBool(key: Int): Boolean = readU8(key) != 0

	/**
	 * Reads a 7-bit, MSB-first variable-length unsigned integer.
	 *
	 * EN: A 7-bit variable-length integer: each byte carries 7 value bits;
	 *     the high bit signals continuation. Used as the length prefix for strings.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return Int The decoded length/count.
	 */
	public fun readVarInt(key: Int): Int {
		val byte0 = readU8(key)
		if (byte0 and 0x80 == 0) return byte0 and 0xFF
		val byte1 = readU8(key)
		if (byte1 and 0x80 == 0) return ((byte0 and 0x7F) shl 7) or (byte1 and 0x7F)
		val byte2 = readU8(key)
		if (byte2 and 0x80 == 0) {
			return ((byte0 and 0x7F) shl 14) or ((byte1 and 0x7F) shl 7) or (byte2 and 0xFF)
		}
		val byte3 = readU8(key)
		if (byte3 and 0x80 == 0) {
			return ((byte0 and 0x7F) shl 21) or ((byte1 and 0x7F) shl 14) or
				((byte2 and 0x7F) shl 7) or (byte3 and 0xFF)
		}
		throw IllegalStateException("varint exceeds 4 bytes at offset ${position - 4}")
	}

	/**
	 * Reads a UTF-8 string: a [readVarInt] byte length followed by that many XOR'd UTF-8 bytes.
	 *
	 * @param Int key Obfuscation key (0 = none).
	 * @return String The decoded string.
	 */
	public fun readString(key: Int): String {
		val length = readVarInt(key)
		val raw = readBytes(length, key)
		return raw.decodeToString()
	}

	/**
	 * Reads [count] raw bytes, optionally XOR-deobfuscating each with key&0xFF.
	 *
	 * @param Int count Number of bytes to read.
	 * @param Int key   Obfuscation key (0 = none).
	 * @return ByteArray A freshly allocated array of the (decoded) bytes.
	 */
	public fun readBytes(count: Int, key: Int): ByteArray {
		val out = ByteArray(count)
		val byteKey = (key and 0xFF).toByte()
		for (index in 0 until count) {
			out[index] = if (key == 0) data[position + index] else (data[position + index].toInt() xor byteKey.toInt()).toByte()
		}
		position += count
		return out
	}
}