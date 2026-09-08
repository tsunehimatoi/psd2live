package org.umamo.format.png

import okio.Buffer
import org.umamo.format.FileKind
import org.umamo.format.raster.RasterCodec
import org.umamo.format.raster.RasterImage

/**
 * Pure-Kotlin PNG codec, read and write.
 *
 * Decodes every PNG color type (grayscale, RGB, palette, grayscale+alpha, RGBA) at bit depths
 * 1/2/4/8/16, including tRNS transparency and Adam7 interlace, to the neutral straight-alpha
 * RGBA8888 top-first [RasterImage] (16-bit channels are down-converted to 8-bit, roadmap
 * invariant #6).  Encodes 8-bit RGBA (color type 6, non-interlaced) - the form the atlas /
 * thumbnail pipeline needs.  DEFLATE is the codec's only platform dependency, reached through the
 * zlib bridge in org.umamo.format.raster, so it decodes byte-identically on every target (no
 * javax.imageio / BitmapFactory, which is exactly what this replaces).
 */
public object PngCodec : RasterCodec {
	override val kind: FileKind = FileKind.Png

	// PNG spec §11.2.2 IHDR color types.
	private const val COLOR_GRAYSCALE = 0
	private const val COLOR_RGB = 2
	private const val COLOR_PALETTE = 3
	private const val COLOR_GRAYSCALE_ALPHA = 4
	private const val COLOR_RGBA = 6

	private const val OPAQUE = 0xFF.toByte()

	/** Adam7 per-pass origin and step (PNG spec §8.2): {startColumn, startRow, columnStep, rowStep}. */
	private val ADAM7_PASSES =
		arrayOf(
			intArrayOf(0, 0, 8, 8),
			intArrayOf(4, 0, 8, 8),
			intArrayOf(0, 4, 4, 8),
			intArrayOf(2, 0, 4, 4),
			intArrayOf(0, 2, 2, 4),
			intArrayOf(1, 0, 2, 2),
			intArrayOf(0, 1, 1, 2),
		)

	/**
	 * True if [candidateBytes] opens with the PNG signature.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the PNG magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = matchesPngSignature(candidateBytes)

	/**
	 * Decodes a `.png` into a straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.png` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		val chunks = readChunks(bytes)
		val ihdrChunk = chunks.firstOrNull { it.type == "IHDR" } ?: throw IllegalArgumentException("PNG has no IHDR")
		val header = parseHeader(ihdrChunk.data)

		val palette = chunks.firstOrNull { it.type == "PLTE" }?.data
		val transparency = chunks.firstOrNull { it.type == "tRNS" }?.data

		// PNG spec §5.4 IDAT: the image data may be split across multiple IDAT chunks; concatenate then inflate once.
		val idat = Buffer()
		for (chunk in chunks) {
			if (chunk.type == "IDAT") {
				idat.write(chunk.data)
			}
		}
		val idatBytes = idat.readByteArray()
		val channels = channelCount(header.colorType)
		val bitsPerPixel = channels * header.bitDepth
		val raw = inflateIdat(idatBytes, 0, idatBytes.size, expectedRawSize(header, bitsPerPixel))

		val rgba = ByteArray(header.width * header.height * 4)
		if (header.interlace == 1) {
			var cursor = 0
			for (pass in ADAM7_PASSES) {
				val passWidth = passExtent(header.width, pass[0], pass[2])
				val passHeight = passExtent(header.height, pass[1], pass[3])
				if (passWidth == 0 || passHeight == 0) {
					continue
				}
				cursor = decodePass(raw, cursor, rgba, header, palette, transparency, channels, bitsPerPixel, passWidth, passHeight, pass[0], pass[1], pass[2], pass[3])
			}
		} else {
			decodePass(raw, 0, rgba, header, palette, transparency, channels, bitsPerPixel, header.width, header.height, 0, 0, 1, 1)
		}
		return RasterImage(header.width, header.height, rgba)
	}

	/**
	 * Encodes a [RasterImage] as an 8-bit RGBA (color type 6), non-interlaced PNG.
	 *
	 * Every scanline uses filter type 0 (None) - the simplest valid choice; DEFLATE still compresses
	 * it well.  Round-trip is not byte-identical (zlib output varies), but decoding the result
	 * reproduces the input pixels exactly.
	 *
	 * @param RasterImage model The image to encode.
	 * @return ByteArray The complete `.png` file.
	 */
	override fun write(model: RasterImage): ByteArray {
		val out = Buffer()
		out.write(PNG_SIGNATURE)

		// PNG spec §11.2.2 IHDR: width, height, bitDepth=8, colourType=6 (RGBA), compression=0, filter=0, interlace=0.
		val ihdr = Buffer()
		ihdr.writeInt(model.width)
		ihdr.writeInt(model.height)
		ihdr.writeByte(8)
		ihdr.writeByte(COLOR_RGBA)
		ihdr.writeByte(0)
		ihdr.writeByte(0)
		ihdr.writeByte(0)
		writeChunk(out, "IHDR", ihdr.readByteArray())

		// Raw filtered scanlines: a leading filter byte (0=None) then width*4 RGBA bytes per row.
		val rowBytes = model.width * 4
		val filtered = ByteArray(model.height * (1 + rowBytes))
		var destination = 0
		var source = 0
		for (rowIndex in 0 until model.height) {
			filtered[destination++] = FILTER_NONE.toByte()
			model.rgba.copyInto(filtered, destination, source, source + rowBytes)
			destination += rowBytes
			source += rowBytes
		}
		writeChunk(out, "IDAT", deflateIdat(filtered))
		writeChunk(out, "IEND", ByteArray(0))
		return out.readByteArray()
	}

