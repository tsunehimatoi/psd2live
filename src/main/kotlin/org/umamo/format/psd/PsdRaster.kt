// Ported from TwelveMonkeys imageio-psd (BSD-3-Clause, Copyright (c) 2013-2020 Harald Kuhr).
// The channel decompression (PackBits RLE, ZIP, ZIP-with-prediction) and the per-depth/per-mode
// sample assembly follow PSDImageReader, PSDUtil, and HorizontalDeDifferencingStream.  See CREDITS.md.

package org.umamo.format.psd

import org.umamo.format.art.LayerRaster
import org.umamo.format.binary.inflateZlib

/**
 * Decodes one PSD layer's channel image data into a straight-alpha RGBA8888 [LayerRaster].
 *
 * EN: A pure-Kotlin pixel decoder, needed because TwelveMonkeys / javax.imageio is absent on
 *     Android.  A layer's pixels are stored as separate
 *     channel planes (R, G, B, alpha, …), each independently compressed; this object decompresses
 *     each plane, reduces it to 8 bits, and interleaves them to RGBA cropped to the layer bounds.
 *     Supported: 8/16-bit RGB(A) and Grayscale, Indexed-color, and 1-bit Bitmap.  CMYK, Lab,
 *     Multichannel, Duotone, and 32-bit float are rejected by [PsdReader] before reaching here.
 * JA: TwelveMonkeys に頼らない純 Kotlin のピクセル復号。各チャンネル面を解凍し 8 ビットへ落として
 *     RGBA に合成する。RGB(A)/グレースケール(8/16bit)・インデックス・1bit のみ対応。
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md §4 Channel image data</a>
 */
internal object PsdRaster {
	// PSD: channel image-data compression codes (PSD.java COMPRESSION_*).
	private const val COMPRESSION_NONE = 0
	private const val COMPRESSION_RLE = 1
	private const val COMPRESSION_ZIP = 2
	private const val COMPRESSION_ZIP_PREDICTION = 3

	// PSD: color modes (PSD.java COLOR_MODE_*).
	private const val MODE_BITMAP = 0
	private const val MODE_GRAYSCALE = 1
	private const val MODE_INDEXED = 2
	private const val MODE_RGB = 3

	// PSD: channel ids - color channels are 0/1/2…; -1 is the transparency (alpha) channel.
	private const val CHANNEL_RED = 0
	private const val CHANNEL_GREEN = 1
	private const val CHANNEL_BLUE = 2
	private const val CHANNEL_ALPHA = -1

	private const val OPAQUE = 0xFF.toByte()

	/**
	 * Decodes [record]'s channels and assembles them into an RGBA8888 raster sized to the layer's
	 * bounds (top row first, straight alpha).
	 *
	 * @param ByteArray bytes          The complete `.psd` file.
	 * @param PsdHeaderInfo header      The file header (depth and color mode drive the decode).
	 * @param ByteArray colorModeData   The Color Mode Data bytes (the indexed palette; empty otherwise).
	 * @param PsdLayerRecord record      The layer to decode, carrying its channel-data offset.
	 * @return LayerRaster The cropped, straight-alpha RGBA pixels.
	 */
	fun decodeLayer(bytes: ByteArray, header: PsdHeaderInfo, colorModeData: ByteArray, record: PsdLayerRecord): LayerRaster {
		val width = record.bounds.width
		val height = record.bounds.height
		if (width <= 0 || height <= 0) {
			// A layer with no pixel area (an empty paint layer or a structural record): nothing to decode.
			return LayerRaster(width = width.coerceAtLeast(0), height = height.coerceAtLeast(0), rgba = ByteArray(0))
		}

		val rowBytes = bytesPerRow(width, header.depth)
		val planes = decodePlanes(bytes, record, width, height, header.depth, rowBytes)

		val rgba = ByteArray(width * height * 4)
		when (header.colorMode) {
			MODE_RGB -> assembleRgb(planes, rgba, width * height)
			MODE_GRAYSCALE -> assembleGrayscale(planes, rgba, width * height)
			MODE_INDEXED -> assembleIndexed(planes, colorModeData, rgba, width * height)
			MODE_BITMAP -> assembleBitmap(planes, rgba, width * height)
			else -> error("PSD color mode ${header.colorMode} is not supported")
		}
		return LayerRaster(width = width, height = height, rgba = rgba)
	}

