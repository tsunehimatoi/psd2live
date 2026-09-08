package org.umamo.format.clip

import org.umamo.format.binary.decodeAscii

/**
 * Parser for the outer CLIP (Clip Studio Paint) container: the CSFCHUNK wrapper.
 *
 * EN: A .clip file is a flat sequence of 8-byte-ASCII-tagged chunks with big-endian u64 sizes,
 *     verified against several samples(`>8sQ`).  This object walks that sequence and hands back
 *     the embedded SQLite database (the CHNKSQLi payload), which carries the layer table the
 *     reader needs.  The tiled raster in the CHNKExta chunks is left untouched.
 * JA: CLIP コンテナ（CSFCHUNK）を解析し, 埋め込み SQLite（CHNKSQLi）を取り出す.
 *
 * Layout (all integers big-endian):
 *   // CLIP: @ +0x00  "CSFCHUNK" (8)  container magic
 *   // CLIP: @ +0x08  fileSize   u64  whole-file length
 *   // CLIP: @ +0x10  headOffset u64  offset of the first chunk (= 24)
 *   // CLIP: then [8-byte id][u64 size][size bytes payload] repeated:
 *   //         "CHNKHead" header · "CHNKExta" external raster · "CHNKSQLi" db · "CHNKFoot" terminator
 */
object ClipContainer {
	/** Container magic at offset 0. */
	internal const val MAGIC = "CSFCHUNK"

	private const val CHUNK_SQLITE = "CHNKSQLi"
	private const val CHUNK_FOOTER = "CHNKFoot"

	// An 8-byte ASCII id + an 8-byte big-endian u64 size precede every chunk payload.
	private const val CHUNK_HEADER_SIZE = 16

	/**
	 * True if [candidateBytes] begins with the CSFCHUNK container magic.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the CLIP magic.
	 */
	fun isClip(candidateBytes: ByteArray): Boolean {
		// CLIP: CSFCHUNK container magic @ +0x00.
		val magic = MAGIC.encodeToByteArray()
		if (candidateBytes.size < magic.size) {
			return false
		}
		for (magicIndex in magic.indices) {
			if (candidateBytes[magicIndex] != magic[magicIndex]) {
				return false
			}
		}
		return true
	}

	/**
	 * Extracts the embedded SQLite database (the CHNKSQLi chunk payload) from a complete .clip file.
	 *
	 * Walks the chunk sequence from the header offset, returning the byte slice of the first
	 * CHNKSQLi payload.  Stops at CHNKFoot.  The returned array is a fresh copy, safe to write to a
	 * temp file for the SQLite driver.
	 *
	 * @param ByteArray bytes The complete .clip file contents.
	 * @return ByteArray The raw "SQLite format 3" database bytes.
	 * @throws IllegalArgumentException If the magic is missing or no CHNKSQLi chunk is present.
	 */
	fun extractSqliteDatabase(bytes: ByteArray): ByteArray {
		require(isClip(bytes)) { "not a .clip file: missing CSFCHUNK magic @ +0x00" }

		// CLIP: @ +0x10 headOffset u64 - where the first chunk begins (24 in every observed file).
		val headOffset = readUInt64(bytes, 16)
		var offset = requireFits(headOffset, "headOffset")

		while (offset + CHUNK_HEADER_SIZE <= bytes.size) {
			val chunkId = readAscii(bytes, offset, 8)
			// CLIP: chunk size u64 immediately after the 8-byte id.
			val payloadSize = requireFits(readUInt64(bytes, offset + 8), "chunk '$chunkId' size")
			val payloadStart = offset + CHUNK_HEADER_SIZE

			if (chunkId == CHUNK_SQLITE) {
				val payloadEnd = payloadStart + payloadSize
				require(payloadEnd <= bytes.size) {
					"CHNKSQLi payload runs past EOF (need $payloadEnd, have ${bytes.size})"
				}
				return bytes.copyOfRange(payloadStart, payloadEnd)
			}
			if (chunkId == CHUNK_FOOTER) {
				break
			}

			offset = payloadStart + payloadSize
		}

		throw IllegalArgumentException("no CHNKSQLi chunk found in the CLIP container")
	}

	/**
	 * Reads a big-endian unsigned 64-bit integer as a Long.
	 *
	 * @param ByteArray bytes The buffer to read from.
	 * @param Int at The offset of the most-significant byte.
	 * @return Long The decoded value (treated as non-negative; CLIP sizes never use the sign bit).
	 */
	private fun readUInt64(bytes: ByteArray, at: Int): Long {
		require(at + 8 <= bytes.size) { "truncated u64 @ +$at" }
		var value = 0L
		for (byteIndex in 0 until 8) {
			value = (value shl 8) or (bytes[at + byteIndex].toLong() and 0xFF)
		}
		return value
	}

	/**
	 * Reads a fixed-length ASCII string (chunk ids are 8-byte ASCII tags).
	 *
	 * @param ByteArray bytes The buffer to read from.
	 * @param Int at The start offset.
	 * @param Int length The number of bytes to read.
	 * @return String The decoded ASCII string.
	 */
	private fun readAscii(bytes: ByteArray, at: Int, length: Int): String {
		require(at + length <= bytes.size) { "truncated ascii @ +$at" }
		return decodeAscii(bytes, at, length)
	}

	/**
	 * Narrows a 64-bit file offset/size to an Int, since the whole file is held in one ByteArray
	 * (so any valid offset fits).  Rejects values that would overflow rather than silently wrapping.
	 *
	 * @param Long value The 64-bit value to narrow.
	 * @param String label A description used in the error message.
	 * @return Int The value as a non-negative Int.
	 */
	private fun requireFits(value: Long, label: String): Int {
		require(value in 0..Int.MAX_VALUE) { "$label out of addressable range: $value" }
		return value.toInt()
	}
}