	/** The IHDR fields the decoder needs. */
	private class PngHeader(
		val width: Int,
		val height: Int,
		val bitDepth: Int,
		val colorType: Int,
		val interlace: Int,
	)

	/**
	 * Parses the 13-byte IHDR data (PNG spec §11.2.2).
	 *
	 * @param ByteArray data The IHDR chunk data.
	 * @return PngHeader The parsed header, validated for a supported color-type/bit-depth pairing.
	 */
	private fun parseHeader(data: ByteArray): PngHeader {
		require(data.size >= 13) { "PNG IHDR too short" }
		val width = readU32BE(data, 0).toInt()
		val height = readU32BE(data, 4).toInt()
		val bitDepth = data[8].toInt() and 0xFF
		val colorType = data[9].toInt() and 0xFF
		val interlace = data[12].toInt() and 0xFF
		require(width > 0 && height > 0) { "PNG has non-positive dimensions ${width}x$height" }
		// IHDR allows dimensions up to 2^31-1 each, whose product overflows the Int sizing the RGBA
		// buffer; reject rather than wrap to a negative (or plausible-but-wrong) allocation.
		require(width.toLong() * height * 4 <= Int.MAX_VALUE) { "PNG is too large to decode: ${width}x$height" }
		require(colorType == COLOR_GRAYSCALE || colorType == COLOR_RGB || colorType == COLOR_PALETTE || colorType == COLOR_GRAYSCALE_ALPHA || colorType == COLOR_RGBA) {
			"unsupported PNG color type $colorType"
		}
		require(interlace == 0 || interlace == 1) { "unsupported PNG interlace method $interlace" }
		return PngHeader(width, height, bitDepth, colorType, interlace)
	}

	/** Channels stored per pixel for a color type (palette and grayscale store one sample). */
	private fun channelCount(colorType: Int): Int =
		when (colorType) {
			COLOR_GRAYSCALE, COLOR_PALETTE -> 1
			COLOR_GRAYSCALE_ALPHA -> 2
			COLOR_RGB -> 3
			COLOR_RGBA -> 4
			else -> throw IllegalArgumentException("unsupported PNG color type $colorType")
		}

