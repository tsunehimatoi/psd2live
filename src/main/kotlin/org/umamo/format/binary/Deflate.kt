package org.umamo.format.binary

import okio.Buffer
import okio.Deflater
import okio.DeflaterSink
import okio.IOException
import okio.Inflater
import okio.InflaterSource
import okio.buffer

/*
 * DEFLATE for the raster codecs and the CMO3 CAFF container.
 *
 * Two framings, because the callers need both:
 *   - zlib (RFC 1950: 2-byte header + deflate + Adler-32) — PNG IDAT, TIFF Deflate, PSD ZIP
 *     channels, CLIP tiles.
 *   - raw deflate (no wrapper; zlib's windowBits = -15) — the payload of a ZIP entry, which is what
 *     the CAFF codec frames for CMO3's compressed blobs.
 */

/** Asks zlib for its default (level 6), matching java.util.zip's DEFAULT_COMPRESSION. */
private const val DEFAULT_COMPRESSION_LEVEL = -1

/**
 * Inflates the zlib stream at [sourceOffset] into its decompressed bytes.
 *
 * Best-effort by design: a truncated or corrupt stream yields the bytes recovered so far rather than
 * throwing, matching the other readers and letting a partly damaged image still decode.
 *
 * @param ByteArray source  The buffer holding the zlib stream.
 * @param Int sourceOffset  Offset of the stream's first byte.
 * @param Int sourceLength  Compressed byte count.
 * @param Int maximumSize   An upper bound on the output, so a malformed stream cannot inflate without
 *                          limit; pass the decompressed size the container states.
 * @return ByteArray The inflated bytes.
 */
internal fun inflateZlib(source: ByteArray, sourceOffset: Int, sourceLength: Int, maximumSize: Int): ByteArray =
	inflate(source, sourceOffset, sourceLength, maximumSize, rawDeflate = false)

/**
 * Inflates a raw (unwrapped) deflate stream — the payload of a ZIP entry.
 *
 * @param ByteArray source  The buffer holding the deflate stream.
 * @param Int sourceOffset  Offset of the stream's first byte.
 * @param Int sourceLength  Compressed byte count.
 * @param Int maximumSize   An upper bound on the output.
 * @return ByteArray The inflated bytes.
 */
internal fun inflateRawDeflate(source: ByteArray, sourceOffset: Int, sourceLength: Int, maximumSize: Int): ByteArray =
	inflate(source, sourceOffset, sourceLength, maximumSize, rawDeflate = true)

/**
 * Compresses [source] to a zlib stream at the default compression level.
 *
 * @param ByteArray source The raw bytes to compress.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal fun deflateZlib(source: ByteArray): ByteArray = deflate(source, DEFAULT_COMPRESSION_LEVEL, rawDeflate = false)

/**
 * Compresses [source] to a raw (unwrapped) deflate stream, for embedding as a ZIP entry's payload.
 *
 * @param ByteArray source The raw bytes to compress.
 * @param Int level        The DEFLATE level (0..9).
 * @return ByteArray The raw deflate stream.
 */
internal fun deflateRawDeflate(source: ByteArray, level: Int): ByteArray = deflate(source, level, rawDeflate = true)

/**
 * The shared inflate path.
 *
 * @param ByteArray source   The buffer holding the stream.
 * @param Int sourceOffset   Offset of the stream's first byte.
 * @param Int sourceLength   Compressed byte count.
 * @param Int maximumSize    Upper bound on the output.
 * @param Boolean rawDeflate True for an unwrapped stream (windowBits -15), false for zlib framing.
 * @return ByteArray The inflated bytes, up to [maximumSize].
 */
private fun inflate(source: ByteArray, sourceOffset: Int, sourceLength: Int, maximumSize: Int, rawDeflate: Boolean): ByteArray {
	if (sourceLength <= 0 || sourceOffset >= source.size || maximumSize <= 0) {
		return ByteArray(0)
	}
	val available = minOf(sourceLength, source.size - sourceOffset)
	val compressed = Buffer().write(source, sourceOffset, available)
	// A raw stream has no trailer, and zlib wants a byte of lookahead past the final block to notice
	// it is finished; without it the inflater asks for more input at the very end and okio raises EOF.
	// java.util.zip documents the same "extra dummy byte" requirement for its nowrap mode.
	if (rawDeflate) {
		compressed.writeByte(0)
	}
	val inflated = Buffer()
	val inflaterSource = InflaterSource(compressed, Inflater(rawDeflate))
	try {
		while (inflated.size < maximumSize) {
			if (inflaterSource.read(inflated, maximumSize - inflated.size) == -1L) {
				break
			}
		}
	} catch (_: IOException) {
		// Best-effort: return what inflated so far rather than failing the whole decode.
	} finally {
		// Releases the inflater.  On a truncated stream close() can itself raise, which must not mask
		// the bytes already recovered.
		try {
			inflaterSource.close()
		} catch (_: IOException) {
			// Already best-effort.
		}
	}
	return inflated.readByteArray()
}

/**
 * The shared deflate path.
 *
 * @param ByteArray source   The raw bytes to compress.
 * @param Int level          The DEFLATE level.
 * @param Boolean rawDeflate True to omit the zlib wrapper.
 * @return ByteArray The compressed stream.
 */
private fun deflate(source: ByteArray, level: Int, rawDeflate: Boolean): ByteArray {
	val deflated = Buffer()
	val sink = DeflaterSink(deflated, Deflater(level, rawDeflate)).buffer()
	sink.write(source)
	// Finishes the stream and releases the deflater.
	sink.close()
	return deflated.readByteArray()
}