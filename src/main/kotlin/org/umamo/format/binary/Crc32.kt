package org.umamo.format.binary

/**
 * The CRC-32 lookup table for the reflected IEEE 802.3 polynomial (0xEDB88320), built once.
 *
 * Each entry is the CRC of a single byte value, so the update loop is one table lookup per byte.
 */
private val CRC32_TABLE: IntArray =
	IntArray(256) { tableIndex ->
		var remainder = tableIndex
		repeat(8) {
			remainder =
				if ((remainder and 1) != 0) {
					(remainder ushr 1) xor 0xEDB88320.toInt()
				} else {
					remainder ushr 1
				}
		}
		remainder
	}

/**
 * CRC-32 (IEEE 802.3), the checksum PNG stamps on every chunk (PNG spec §5.3) and ZIP on every entry
 * (APPNOTE.TXT 4.3.9) — hence a shared utility here rather than something a single codec owns.
 *
 * A pure-Kotlin equivalent of `java.util.zip.CRC32`, mirroring its shape (accumulate with [update],
 * read with [value]) so the codecs that use it stay in commonMain and compile for every target.
 */
internal class Crc32 {
	// The running remainder, pre-conditioned to all-ones per the standard.
	private var remainder = -1

	/**
	 * Folds every byte of [bytes] into the running checksum.
	 *
	 * @param ByteArray bytes The bytes to accumulate.
	 */
	fun update(bytes: ByteArray) {
		var running = remainder
		for (byteValue in bytes) {
			running = CRC32_TABLE[(running xor byteValue.toInt()) and 0xFF] xor (running ushr 8)
		}
		remainder = running
	}

	/** The checksum of everything accumulated so far, as an unsigned 32-bit value. */
	val value: Long
		get() = remainder.inv().toLong() and 0xFFFFFFFFL
}