	/**
	 * The number of pixels a reduced Adam7 pass spans along one axis (PNG spec §8.2).
	 *
	 * @param Int total The full image extent (width or height).
	 * @param Int start The pass origin on this axis.
	 * @param Int step  The pass step on this axis.
	 * @return Int The count of samples this pass contributes on this axis (0 if the pass is empty).
	 */
	private fun passExtent(total: Int, start: Int, step: Int): Int =
		if (total <= start) {
			0
		} else {
			(total - start + step - 1) / step
		}

	/**
	 * The exact size of the decompressed IDAT stream, derived from IHDR alone.
	 *
	 * PNG does not record this anywhere, but it is fully determined: every scanline is one filter-type
	 * byte plus its packed samples, summed over the seven Adam7 passes when interlaced.  Computing it
	 * gives the inflate a real bound, so a corrupt or deliberately-bomb IDAT stops at the size the
	 * header promised instead of expanding until the process dies.
	 *
	 * @param PngHeader header  The parsed IHDR.
	 * @param Int bitsPerPixel  Channels times bit depth.
	 * @return Int The decompressed byte count.
	 */
	private fun expectedRawSize(header: PngHeader, bitsPerPixel: Int): Int {
		if (header.interlace != 1) {
			return scanlinesSize(header.width, header.height, bitsPerPixel)
		}
		var total = 0L
		for (pass in ADAM7_PASSES) {
			val passWidth = passExtent(header.width, pass[0], pass[2])
			val passHeight = passExtent(header.height, pass[1], pass[3])
			if (passWidth == 0 || passHeight == 0) {
				continue
			}
			total += scanlinesSize(passWidth, passHeight, bitsPerPixel).toLong()
		}
		return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
	}

