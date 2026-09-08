// TIFF decoder, ported from TwelveMonkeys imageio-tiff (BSD-3-Clause, Copyright (c) 2012 Harald
// Kuhr).  The container/IFD parse, codecs, and pixel assembly follow TIFFReader / TIFFImageReader /
// LZWDecoder / PackBitsDecoder / HorizontalDeDifferencingStream / CCITTFaxDecoderStream; see
// CREDITS.md.  JPEG-in-TIFF strips are handed to org.umamo.format.jpeg, which is our own code.

package org.umamo.format.tiff

import org.umamo.format.FileKind
import org.umamo.format.binary.inflateZlib
import org.umamo.format.jpeg.JpegColorTransform
import org.umamo.format.jpeg.decodeJpeg
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * Pure-Kotlin TIFF reader (desktop JVM and Android), read only.
 *
 * Decodes the first image of a classic (non-BigTIFF) TIFF to the neutral straight-alpha RGBA8888
 * top-first [RasterImage].  Scope: uncompressed / PackBits / LZW / Deflate / CCITT (Modified Huffman
 * RLE, T.4, T.6) / baseline JPEG strips and tiles; WhiteIsZero / BlackIsZero grayscale, RGB, palette,
 * and YCbCr (JPEG only); 1/2/4/8/16-bit unsigned samples (16 down-converts to 8, roadmap invariant
 * #6); horizontal-differencing predictor; chunky and planar configurations; ExtraSamples alpha
 * (associated -> un-premultiplied, unassociated -> straight).  Anything else (old-style JPEG /
 * float / CIELab / CMYK / BigTIFF / progressive JPEG) is rejected with a clear message.  Uses only
 * java.util.zip, so it decodes identically on both targets (no javax.imageio).
 */
public object TiffReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.Tiff

	/**
	 * True if [candidateBytes] opens with a little- or big-endian TIFF header.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the TIFF magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean {
		if (candidateBytes.size < 4) {
			return false
		}
		// TIFF: 'II' 0x2A00 (little-endian) or 'MM' 0x002A (big-endian) header @ +0x00.
		val littleEndian = candidateBytes[0] == 0x49.toByte() && candidateBytes[1] == 0x49.toByte() && candidateBytes[2] == 0x2A.toByte() && candidateBytes[3] == 0x00.toByte()
		val bigEndian = candidateBytes[0] == 0x4D.toByte() && candidateBytes[1] == 0x4D.toByte() && candidateBytes[2] == 0x00.toByte() && candidateBytes[3] == 0x2A.toByte()
		return littleEndian || bigEndian
	}

	/**
	 * Decodes the first image of a `.tiff` into a straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.tiff` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		require(matches(bytes)) { "Not a TIFF (bad signature)" }
		val directory = parseFirstDirectory(bytes)
		val littleEndian = directory.littleEndian

		val width = directory.int(TiffConstants.TAG_IMAGE_WIDTH, -1)
		val height = directory.int(TiffConstants.TAG_IMAGE_HEIGHT, -1)
		require(width > 0 && height > 0) { "TIFF has non-positive dimensions ${width}x$height" }

		val samplesPerPixel = directory.int(TiffConstants.TAG_SAMPLES_PER_PIXEL, 1)
		require(samplesPerPixel in 1..4) { "Unsupported TIFF samples-per-pixel $samplesPerPixel" }

		val bitsPerSampleValues = directory.ints(TiffConstants.TAG_BITS_PER_SAMPLE) ?: intArrayOf(1)
		val bitsPerSample = bitsPerSampleValues[0]
		for (sampleIndex in 1 until minOf(bitsPerSampleValues.size, samplesPerPixel)) {
			require(bitsPerSampleValues[sampleIndex] == bitsPerSample) { "Mixed TIFF bits-per-sample not supported" }
		}
		require(bitsPerSample == 1 || bitsPerSample == 2 || bitsPerSample == 4 || bitsPerSample == 8 || bitsPerSample == 16) {
			"Unsupported TIFF bits-per-sample $bitsPerSample"
		}

		val sampleFormat = directory.ints(TiffConstants.TAG_SAMPLE_FORMAT)?.firstOrNull() ?: TiffConstants.SAMPLEFORMAT_UINT
		require(sampleFormat == TiffConstants.SAMPLEFORMAT_UINT) { "Only unsigned-integer TIFF samples are supported (format $sampleFormat)" }

		val compression = directory.int(TiffConstants.TAG_COMPRESSION, TiffConstants.COMPRESSION_NONE)
		require(
			compression == TiffConstants.COMPRESSION_NONE ||
				compression == TiffConstants.COMPRESSION_PACKBITS ||
				compression == TiffConstants.COMPRESSION_LZW ||
				compression == TiffConstants.COMPRESSION_ZLIB ||
				compression == TiffConstants.COMPRESSION_DEFLATE ||
				compression == TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE ||
				compression == TiffConstants.COMPRESSION_CCITT_T4 ||
				compression == TiffConstants.COMPRESSION_CCITT_T6 ||
				compression == TiffConstants.COMPRESSION_JPEG,
		) { "Unsupported TIFF compression $compression (only none / PackBits / LZW / Deflate / CCITT / JPEG)" }

		val photometric =
			if (directory.has(TiffConstants.TAG_PHOTOMETRIC_INTERPRETATION)) {
				directory.int(TiffConstants.TAG_PHOTOMETRIC_INTERPRETATION, TiffConstants.PHOTOMETRIC_BLACK_IS_ZERO)
			} else if (samplesPerPixel >= 3) {
				TiffConstants.PHOTOMETRIC_RGB
			} else {
				TiffConstants.PHOTOMETRIC_BLACK_IS_ZERO
			}
		require(
			photometric == TiffConstants.PHOTOMETRIC_WHITE_IS_ZERO ||
				photometric == TiffConstants.PHOTOMETRIC_BLACK_IS_ZERO ||
				photometric == TiffConstants.PHOTOMETRIC_RGB ||
				photometric == TiffConstants.PHOTOMETRIC_PALETTE ||
				(photometric == TiffConstants.PHOTOMETRIC_YCBCR && compression == TiffConstants.COMPRESSION_JPEG),
		) { "Unsupported TIFF photometric interpretation $photometric" }
		// The two tags are validated independently above, so a file may still pair a color photometric
		// with too few samples.  assembleRgba reads three samples per pixel for those, which would run
		// off the end of a one-sample buffer, so require the pairing to be coherent up front.
		if (photometric == TiffConstants.PHOTOMETRIC_RGB || photometric == TiffConstants.PHOTOMETRIC_YCBCR) {
			require(samplesPerPixel >= 3) { "TIFF declares photometric $photometric but only $samplesPerPixel sample(s) per pixel" }
		}

		val planarConfig = directory.int(TiffConstants.TAG_PLANAR_CONFIGURATION, TiffConstants.PLANARCONFIG_CHUNKY)
		require(planarConfig == TiffConstants.PLANARCONFIG_CHUNKY || planarConfig == TiffConstants.PLANARCONFIG_PLANAR) {
			"Unsupported TIFF planar configuration $planarConfig"
		}

		val predictor = directory.int(TiffConstants.TAG_PREDICTOR, TiffConstants.PREDICTOR_NONE)
		require(predictor == TiffConstants.PREDICTOR_NONE || predictor == TiffConstants.PREDICTOR_HORIZONTAL_DIFFERENCING) {
			"Unsupported TIFF predictor $predictor"
		}

		val fillOrder = directory.int(TiffConstants.TAG_FILL_ORDER, TiffConstants.FILL_LEFT_TO_RIGHT)
		require(fillOrder == TiffConstants.FILL_LEFT_TO_RIGHT) { "Reversed TIFF fill order is not supported" }

		val extraSamples = directory.ints(TiffConstants.TAG_EXTRA_SAMPLES)
		val colorMap = directory.longs(TiffConstants.TAG_COLOR_MAP)

		val planeCount = if (planarConfig == TiffConstants.PLANARCONFIG_PLANAR) samplesPerPixel else 1
		val samplesInPlane = if (planarConfig == TiffConstants.PLANARCONFIG_PLANAR) 1 else samplesPerPixel

		// One raw sample value (native bit-depth range) per (pixel, channel), filled from every unit.
		val samples = IntArray(width * height * samplesPerPixel)
		val units = buildUnits(directory, width, height, samplesPerPixel, planeCount)
		val coding = buildCoding(directory, bytes, units, compression, predictor, photometric, littleEndian)
		for (unit in units) {
			decodeUnit(bytes, unit, width, samplesInPlane, samplesPerPixel, bitsPerSample, coding, samples)
		}

		// A JPEG-compressed YCbCr image is color-converted by the JPEG decoder, so it assembles as RGB.
		val assemblyPhotometric = if (photometric == TiffConstants.PHOTOMETRIC_YCBCR) TiffConstants.PHOTOMETRIC_RGB else photometric
		return RasterImage(width, height, assembleRgba(samples, width, height, samplesPerPixel, bitsPerSample, assemblyPhotometric, extraSamples, colorMap))
	}

	/** The per-image coding parameters every unit decode needs. */
	private class TiffCoding(
		val compression: Int,
		val predictor: Int,
		val littleEndian: Boolean,
		val ccittType: Int,
		val ccittOptions: Long,
		val ccittByteAligned: Boolean,
		val jpegTables: ByteArray?,
		val jpegColorTransform: Int?,
	)

	/**
	 * Gathers the compression-specific parameters for the image: the CCITT variant and options, and
	 * the JPEG tables and color transform.
	 *
	 * @param TiffDirectory directory The parsed directory.
	 * @param ByteArray bytes         The complete file.
	 * @param List<TiffUnit> units    The image's decode units.
	 * @param Int compression         The TIFF compression code.
	 * @param Int predictor           The TIFF predictor code.
	 * @param Int photometric         The photometric interpretation.
	 * @param Boolean littleEndian    The TIFF byte order.
	 * @return TiffCoding The coding parameters.
	 */
	private fun buildCoding(
		directory: TiffDirectory,
		bytes: ByteArray,
		units: List<TiffUnit>,
		compression: Int,
		predictor: Int,
		photometric: Int,
		littleEndian: Boolean,
	): TiffCoding {
		val ccittOptions =
			when (compression) {
				TiffConstants.COMPRESSION_CCITT_T4 -> directory.longs(TiffConstants.TAG_GROUP3OPTIONS)?.firstOrNull() ?: 0L
				TiffConstants.COMPRESSION_CCITT_T6 -> directory.longs(TiffConstants.TAG_GROUP4OPTIONS)?.firstOrNull() ?: 0L
				else -> 0L
			}
		// Some encoders tag a Modified Huffman RLE stream as T.4; probe the first unit once per image.
		val ccittType =
			if (compression == TiffConstants.COMPRESSION_CCITT_T4 && units.isNotEmpty()) {
				val firstUnit = units.first()
				val start = firstUnit.offset.coerceIn(0, bytes.size)
				val available = if (firstUnit.byteCount > 0) minOf(firstUnit.byteCount, bytes.size - start) else bytes.size - start
				findCcittCompressionType(bytes.copyOfRange(start, start + maxOf(available, 0)), compression)
			} else {
				compression
			}

		// TIFF's photometric interpretation is authoritative for JPEG-in-TIFF, overriding the JPEG's
		// own JFIF/Adobe color-space signalling (TIFF Technical Note 2).
		val jpegColorTransform =
			when {
				compression != TiffConstants.COMPRESSION_JPEG -> null
				photometric == TiffConstants.PHOTOMETRIC_YCBCR -> JpegColorTransform.YCBCR
				photometric == TiffConstants.PHOTOMETRIC_RGB -> JpegColorTransform.NONE
				else -> null
			}

		return TiffCoding(
			compression = compression,
			predictor = predictor,
			littleEndian = littleEndian,
			ccittType = ccittType,
			ccittOptions = ccittOptions,
			// Follows the probed type, not the tag.  TIFF 6.0 starts every Modified Huffman RLE row on
			// a byte boundary, and that is as true of a stream the probe above unmasked as of one
			// honestly tagged compression 2 - keying this off `compression` instead would decode the
			// same bytes two different ways depending only on how the encoder labelled them.
			ccittByteAligned = ccittType == TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE,
			jpegTables = if (compression == TiffConstants.COMPRESSION_JPEG) directory.bytes(TiffConstants.TAG_JPEG_TABLES) else null,
			jpegColorTransform = jpegColorTransform,
		)
	}

	/** A decode unit: one strip or one tile, with its stored dimensions and in-image placement. */
	private class TiffUnit(
		val offset: Int,
		val byteCount: Int,
		val imageX: Int,
		val imageY: Int,
		val unitWidth: Int,
		val unitHeight: Int,
		val copyWidth: Int,
		val copyHeight: Int,
		val plane: Int,
	)

	/**
	 * Builds the list of strips or tiles for the image, unifying strips as full-width tiles.
	 *
	 * @param TiffDirectory directory The parsed directory.
	 * @param Int width               Image width.
	 * @param Int height              Image height.
	 * @param Int samplesPerPixel     Samples per pixel.
	 * @param Int planeCount          1 for chunky, samplesPerPixel for planar.
	 * @return List<TiffUnit> The decode units in tag order (matching offsets/byteCounts).
	 */
	private fun buildUnits(directory: TiffDirectory, width: Int, height: Int, samplesPerPixel: Int, planeCount: Int): List<TiffUnit> {
		val units = ArrayList<TiffUnit>()
		if (directory.has(TiffConstants.TAG_TILE_WIDTH)) {
			val tileWidth = directory.int(TiffConstants.TAG_TILE_WIDTH, width)
			val tileHeight = directory.int(TiffConstants.TAG_TILE_HEIGHT, height)
			require(tileWidth > 0 && tileHeight > 0) { "Invalid TIFF tile dimensions" }
			val offsets = directory.longs(TiffConstants.TAG_TILE_OFFSETS) ?: throw IllegalArgumentException("TIFF tiles without offsets")
			val byteCounts = directory.longs(TiffConstants.TAG_TILE_BYTE_COUNTS) ?: throw IllegalArgumentException("TIFF tiles without byte counts")
			val tilesAcross = (width + tileWidth - 1) / tileWidth
			val tilesDown = (height + tileHeight - 1) / tileHeight
			val tilesPerPlane = tilesAcross * tilesDown
			for (plane in 0 until planeCount) {
				for (tileY in 0 until tilesDown) {
					for (tileX in 0 until tilesAcross) {
						val unitIndex = plane * tilesPerPlane + tileY * tilesAcross + tileX
						if (unitIndex >= offsets.size) {
							continue
						}
						val imageX = tileX * tileWidth
						val imageY = tileY * tileHeight
						units +=
							TiffUnit(
								offset = offsets[unitIndex].toInt(),
								byteCount = byteCounts.getOrElse(unitIndex) { 0L }.toInt(),
								imageX = imageX,
								imageY = imageY,
								unitWidth = tileWidth,
								unitHeight = tileHeight,
								copyWidth = minOf(tileWidth, width - imageX),
								copyHeight = minOf(tileHeight, height - imageY),
								plane = plane,
							)
					}
				}
			}
		} else {
			val rowsPerStrip = directory.int(TiffConstants.TAG_ROWS_PER_STRIP, height).let { if (it <= 0) height else minOf(it, height) }
			val offsets = directory.longs(TiffConstants.TAG_STRIP_OFFSETS) ?: throw IllegalArgumentException("TIFF without strip offsets")
			val byteCounts = directory.longs(TiffConstants.TAG_STRIP_BYTE_COUNTS)
			val stripsPerPlane = (height + rowsPerStrip - 1) / rowsPerStrip
			for (plane in 0 until planeCount) {
				for (stripY in 0 until stripsPerPlane) {
					val unitIndex = plane * stripsPerPlane + stripY
					if (unitIndex >= offsets.size) {
						continue
					}
					val imageY = stripY * rowsPerStrip
					val copyHeight = minOf(rowsPerStrip, height - imageY)
					units +=
						TiffUnit(
							offset = offsets[unitIndex].toInt(),
							byteCount = byteCounts?.getOrElse(unitIndex) { 0L }?.toInt() ?: 0,
							imageX = 0,
							imageY = imageY,
							unitWidth = width,
							unitHeight = copyHeight,
							copyWidth = width,
							copyHeight = copyHeight,
							plane = plane,
						)
				}
			}
		}
		return units
	}

	/**
	 * Decompresses one unit, un-applies the predictor, and scatters its samples into [samples].
	 *
	 * @param ByteArray bytes         The complete file.
	 * @param TiffUnit unit           The strip/tile to decode.
	 * @param Int width               Image width.
	 * @param Int samplesInPlane      Samples per pixel within a unit (1 for planar).
	 * @param Int samplesPerPixel     Samples per pixel in the assembled image.
	 * @param Int bitsPerSample       Bits per sample.
	 * @param TiffCoding coding       The image's compression parameters.
	 * @param IntArray samples        The destination raw-sample buffer (width*height*samplesPerPixel).
	 */
	private fun decodeUnit(
		bytes: ByteArray,
		unit: TiffUnit,
		width: Int,
		samplesInPlane: Int,
		samplesPerPixel: Int,
		bitsPerSample: Int,
		coding: TiffCoding,
		samples: IntArray,
	) {
		val rowBytes = (unit.unitWidth * samplesInPlane * bitsPerSample + 7) / 8
		val expectedSize = rowBytes * unit.unitHeight
		val decompressed = decompressUnit(bytes, unit, expectedSize, samplesInPlane, coding)

		for (row in 0 until unit.copyHeight) {
			val rowBase = row * rowBytes
			if (coding.predictor == TiffConstants.PREDICTOR_HORIZONTAL_DIFFERENCING) {
				applyHorizontalPredictor(decompressed, rowBase, unit.unitWidth, samplesInPlane, bitsPerSample, coding.littleEndian)
			}
			val imageY = unit.imageY + row
			for (column in 0 until unit.copyWidth) {
				val imageX = unit.imageX + column
				val pixelBase = (imageY * width + imageX) * samplesPerPixel
				for (sampleWithinPlane in 0 until samplesInPlane) {
					val linearSampleIndex = column * samplesInPlane + sampleWithinPlane
					val channel = if (samplesInPlane == 1) unit.plane else sampleWithinPlane
					samples[pixelBase + channel] = readSample(decompressed, rowBase, linearSampleIndex, bitsPerSample, coding.littleEndian)
				}
			}
		}
	}

	/**
	 * Reads one raw sample from a decompressed row, in the native bit-depth range.
	 *
	 * @param ByteArray row            The decompressed buffer.
	 * @param Int rowBase              Byte offset of the row.
	 * @param Int linearSampleIndex    The sample's index within the row (pixel*samplesInPlane + sample).
	 * @param Int bitsPerSample        Bits per sample.
	 * @param Boolean littleEndian     The TIFF byte order (for 16-bit).
	 * @return Int The raw sample value.
	 */
	private fun readSample(row: ByteArray, rowBase: Int, linearSampleIndex: Int, bitsPerSample: Int, littleEndian: Boolean): Int {
		return when (bitsPerSample) {
			8 -> {
				val offset = rowBase + linearSampleIndex
				if (offset < row.size) row[offset].toInt() and 0xFF else 0
			}

			16 -> {
				val offset = rowBase + linearSampleIndex * 2
				if (offset + 1 < row.size) {
					val low = row[offset].toInt() and 0xFF
					val high = row[offset + 1].toInt() and 0xFF
					if (littleEndian) low or (high shl 8) else (low shl 8) or high
				} else {
					0
				}
			}

			else -> {
				val bitIndex = linearSampleIndex * bitsPerSample
				val byteIndex = rowBase + (bitIndex ushr 3)
				if (byteIndex >= row.size) {
					return 0
				}
				val shift = 8 - bitsPerSample - (bitIndex and 7)
				val mask = (1 shl bitsPerSample) - 1
				(row[byteIndex].toInt() ushr shift) and mask
			}
		}
	}

	/**
	 * Decompresses one strip/tile into [expectedSize] bytes of packed samples.
	 *
	 * @param ByteArray bytes     The complete file.
	 * @param TiffUnit unit       The strip/tile to decompress.
	 * @param Int expectedSize    Decompressed byte count.
	 * @param Int samplesInPlane  Samples per pixel within the unit (1 for planar).
	 * @param TiffCoding coding   The image's compression parameters.
	 * @return ByteArray The decompressed bytes.
	 */
	private fun decompressUnit(bytes: ByteArray, unit: TiffUnit, expectedSize: Int, samplesInPlane: Int, coding: TiffCoding): ByteArray {
		val safeOffset = unit.offset.coerceIn(0, bytes.size)
		val available = if (unit.byteCount > 0) minOf(unit.byteCount, bytes.size - safeOffset) else bytes.size - safeOffset
		return when (coding.compression) {
			TiffConstants.COMPRESSION_NONE -> {
				val out = ByteArray(expectedSize)
				val copy = minOf(available, expectedSize)
				if (copy > 0) {
					bytes.copyInto(out, 0, safeOffset, safeOffset + copy)
				}
				out
			}

			TiffConstants.COMPRESSION_PACKBITS -> decodePackBits(bytes.copyOfRange(safeOffset, safeOffset + available), expectedSize)
			TiffConstants.COMPRESSION_LZW -> decodeLzw(bytes.copyOfRange(safeOffset, safeOffset + available), expectedSize)
			TiffConstants.COMPRESSION_ZLIB, TiffConstants.COMPRESSION_DEFLATE -> inflate(bytes, safeOffset, available, expectedSize)

			TiffConstants.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE,
			TiffConstants.COMPRESSION_CCITT_T4,
			TiffConstants.COMPRESSION_CCITT_T6,
			->
				decodeCcitt(
					bytes.copyOfRange(safeOffset, safeOffset + available),
					unit.unitWidth,
					unit.unitHeight,
					coding.ccittType,
					coding.ccittOptions,
					coding.ccittByteAligned,
				)

			TiffConstants.COMPRESSION_JPEG ->
				decodeJpegUnit(bytes.copyOfRange(safeOffset, safeOffset + available), unit, samplesInPlane, expectedSize, coding)

			else -> throw IllegalArgumentException("Unsupported TIFF compression ${coding.compression}")
		}
	}

	/**
	 * Decodes one JPEG-compressed strip/tile to interleaved 8-bit samples.
	 *
	 * TIFF Technical Note 2 ("new-style" JPEG, compression 7) stores each strip as its own abbreviated
	 * JPEG datastream, with the shared quantization and Huffman tables factored out into the JPEGTables
	 * tag; the two are spliced back together before decoding.
	 *
	 * @param ByteArray unitBytes The strip/tile's JPEG datastream.
	 * @param TiffUnit unit       The strip/tile being decoded.
	 * @param Int samplesInPlane  Samples per pixel expected within the unit.
	 * @param Int expectedSize    The decompressed byte count the caller expects.
	 * @param TiffCoding coding   The image's compression parameters.
	 * @return ByteArray The interleaved samples, padded to [expectedSize].
	 */
	private fun decodeJpegUnit(unitBytes: ByteArray, unit: TiffUnit, samplesInPlane: Int, expectedSize: Int, coding: TiffCoding): ByteArray {
		val datastream = spliceJpegTables(coding.jpegTables, unitBytes)
		val decoded = decodeJpeg(datastream, coding.jpegColorTransform)
		require(decoded.componentCount == samplesInPlane) {
			"JPEG-in-TIFF strip has ${decoded.componentCount} components but the TIFF declares $samplesInPlane"
		}

		val out = ByteArray(expectedSize)
		val rowBytes = unit.unitWidth * samplesInPlane
		val copyRows = minOf(unit.unitHeight, decoded.height)
		val copyBytes = minOf(rowBytes, decoded.width * samplesInPlane)
		for (row in 0 until copyRows) {
			decoded.samples.copyInto(out, row * rowBytes, row * decoded.width * samplesInPlane, row * decoded.width * samplesInPlane + copyBytes)
		}
		return out
	}

	/**
	 * Splices an abbreviated JPEGTables stream onto a strip's datastream: the tables' trailing EOI and
	 * the strip's leading SOI are dropped so the result is one well-formed datastream.
	 *
	 * @param ByteArray? tables   The JPEGTables tag contents, or null when the strips are self-contained.
	 * @param ByteArray unitBytes The strip's datastream.
	 * @return ByteArray The complete JPEG datastream to decode.
	 */
	private fun spliceJpegTables(tables: ByteArray?, unitBytes: ByteArray): ByteArray {
		if (tables == null || tables.size < 4) {
			return unitBytes
		}
		// Drop the tables' EOI marker, keeping SOI + DQT/DHT segments.
		var tablesEnd = tables.size
		if (tables[tablesEnd - 2] == 0xFF.toByte() && tables[tablesEnd - 1] == 0xD9.toByte()) {
			tablesEnd -= 2
		}
		// Drop the strip's SOI marker, which the tables stream already supplied.
		var unitStart = 0
		if (unitBytes.size >= 2 && unitBytes[0] == 0xFF.toByte() && unitBytes[1] == 0xD8.toByte()) {
			unitStart = 2
		}
		val spliced = ByteArray(tablesEnd + (unitBytes.size - unitStart))
		tables.copyInto(spliced, 0, 0, tablesEnd)
		unitBytes.copyInto(spliced, tablesEnd, unitStart, unitBytes.size)
		return spliced
	}

	/**
	 * Inflates a zlib stream at [offset] into exactly [expectedSize] bytes (TIFF Deflate/ZIP are plain
	 * zlib).  The strip's uncompressed size is known, so it bounds the inflate and any shortfall from a
	 * truncated stream is left zero-filled.
	 *
	 * @param ByteArray bytes    The buffer holding the stream.
	 * @param Int offset         Offset of the stream.
	 * @param Int length         Compressed byte count.
	 * @param Int expectedSize   Decompressed byte count.
	 * @return ByteArray The inflated bytes (zero-padded if the stream ends early).
	 */
	private fun inflate(bytes: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
		val inflated = inflateZlib(bytes, offset, length, expectedSize)
		if (inflated.size == expectedSize) {
			return inflated
		}
		val out = ByteArray(expectedSize)
		inflated.copyInto(out, 0, 0, minOf(inflated.size, expectedSize))
		return out
	}

	/**
	 * Maps the raw sample buffer to straight-alpha RGBA8888, applying photometric interpretation,
	 * palette lookup, 16-to-8 down-conversion, and ExtraSamples alpha (un-premultiplying associated
	 * alpha).
	 *
	 * @param IntArray samples       The raw per-sample values.
	 * @param Int width              Image width.
	 * @param Int height             Image height.
	 * @param Int samplesPerPixel    Samples per pixel.
	 * @param Int bitsPerSample      Bits per sample.
	 * @param Int photometric        The photometric interpretation.
	 * @param IntArray? extraSamples The ExtraSamples values, or null.
	 * @param LongArray? colorMap    The palette (3 planes of 16-bit values), or null.
	 * @return ByteArray The RGBA8888 pixels.
	 */
	private fun assembleRgba(
		samples: IntArray,
		width: Int,
		height: Int,
		samplesPerPixel: Int,
		bitsPerSample: Int,
		photometric: Int,
		extraSamples: IntArray?,
		colorMap: LongArray?,
	): ByteArray {
		val rgba = ByteArray(width * height * 4)
		val maxValue = if (bitsPerSample >= 16) 65535 else (1 shl bitsPerSample) - 1
		val associatedAlpha = extraSamples != null && extraSamples.isNotEmpty() && extraSamples[0] == TiffConstants.EXTRASAMPLE_ASSOCIATED_ALPHA

		val opaqueSamples = if (photometric == TiffConstants.PHOTOMETRIC_RGB) 3 else 1
		val hasAlpha = samplesPerPixel > opaqueSamples

		val paletteSize = 1 shl bitsPerSample
		val paletteAllLow = colorMap != null && colorMap.all { it < 256 }

		for (pixelIndex in 0 until width * height) {
			val sampleBase = pixelIndex * samplesPerPixel
			val destination = pixelIndex * 4
			var red: Int
			var green: Int
			var blue: Int
			when (photometric) {
				TiffConstants.PHOTOMETRIC_PALETTE -> {
					val index = samples[sampleBase]
					if (colorMap != null && index < paletteSize && 2 * paletteSize + index < colorMap.size) {
						red = paletteComponent(colorMap[index], paletteAllLow)
						green = paletteComponent(colorMap[paletteSize + index], paletteAllLow)
						blue = paletteComponent(colorMap[2 * paletteSize + index], paletteAllLow)
					} else {
						red = 0
						green = 0
						blue = 0
					}
				}

				TiffConstants.PHOTOMETRIC_RGB -> {
					red = scaleToByte(samples[sampleBase], bitsPerSample, maxValue)
					green = scaleToByte(samples[sampleBase + 1], bitsPerSample, maxValue)
					blue = scaleToByte(samples[sampleBase + 2], bitsPerSample, maxValue)
				}

				else -> {
					var gray = scaleToByte(samples[sampleBase], bitsPerSample, maxValue)
					if (photometric == TiffConstants.PHOTOMETRIC_WHITE_IS_ZERO) {
						gray = 255 - gray
					}
					red = gray
					green = gray
					blue = gray
				}
			}

			var alpha = 255
			if (hasAlpha) {
				alpha = scaleToByte(samples[sampleBase + opaqueSamples], bitsPerSample, maxValue)
				if (associatedAlpha) {
					red = unpremultiply(red, alpha)
					green = unpremultiply(green, alpha)
					blue = unpremultiply(blue, alpha)
				}
			}

			rgba[destination] = red.toByte()
			rgba[destination + 1] = green.toByte()
			rgba[destination + 2] = blue.toByte()
			rgba[destination + 3] = alpha.toByte()
		}
		return rgba
	}

	/**
	 * Scales a raw sample in [0, maxValue] to 8 bits (16-bit keeps the high byte; sub-8-bit scales up).
	 *
	 * @param Int sample        The raw sample value.
	 * @param Int bitsPerSample Bits per sample.
	 * @param Int maxValue      The maximum raw value for this depth.
	 * @return Int The 8-bit value.
	 */
	private fun scaleToByte(sample: Int, bitsPerSample: Int, maxValue: Int): Int =
		when {
			bitsPerSample >= 16 -> (sample ushr 8) and 0xFF
			bitsPerSample == 8 -> sample and 0xFF
			else -> (sample * 255 + maxValue / 2) / maxValue
		}

	/**
	 * Converts one 16-bit ColorMap entry to an 8-bit component: normally value/256, but if the whole
	 * map fits in the low byte (a common exporter bug) the raw value is used.
	 *
	 * @param Long value    The 16-bit palette component.
	 * @param Boolean allLow Whether every palette value is below 256.
	 * @return Int The 8-bit component.
	 */
	private fun paletteComponent(value: Long, allLow: Boolean): Int = if (allLow) (value and 0xFF).toInt() else ((value ushr 8) and 0xFF).toInt()

	/**
	 * Un-premultiplies an associated-alpha component: c = round(c * 255 / alpha), 0 when alpha is 0.
	 *
	 * @param Int component The premultiplied 8-bit component.
	 * @param Int alpha     The 8-bit alpha.
	 * @return Int The straight-alpha component (0..255).
	 */
	private fun unpremultiply(component: Int, alpha: Int): Int = if (alpha == 0) 0 else minOf(255, (component * 255 + alpha / 2) / alpha)
}