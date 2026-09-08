package org.umamo.format.cmo3.io

/**
 * Big-endian, XOR-obfuscating writer into a growable byte buffer.
 *
 * EN: Inverse of [BinaryReader];
 *     same big-endian + width-keyed XOR conventions.
 * JA: [BinaryReader] の逆操作。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Container: CAFF</a>
 */
public class BinaryWriter(initialCapacity: Int = 64 * 1024) {
	private var buffer = ByteArray(initialCapacity)

	/** Number of bytes written so far (also the current write cursor). */
	public var position: Int = 0
		private set

	private fun ensure(additional: Int) {
		val needed = position + additional
		if (needed > buffer.size) {
			var newSize = buffer.size * 2
			if (newSize < needed) newSize = needed
			buffer = buffer.copyOf(newSize)
		}
	}

	/** Returns a copy of the written bytes (length == [position]). */
	public fun toByteArray(): ByteArray = buffer.copyOf(position)

	/**
	 * Writes [count] zero bytes - a CAFF reserved "skip(n)" gap.
	 *
	 * @param Int count Number of zero bytes to emit.
	 */
	public fun skip(count: Int) {
		ensure(count)
		position += count
	}

	/**
	 * Overwrites an already-written big-endian int32 at an absolute offset (used for back-patching
	 * file offsets without a second streaming pass).
	 *
	 * @param Int offset Absolute byte offset of the 4-byte slot.
	 * @param Int value  Value to store (already XOR-encoded by the caller if needed).
	 */
	public fun patchInt32(offset: Int, value: Int) {
		buffer[offset] = (value ushr 24).toByte()
		buffer[offset + 1] = (value ushr 16).toByte()
		buffer[offset + 2] = (value ushr 8).toByte()
		buffer[offset + 3] = value.toByte()
	}

	/**
	 * Writes one byte, XOR-obfuscated with key&0xFF.
	 *
	 * @param Int value Byte value (low 8 bits used).
	 * @param Int key   Obfuscation key (0 = none).
	 */
	public fun writeU8(value: Int, key: Int) {
		ensure(1)
		buffer[position] = (value xor (key and 0xFF)).toByte()
		position += 1
	}

	/**
	 * Writes a big-endian 16-bit integer, XOR-obfuscated with key&0xFFFF.
	 *
	 * @param Int value Value to write (low 16 bits used).
	 * @param Int key   Obfuscation key (0 = none).
	 */
	public fun writeInt16(value: Int, key: Int) {
		ensure(2)
		val encoded = value xor (key and 0xFFFF)
		buffer[position] = (encoded ushr 8).toByte()
		buffer[position + 1] = encoded.toByte()
		position += 2
	}

	/**
	 * Writes a big-endian 32-bit integer, XOR-obfuscated with the full key.
	 *
	 * @param Int value Value to write.
	 * @param Int key   Obfuscation key (0 = none).
	 */
	public fun writeInt32(value: Int, key: Int) {
		ensure(4)
		val encoded = value xor key
		buffer[position] = (encoded ushr 24).toByte()
		buffer[position + 1] = (encoded ushr 16).toByte()
		buffer[position + 2] = (encoded ushr 8).toByte()
		buffer[position + 3] = encoded.toByte()
		position += 4
	}

	/**
	 * Writes a big-endian 64-bit integer, XOR-obfuscated with ((long) key << 32) | key.
	 *
	 * CMO3: the editor's 64-bit mask ORs the SIGN-EXTENDED key, so a negative key floods the
	 * high dword to all-ones (corpus-pinned: every negative-key file stores 0xFFFFFFFF high
	 * dwords for sub-4GB offsets).  A zero-extended mask matches only positive keys - the
	 * official reader then decodes garbage offsets and reports the file as empty.
	 *
	 * @param Long value Value to write.
	 * @param Int  key   Obfuscation key (0 = none).
	 */
	public fun writeInt64(value: Long, key: Int) {
		ensure(8)
		val mask = (key.toLong() shl 32) or key.toLong()
		val encoded = value xor mask
		for (byteIndex in 0 until 8) {
			buffer[position + byteIndex] = (encoded ushr (56 - 8 * byteIndex)).toByte()
		}
		position += 8
	}

	/**
	 * Writes a boolean as a single XOR'd byte (1/0).
	 *
	 * @param Boolean value Flag to write.
	 * @param Int     key   Obfuscation key (0 = none).
	 */
	public fun writeBool(value: Boolean, key: Int): Unit = writeU8(if (value) 1 else 0, key)

	/**
	 * Writes a 7-bit, MSB-first variable-length unsigned integer (inverse of [BinaryReader.readVarInt]).
	 *
	 * @param Int value Non-negative value to encode.
	 * @param Int key   Obfuscation key (0 = none).
	 */
	public fun writeVarInt(value: Int, key: Int) {
		when {
			value < 0x80 -> writeU8(value, key)
			value < 0x4000 -> {
				writeU8((value shr 7) and 0x7F or 0x80, key)
				writeU8(value and 0x7F, key)
			}
			value < 0x200000 -> {
				writeU8((value shr 14) and 0x7F or 0x80, key)
				writeU8((value shr 7) and 0x7F or 0x80, key)
				writeU8(value and 0x7F, key)
			}
			value < 0x10000000 -> {
				writeU8((value shr 21) and 0x7F or 0x80, key)
				writeU8((value shr 14) and 0x7F or 0x80, key)
				writeU8((value shr 7) and 0x7F or 0x80, key)
				writeU8(value and 0x7F, key)
			}
			else -> throw IllegalArgumentException("varint too large: $value")
		}
	}

	/**
	 * Writes a UTF-8 string: a [writeVarInt] length prefix followed by the XOR'd UTF-8 bytes.
	 *
	 * @param String value String to write.
	 * @param Int    key   Obfuscation key (0 = none).
	 */
	public fun writeString(value: String, key: Int) {
		val utf8 = value.encodeToByteArray()
		writeVarInt(utf8.size, key)
		writeBytes(utf8, key)
	}

	/**
	 * Writes raw bytes, optionally XOR-obfuscating each with key&0xFF.
	 *
	 * @param ByteArray bytes Bytes to write.
	 * @param Int       key   Obfuscation key (0 = none).
	 */
	public fun writeBytes(bytes: ByteArray, key: Int) {
		ensure(bytes.size)
		if (key == 0) {
			bytes.copyInto(buffer, position)
		} else {
			val byteKey = key and 0xFF
			for (index in bytes.indices) {
				buffer[position + index] = (bytes[index].toInt() xor byteKey).toByte()
			}
		}
		position += bytes.size
	}
}