	/**
	 * The decompressed size of [rows] scanlines of [columns] pixels: a filter-type byte plus the row's
	 * packed samples, each.
	 *
	 * @param Int columns      Pixels per row.
	 * @param Int rows         Number of rows.
	 * @param Int bitsPerPixel Channels times bit depth.
	 * @return Int The byte count.
	 */
	private fun scanlinesSize(columns: Int, rows: Int, bitsPerPixel: Int): Int =
		(rows.toLong() * (1L + (columns.toLong() * bitsPerPixel + 7) / 8)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

	/**
	 * Decodes one filtered sub-image (the whole image when non-interlaced, one Adam7 pass otherwise)
	 * from [raw] starting at [startOffset], scattering its pixels into [rgba] at their interlaced
	 * positions.
	 *
	 * @param ByteArray raw       The inflated, still-filtered scanline bytes.
	 * @param Int startOffset      Offset in [raw] of this sub-image's first filter byte.
	 * @param ByteArray rgba       The destination full-image RGBA buffer.
	 * @param PngHeader header      The IHDR parameters.
	 * @param ByteArray? palette    The PLTE bytes (3 per entry), or null.
	 * @param ByteArray? transparency The tRNS bytes, or null.
	 * @param Int channels          Samples per pixel.
	 * @param Int bitsPerPixel      channels * bitDepth.
	 * @param Int passWidth         Pixels per row in this sub-image.
	 * @param Int passHeight        Rows in this sub-image.
	 * @param Int startColumn       Adam7 column origin (0 for the full image).
	 * @param Int startRow          Adam7 row origin (0 for the full image).
	 * @param Int columnStep        Adam7 column step (1 for the full image).
	 * @param Int rowStep           Adam7 row step (1 for the full image).
	 * @return Int The offset in [raw] just past this sub-image.
	 */
	private fun decodePass(
		raw: ByteArray,
		startOffset: Int,
		rgba: ByteArray,
		header: PngHeader,
		palette: ByteArray?,
		transparency: ByteArray?,
		channels: Int,
		bitsPerPixel: Int,
		passWidth: Int,
		passHeight: Int,
		startColumn: Int,
		startRow: Int,
		columnStep: Int,
		rowStep: Int,
	): Int {
		val bytesPerPixel = maxOf(1, (bitsPerPixel + 7) / 8)
		val rowBytes = (bitsPerPixel * passWidth + 7) / 8
		var cursor = startOffset
		var previous = ByteArray(rowBytes)
		var current = ByteArray(rowBytes)
		for (rowIndex in 0 until passHeight) {
			val filterType = if (cursor < raw.size) raw[cursor].toInt() and 0xFF else FILTER_NONE
			cursor++
			val available = maxOf(0, minOf(rowBytes, raw.size - cursor))
			if (available > 0) {
				raw.copyInto(current, 0, cursor, cursor + available)
			}
			if (available < rowBytes) {
				current.fill(0, available, rowBytes)
			}
			cursor += rowBytes

			unfilterScanline(filterType, current, previous, bytesPerPixel)

			val imageY = startRow + rowIndex * rowStep
			for (columnIndex in 0 until passWidth) {
				val imageX = startColumn + columnIndex * columnStep
				val destination = (imageY * header.width + imageX) * 4
				writePixel(current, columnIndex, header, channels, palette, transparency, rgba, destination)
			}

			val swap = previous
			previous = current
			current = swap
		}
		return cursor
	}

	/**
	 * Extracts pixel [pixelIndex] from a reconstructed scanline and writes its straight-alpha RGBA8888
	 * to [rgba] at [destination], normalising bit depth, expanding palette, and applying tRNS.
	 *
	 * @param ByteArray scanline    The reconstructed row bytes.
	 * @param Int pixelIndex        The pixel within the row.
	 * @param PngHeader header       The IHDR parameters.
	 * @param Int channels           Samples per pixel.
	 * @param ByteArray? palette      The PLTE bytes, or null.
	 * @param ByteArray? transparency The tRNS bytes, or null.
	 * @param ByteArray rgba          The destination RGBA buffer.
	 * @param Int destination         The byte offset in [rgba] for this pixel.
	 */
	private fun writePixel(
		scanline: ByteArray,
		pixelIndex: Int,
		header: PngHeader,
		channels: Int,
		palette: ByteArray?,
		transparency: ByteArray?,
		rgba: ByteArray,
		destination: Int,
	) {
		when (header.colorType) {
			COLOR_GRAYSCALE -> {
				val rawGray = rawSample(scanline, pixelIndex, 0, channels, header.bitDepth)
				val gray = scaleToByte(rawGray, header.bitDepth)
				rgba[destination] = gray
				rgba[destination + 1] = gray
				rgba[destination + 2] = gray
				rgba[destination + 3] = if (transparency != null && transparency.size >= 2 && rawGray == readU16(transparency, 0)) 0 else OPAQUE
			}

			COLOR_RGB -> {
				val rawRed = rawSample(scanline, pixelIndex, 0, channels, header.bitDepth)
				val rawGreen = rawSample(scanline, pixelIndex, 1, channels, header.bitDepth)
				val rawBlue = rawSample(scanline, pixelIndex, 2, channels, header.bitDepth)
				rgba[destination] = scaleToByte(rawRed, header.bitDepth)
				rgba[destination + 1] = scaleToByte(rawGreen, header.bitDepth)
				rgba[destination + 2] = scaleToByte(rawBlue, header.bitDepth)
				val transparent =
					transparency != null &&
						transparency.size >= 6 &&
						rawRed == readU16(transparency, 0) &&
						rawGreen == readU16(transparency, 2) &&
						rawBlue == readU16(transparency, 4)
				rgba[destination + 3] = if (transparent) 0 else OPAQUE
			}

			COLOR_PALETTE -> {
				val index = rawSample(scanline, pixelIndex, 0, channels, header.bitDepth)
				val paletteOffset = index * 3
				if (palette != null && paletteOffset + 2 < palette.size) {
					rgba[destination] = palette[paletteOffset]
					rgba[destination + 1] = palette[paletteOffset + 1]
					rgba[destination + 2] = palette[paletteOffset + 2]
				}
				// PNG spec §11.3.2.1 tRNS for palette: one alpha byte per entry; unlisted entries are opaque.
				rgba[destination + 3] = if (transparency != null && index < transparency.size) transparency[index] else OPAQUE
			}

			COLOR_GRAYSCALE_ALPHA -> {
				val gray = scaleToByte(rawSample(scanline, pixelIndex, 0, channels, header.bitDepth), header.bitDepth)
				val alpha = scaleToByte(rawSample(scanline, pixelIndex, 1, channels, header.bitDepth), header.bitDepth)
				rgba[destination] = gray
				rgba[destination + 1] = gray
				rgba[destination + 2] = gray
				rgba[destination + 3] = alpha
			}

			COLOR_RGBA -> {
				rgba[destination] = scaleToByte(rawSample(scanline, pixelIndex, 0, channels, header.bitDepth), header.bitDepth)
				rgba[destination + 1] = scaleToByte(rawSample(scanline, pixelIndex, 1, channels, header.bitDepth), header.bitDepth)
				rgba[destination + 2] = scaleToByte(rawSample(scanline, pixelIndex, 2, channels, header.bitDepth), header.bitDepth)
				rgba[destination + 3] = scaleToByte(rawSample(scanline, pixelIndex, 3, channels, header.bitDepth), header.bitDepth)
			}
		}
	}

	/**
	 * Reads one raw channel sample from a reconstructed scanline, in the native bit-depth range.
	 *
	 * @param ByteArray scanline The reconstructed row bytes.
	 * @param Int pixelIndex     The pixel within the row.
	 * @param Int channelIndex   The channel within the pixel.
	 * @param Int channels       Samples per pixel.
	 * @param Int bitDepth       Bits per sample (1/2/4/8/16).
	 * @return Int The raw sample value (0..2^bitDepth - 1).
	 */
	private fun rawSample(scanline: ByteArray, pixelIndex: Int, channelIndex: Int, channels: Int, bitDepth: Int): Int {
		return when (bitDepth) {
			8 -> {
				val offset = pixelIndex * channels + channelIndex
				if (offset < scanline.size) scanline[offset].toInt() and 0xFF else 0
			}

			16 -> {
				val offset = (pixelIndex * channels + channelIndex) * 2
				if (offset + 1 < scanline.size) {
					((scanline[offset].toInt() and 0xFF) shl 8) or (scanline[offset + 1].toInt() and 0xFF)
				} else {
					0
				}
			}

			else -> {
				// Sub-byte samples (1/2/4 bit) occur only for grayscale/palette (one channel), MSB first.
				val bitIndex = pixelIndex * bitDepth
				val byteIndex = bitIndex ushr 3
				if (byteIndex >= scanline.size) {
					return 0
				}
				val shift = 8 - bitDepth - (bitIndex and 7)
				val mask = (1 shl bitDepth) - 1
				(scanline[byteIndex].toInt() ushr shift) and mask
			}
		}
	}

	/**
	 * Scales a raw sample in [0, 2^bitDepth - 1] to an 8-bit byte.  16-bit keeps the high byte
	 * (roadmap invariant #6 down-conversion); sub-8-bit scales up to fill 0..255; 8-bit passes through.
	 *
	 * @param Int sample   The raw sample value.
	 * @param Int bitDepth Bits per sample.
	 * @return Byte The 8-bit value.
	 */
	private fun scaleToByte(sample: Int, bitDepth: Int): Byte =
		when (bitDepth) {
			16 -> (sample ushr 8).toByte()
			8 -> sample.toByte()
			else -> {
				val maxValue = (1 shl bitDepth) - 1
				((sample * 255 + maxValue / 2) / maxValue).toByte()
			}
		}

	/**
	 * Reads a big-endian unsigned 16-bit value (tRNS stores each key sample this way).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the most-significant byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16(bytes: ByteArray, at: Int): Int =
		((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}