	/**
	 * Decompresses every displayable channel of [record] into an 8-bit-per-pixel plane, keyed by
	 * channel id.  Mask channels (id < -1) are skipped; the cursor advances by each channel's full
	 * block length regardless, keeping the channels sequential.
	 *
	 * @param ByteArray bytes      The complete `.psd` file.
	 * @param PsdLayerRecord record The layer whose channels to decode.
	 * @param Int width            Layer width in pixels.
	 * @param Int height           Layer height in pixels.
	 * @param Int depth            Bits per sample (1, 8, or 16).
	 * @param Int rowBytes         Bytes per decoded channel row for this width and depth.
	 * @return Map Each channel id mapped to its width*height 8-bit plane.
	 */
	private fun decodePlanes(bytes: ByteArray, record: PsdLayerRecord, width: Int, height: Int, depth: Int, rowBytes: Int): Map<Int, ByteArray> {
		val planes = HashMap<Int, ByteArray>(record.channels.size)
		var cursor = record.channelDataOffset
		for (channel in record.channels) {
			val blockStart = cursor
			val blockEnd = blockStart + channel.length.toInt()
			cursor = blockEnd
			// PSD: channel id < -1 is a user/vector layer mask (-2/-3) - not displayable color, skip it.
			if (channel.id < CHANNEL_ALPHA) {
				continue
			}
			// PSD: each channel block begins with a 2-byte compression code, then the (possibly
			// compressed) sample bytes for that one channel.
			val compression = readU16BE(bytes, blockStart)
			val samples = decodeChannel(bytes, blockStart + 2, blockEnd, compression, width, height, depth, rowBytes)
			planes[channel.id] = samplesToPlane(samples, width, height, depth, rowBytes)
		}
		return planes
	}

	/**
	 * Decompresses one channel's sample bytes (size height*rowBytes), per its compression code.
	 *
	 * @param ByteArray bytes      The complete `.psd` file.
	 * @param Int dataStart        Offset of the channel's sample data (just past the compression code).
	 * @param Int blockEnd         One past the last byte of this channel's block.
	 * @param Int compression      The PSD compression code (0=raw, 1=RLE, 2=ZIP, 3=ZIP+prediction).
	 * @param Int width            Layer width in pixels.
	 * @param Int height           Layer height in pixels.
	 * @param Int depth            Bits per sample (1, 8, or 16).
	 * @param Int rowBytes         Bytes per decoded row.
	 * @return ByteArray The raw, row-major sample bytes for this channel.
	 */
	private fun decodeChannel(bytes: ByteArray, dataStart: Int, blockEnd: Int, compression: Int, width: Int, height: Int, depth: Int, rowBytes: Int): ByteArray {
		val total = height * rowBytes
		return when (compression) {
			COMPRESSION_NONE -> {
				// PSD: raw, uncompressed sample bytes, exactly height*rowBytes.
				val out = ByteArray(total)
				val available = minOf(total, blockEnd - dataStart, bytes.size - dataStart)
				if (available > 0) {
					bytes.copyInto(out, 0, dataStart, dataStart + available)
				}
				out
			}

			COMPRESSION_RLE -> decodeRle(bytes, dataStart, height, rowBytes)
			COMPRESSION_ZIP -> inflate(bytes, dataStart, blockEnd - dataStart, total)
			COMPRESSION_ZIP_PREDICTION -> {
				val inflated = inflate(bytes, dataStart, blockEnd - dataStart, total)
				// PSD: ZIP-with-prediction stores horizontal differences; un-apply per row, per sample.
				horizontalUnpredict(inflated, width, height, depth, rowBytes)
				inflated
			}

			else -> error("unknown PSD compression $compression")
		}
	}

