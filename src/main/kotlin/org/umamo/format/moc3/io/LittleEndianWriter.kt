package org.umamo.format.moc3.io

/**
 * Little-endian, non-obfuscated writer into a growable byte buffer.
 *
 * EN: Inverse of [LittleEndianReader]; same little-endian conventions, plus [alignTo] for the
 *     zero-padding MOC3 uses between sections / at end of file.
 * JA: [LittleEndianReader] の逆操作。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public class LittleEndianWriter(initialCapacity: Int = 64 * 1024) {
	private var buffer = ByteArray(initialCapacity)

	/** Number of bytes written so far (also the current write cursor). */
	public var position: Int = 0
		private set

	/**
	 * Grows the backing buffer if needed so that [additional] more bytes fit from [position].
	 *
	 * Doubles capacity (amortised O(1) appends), but jumps straight to the required size when a single
	 * write exceeds a doubling.
	 *
	 * @param Int additional Number of bytes about to be written.
	 */
	private fun ensure(additional: Int) {
		val needed = position + additional
		if (needed > buffer.size) {
			var newSize = buffer.size * 2
			if (newSize < needed) {
				newSize = needed
			}
			buffer = buffer.copyOf(newSize)
		}
	}

	/** Returns a copy of the written bytes (length == [position]). */
	public fun toByteArray(): ByteArray = buffer.copyOf(position)

	/**
	 * Writes [count] zero bytes.
	 *
	 * @param Int count Number of zero bytes to emit.
	 */
	public fun zeroPad(count: Int) {
		ensure(count)
		position += count
	}

	/**
	 * Zero-pads until [position] is a multiple of [alignment].
	 *
	 * @param Int alignment Byte boundary to align to (e.g. 64).
	 */
	public fun alignTo(alignment: Int) {
		val remainder = position % alignment
		if (remainder != 0) {
			zeroPad(alignment - remainder)
		}
	}

	/**
	 * Writes one byte (low 8 bits used).
	 *
	 * @param Int value Byte value.
	 */
	public fun writeU8(value: Int) {
		ensure(1)
		buffer[position] = value.toByte()
		position += 1
	}

	/**
	 * Writes a little-endian 16-bit integer (low 16 bits used).
	 *
	 * Takes an [Int] and masks rather than taking a [Short] with a range precondition: callers pass
	 * `shortValue.toInt()`, which sign-extends above 32767, and a mesh with more than 32767 vertices
	 * writes exactly those values.  A `require` here would reject a file the format permits.
	 *
	 * @param Int value Value to write; only the low 16 bits are used.
	 */
	public fun writeU16(value: Int) {
		ensure(2)
		buffer[position] = value.toByte()
		buffer[position + 1] = (value ushr 8).toByte()
		position += 2
	}

	/**
	 * Writes a little-endian 32-bit integer.
	 *
	 * @param Int value Value to write.
	 */
	public fun writeInt32(value: Int) {
		ensure(4)
		buffer[position] = value.toByte()
		buffer[position + 1] = (value ushr 8).toByte()
		buffer[position + 2] = (value ushr 16).toByte()
		buffer[position + 3] = (value ushr 24).toByte()
		position += 4
	}

	/**
	 * Writes a little-endian 32-bit IEEE-754 float.
	 *
	 * @param Float value Value to write.
	 */
	public fun writeFloat32(value: Float): Unit = writeInt32(value.toRawBits())

	/**
	 * Writes raw bytes verbatim.
	 *
	 * @param ByteArray bytes Bytes to write.
	 */
	public fun writeBytes(bytes: ByteArray) {
		ensure(bytes.size)
		bytes.copyInto(buffer, position)
		position += bytes.size
	}

	/**
	 * Writes [value] as a fixed-width [width]-byte NUL-terminated record, zero-padded to width.
	 *
	 * Inverse of [LittleEndianReader.readFixedString].  MOC3 IDs are 64-byte records holding
	 * plain ASCII (MOC3 §5.4), so the encoded form must fit in `width - 1` bytes to leave room for
	 * the terminator; the corpus's longest id is well inside that.
	 *
	 * @param String value The identifier to write.
	 * @param Int    width Record width in bytes (e.g. 64).
	 * @pre The UTF-8 encoding of [value] is shorter than [width].
	 */
	public fun writeFixedString(value: String, width: Int) {
		val encoded = value.encodeToByteArray()
		require(encoded.size < width) { "id \"$value\" (${encoded.size} bytes) does not fit a $width-byte record" }
		writeBytes(encoded)
		zeroPad(width - encoded.size)
	}

	/**
	 * Overwrites an already-written little-endian int32 at an absolute offset (back-patching).
	 *
	 * @param Int offset Absolute byte offset of the 4-byte slot.
	 * @param Int value  Value to store.
	 */
	public fun patchInt32(offset: Int, value: Int) {
		buffer[offset] = value.toByte()
		buffer[offset + 1] = (value ushr 8).toByte()
		buffer[offset + 2] = (value ushr 16).toByte()
		buffer[offset + 3] = (value ushr 24).toByte()
	}
}