	/**
	 * Decodes a PackBits-RLE channel: a table of per-row compressed byte counts, then the runs.
	 *
	 * PSD: for layer channels, the byte counts (one u16 per row, top-to-bottom) directly precede the
	 * PackBits-encoded rows of this single channel.  Each row decodes to exactly rowBytes.
	 *
	 * @param ByteArray bytes   The complete `.psd` file.
	 * @param Int dataStart     Offset of the row-count table.
	 * @param Int height        Number of rows.
	 * @param Int rowBytes      Decoded bytes per row.
	 * @return ByteArray The decoded, row-major sample bytes (height*rowBytes).
	 */
	private fun decodeRle(bytes: ByteArray, dataStart: Int, height: Int, rowBytes: Int): ByteArray {
		var countCursor = dataStart
		val rowCounts = IntArray(height)
		for (rowIndex in 0 until height) {
			rowCounts[rowIndex] = readU16BE(bytes, countCursor)
			countCursor += 2
		}

		val out = ByteArray(height * rowBytes)
		var rowCursor = countCursor
		for (rowIndex in 0 until height) {
			unpackBits(bytes, rowCursor, rowCounts[rowIndex], out, rowIndex * rowBytes, rowBytes)
			rowCursor += rowCounts[rowIndex]
		}
		return out
	}

	/**
	 * Apple PackBits run-length decode of one row.
	 *
	 * A control byte n: 0..127 copies the next n+1 bytes verbatim; -127..-1 repeats the next byte
	 * 1-n times; -128 is a no-op.  All reads and writes are bounded so a corrupt run cannot overrun.
	 *
	 * @param ByteArray source       The buffer holding the compressed bytes.
	 * @param Int sourceOffset       Offset of this row's compressed data.
	 * @param Int sourceLength       Compressed byte count for this row.
	 * @param ByteArray destination  The decoded-output buffer.
	 * @param Int destinationOffset  Offset of this row in the output.
	 * @param Int destinationLength  Decoded bytes expected for this row (rowBytes).
	 */
	private fun unpackBits(source: ByteArray, sourceOffset: Int, sourceLength: Int, destination: ByteArray, destinationOffset: Int, destinationLength: Int) {
		var sourceIndex = sourceOffset
		val sourceEnd = minOf(sourceOffset + sourceLength, source.size)
		var destinationIndex = destinationOffset
		val destinationEnd = destinationOffset + destinationLength
		while (sourceIndex < sourceEnd && destinationIndex < destinationEnd) {
			val control = source[sourceIndex++].toInt() // signed
			if (control >= 0) {
				val literalCount = control + 1
				var copied = 0
				while (copied < literalCount && sourceIndex < sourceEnd && destinationIndex < destinationEnd) {
					destination[destinationIndex++] = source[sourceIndex++]
					copied++
				}
			} else if (control != -128) {
				val runCount = 1 - control
				if (sourceIndex >= sourceEnd) {
					break
				}
				val runValue = source[sourceIndex++]
				var written = 0
				while (written < runCount && destinationIndex < destinationEnd) {
					destination[destinationIndex++] = runValue
					written++
				}
			}
		}
	}

	/**
	 * Inflates a zlib stream of [length] bytes at [offset] into a buffer of [expected] bytes.
	 *
	 * PSD ZIP channels are standard zlib streams (header + Adler-32), so the shared platform bridge
	 * decodes them.  A short or malformed stream yields a partially filled (zero-padded) buffer rather
	 * than throwing, mirroring [org.umamo.format.clip.ClipRaster].
	 *
	 * @param ByteArray bytes   The buffer holding the compressed stream.
	 * @param Int offset        Offset of the zlib stream.
	 * @param Int length        Compressed byte count.
	 * @param Int expected      Decompressed byte count (height*rowBytes).
	 * @return ByteArray The inflated bytes, sized [expected].
	 */
	private fun inflate(bytes: ByteArray, offset: Int, length: Int, expected: Int): ByteArray {
		val inflated = inflateZlib(bytes, offset, length, expected)
		if (inflated.size == expected) {
			return inflated
		}
		// A short stream leaves the rest zero-filled; the layer is best-effort rather than a hard failure.
		val out = ByteArray(expected)
		inflated.copyInto(out, 0, 0, minOf(inflated.size, expected))
		return out
	}

	/**
	 * Reverses the horizontal differencing predictor in place: each sample is the running sum of the
	 * differences along its row (per the TIFF 6.0 predictor, with one sample per pixel for planar
	 * channels).  Only 8- and 16-bit samples occur with prediction in the supported color modes.
	 *
	 * @param ByteArray data    The inflated, differenced sample bytes (modified in place).
	 * @param Int width         Samples per row.
	 * @param Int height        Row count.
	 * @param Int depth         Bits per sample (8 or 16).
	 * @param Int rowBytes      Bytes per row.
	 */
	private fun horizontalUnpredict(data: ByteArray, width: Int, height: Int, depth: Int, rowBytes: Int) {
		when (depth) {
			8 -> {
				for (rowIndex in 0 until height) {
					val base = rowIndex * rowBytes
					for (column in 1 until width) {
						val offset = base + column
						if (offset < data.size) {
							data[offset] = (data[offset] + data[offset - 1]).toByte()
						}
					}
				}
			}

			16 -> {
				for (rowIndex in 0 until height) {
					val base = rowIndex * rowBytes
					for (column in 1 until width) {
						val offset = base + column * 2
						if (offset + 1 < data.size) {
							val previous = ((data[offset - 2].toInt() and 0xFF) shl 8) or (data[offset - 1].toInt() and 0xFF)
							val current = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
							val sum = (previous + current) and 0xFFFF
							data[offset] = (sum ushr 8).toByte()
							data[offset + 1] = sum.toByte()
						}
					}
				}
			}

			else -> error("PSD ZIP prediction unsupported for $depth-bit samples")
		}
	}

	/**
	 * Reduces a channel's raw sample bytes to an 8-bit-per-pixel plane.
	 *
	 * 8-bit samples pass through; 16-bit samples keep the high (big-endian first) byte; 1-bit samples
	 * (Bitmap mode) expand to an intensity where a set bit is black (PSD's bitmap convention) - the
	 * Bitmap assembler reads this plane directly as gray.
	 *
	 * @param ByteArray samples The raw, row-major sample bytes.
	 * @param Int width         Layer width in pixels.
	 * @param Int height        Layer height in pixels.
	 * @param Int depth         Bits per sample (1, 8, or 16).
	 * @param Int rowBytes      Bytes per sample row (gives the 1-bit row stride).
	 * @return ByteArray A width*height plane of 8-bit values.
	 */
	private fun samplesToPlane(samples: ByteArray, width: Int, height: Int, depth: Int, rowBytes: Int): ByteArray {
		val plane = ByteArray(width * height)
		when (depth) {
			8 -> {
				// rowBytes == width, so the samples are already a contiguous per-pixel plane.
				val count = minOf(plane.size, samples.size)
				samples.copyInto(plane, 0, 0, count)
			}

			16 -> {
				for (pixelIndex in 0 until width * height) {
					val sampleOffset = pixelIndex * 2
					if (sampleOffset < samples.size) {
						plane[pixelIndex] = samples[sampleOffset] // big-endian high byte
					}
				}
			}

			1 -> {
				for (rowIndex in 0 until height) {
					val rowBase = rowIndex * rowBytes
					for (column in 0 until width) {
						val byteIndex = rowBase + (column ushr 3)
						if (byteIndex < samples.size) {
							val bit = (samples[byteIndex].toInt() ushr (7 - (column and 7))) and 1
							// PSD: in Bitmap mode a set bit is black; clear is white.
							plane[rowIndex * width + column] = if (bit == 1) 0 else OPAQUE
						}
					}
				}
			}

			else -> error("PSD $depth-bit depth is not supported")
		}
		return plane
	}

	/**
	 * Interleaves R/G/B planes (and the alpha plane, or opaque if absent) into RGBA8888.
	 *
	 * @param Map planes        The decoded channel planes by id.
	 * @param ByteArray rgba    The destination RGBA buffer.
	 * @param Int pixelCount    Number of pixels (width*height).
	 */
	private fun assembleRgb(planes: Map<Int, ByteArray>, rgba: ByteArray, pixelCount: Int) {
		val red = planes[CHANNEL_RED]
		val green = planes[CHANNEL_GREEN]
		val blue = planes[CHANNEL_BLUE]
		val alpha = planes[CHANNEL_ALPHA]
		for (pixelIndex in 0 until pixelCount) {
			val destination = pixelIndex * 4
			rgba[destination] = red?.get(pixelIndex) ?: 0
			rgba[destination + 1] = green?.get(pixelIndex) ?: 0
			rgba[destination + 2] = blue?.get(pixelIndex) ?: 0
			rgba[destination + 3] = alpha?.get(pixelIndex) ?: OPAQUE
		}
	}

	/**
	 * Expands a single gray plane to R=G=B, with the alpha plane (or opaque if absent).
	 *
	 * @param Map planes        The decoded channel planes by id.
	 * @param ByteArray rgba    The destination RGBA buffer.
	 * @param Int pixelCount    Number of pixels (width*height).
	 */
	private fun assembleGrayscale(planes: Map<Int, ByteArray>, rgba: ByteArray, pixelCount: Int) {
		val gray = planes[CHANNEL_RED] // PSD: grayscale's single channel has id 0
		val alpha = planes[CHANNEL_ALPHA]
		for (pixelIndex in 0 until pixelCount) {
			val destination = pixelIndex * 4
			val value = gray?.get(pixelIndex) ?: 0
			rgba[destination] = value
			rgba[destination + 1] = value
			rgba[destination + 2] = value
			rgba[destination + 3] = alpha?.get(pixelIndex) ?: OPAQUE
		}
	}

	/**
	 * Maps each palette index through the Color Mode Data table to RGB, with alpha (or opaque).
	 *
	 * PSD: the indexed palette is non-interleaved - all red bytes, then all green, then all blue -
	 * with paletteSize = colorModeData.length / 3 entries (typically 256).
	 *
	 * @param Map planes              The decoded channel planes by id.
	 * @param ByteArray colorModeData The palette bytes.
	 * @param ByteArray rgba          The destination RGBA buffer.
	 * @param Int pixelCount          Number of pixels (width*height).
	 */
	private fun assembleIndexed(planes: Map<Int, ByteArray>, colorModeData: ByteArray, rgba: ByteArray, pixelCount: Int) {
		val indices = planes[CHANNEL_RED] // PSD: the index channel has id 0
		val alpha = planes[CHANNEL_ALPHA]
		val paletteSize = colorModeData.size / 3
		for (pixelIndex in 0 until pixelCount) {
			val destination = pixelIndex * 4
			val paletteIndex = (indices?.get(pixelIndex)?.toInt() ?: 0) and 0xFF
			if (paletteSize > 0 && paletteIndex < paletteSize) {
				rgba[destination] = colorModeData[paletteIndex]
				rgba[destination + 1] = colorModeData[paletteSize + paletteIndex]
				rgba[destination + 2] = colorModeData[2 * paletteSize + paletteIndex]
			}
			rgba[destination + 3] = alpha?.get(pixelIndex) ?: OPAQUE
		}
	}

	/**
	 * Writes a 1-bit Bitmap plane as opaque gray (the plane already encodes black/white intensity).
	 *
	 * @param Map planes        The decoded channel planes by id.
	 * @param ByteArray rgba    The destination RGBA buffer.
	 * @param Int pixelCount    Number of pixels (width*height).
	 */
	private fun assembleBitmap(planes: Map<Int, ByteArray>, rgba: ByteArray, pixelCount: Int) {
		val value = planes[CHANNEL_RED] // PSD: bitmap's single channel has id 0
		for (pixelIndex in 0 until pixelCount) {
			val destination = pixelIndex * 4
			val gray = value?.get(pixelIndex) ?: 0
			rgba[destination] = gray
			rgba[destination + 1] = gray
			rgba[destination + 2] = gray
			rgba[destination + 3] = OPAQUE
		}
	}

	/**
	 * Bytes per decoded channel row for a given width and sample depth.
	 *
	 * @param Int width The row width in pixels.
	 * @param Int depth Bits per sample (1, 8, or 16).
	 * @return Int The packed/contiguous byte count for one row.
	 */
	private fun bytesPerRow(width: Int, depth: Int): Int =
		when (depth) {
			1 -> (width + 7) / 8 // PSD: 1-bit rows are byte-padded.
			8 -> width
			16 -> width * 2
			else -> error("PSD $depth-bit depth is not supported")
		}

	/**
	 * Reads a big-endian unsigned 16-bit integer.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the most-significant byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16BE(bytes: ByteArray, at: Int): Int =
		((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}