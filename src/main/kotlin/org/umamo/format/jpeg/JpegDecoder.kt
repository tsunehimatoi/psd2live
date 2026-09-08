// JPEG decoder: marker/segment parsing (T.81 Annex B), the sequential and progressive Huffman scan
// decoders (T.81 Annex F and G), chroma upsampling, and color conversion.  The upsampling filters and
// the YCbCr->RGB fixed-point constants follow the Independent JPEG Group's jdsample.c / jdcolor.c, and
// the progressive scan decoders follow jdphuff.c, so that output is byte-identical to libjpeg (and
// therefore to libjpeg-turbo, ImageIO, and PIL).

package org.umamo.format.jpeg

/**
 * A decoded JPEG image: 8-bit samples, interleaved, top row first.
 *
 * @property width          Image width in pixels.
 * @property height         Image height in pixels.
 * @property componentCount 1 for grayscale, 3 for RGB (color conversion is already applied).
 * @property samples        width * height * componentCount interleaved samples.
 */
internal class JpegImage(
	val width: Int,
	val height: Int,
	val componentCount: Int,
	val samples: ByteArray,
)

/** Color transform codes, matching the Adobe APP14 transform byte. */
internal object JpegColorTransform {
	const val NONE = 0 // Components are already RGB (or grayscale).
	const val YCBCR = 1 // Components are YCbCr and need the inverse transform.
}

/**
 * One frame component (T.81 B.2.2): its sampling factors, quantization table, coefficient buffer, and
 * decoded plane.
 *
 * Coefficients are held for the whole component rather than transformed block-by-block, because a
 * progressive image refines the same coefficients across many scans and can only be transformed once
 * every scan has been read.  They are stored raw (still quantized), in natural order, exactly as
 * libjpeg's JCOEF blocks are.
 */
private class JpegComponent(
	val identifier: Int,
	val horizontalSamplingFactor: Int,
	val verticalSamplingFactor: Int,
	val quantizationTableIndex: Int,
) {
	var coefficients: ShortArray = ShortArray(0)
	var plane: ByteArray = ByteArray(0)
	var planeStride = 0

	// The component's true sample dimensions, before padding out to whole MCUs.
	var downsampledWidth = 0
	var downsampledHeight = 0

	// Blocks covering the padded MCU grid; this is the coefficient buffer's shape.
	var blocksPerLine = 0
	var blocksPerColumn = 0

	var dcTableIndex = 0
	var acTableIndex = 0
	var dcPredictor = 0
}

/**
 * Decodes a complete JPEG datastream.
 *
 * @param ByteArray bytes           The JPEG datastream, starting at SOI.
 * @param Int? forcedColorTransform JpegColorTransform.NONE / .YCBCR to override the stream's own
 *                                  color-space signalling (TIFF's PhotometricInterpretation is
 *                                  authoritative for JPEG-in-TIFF), or null to auto-detect.
 * @return JpegImage The decoded image.
 */
internal fun decodeJpeg(bytes: ByteArray, forcedColorTransform: Int? = null): JpegImage = JpegDecoder(bytes, forcedColorTransform).decode()

/**
 * The JPEG decoder: baseline / extended sequential Huffman (SOF0 / SOF1) and progressive Huffman
 * (SOF2).
 *
 * Lossless, arithmetic-coded, differential, and 12-bit frames are rejected with a clear message
 * rather than decoded incorrectly.
 */
private class JpegDecoder(private val bytes: ByteArray, private val forcedColorTransform: Int?) {
	private val quantizationTables = arrayOfNulls<IntArray>(4)
	private val dcHuffmanTables = arrayOfNulls<JpegHuffmanTable>(4)
	private val acHuffmanTables = arrayOfNulls<JpegHuffmanTable>(4)

	private var components: List<JpegComponent> = emptyList()
	private var frameWidth = 0
	private var frameHeight = 0
	private var maxHorizontalSamplingFactor = 1
	private var maxVerticalSamplingFactor = 1
	private var mcusPerLine = 0
	private var mcusPerColumn = 0
	private var restartInterval = 0
	private var frameSeen = false
	private var progressive = false

	private var adobeTransform: Int? = null
	private var jfifSeen = false

	// Current scan parameters (T.81 B.2.3): the spectral band and successive-approximation bit positions.
	private var spectralStart = 0
	private var spectralEnd = 63
	private var approximationHigh = 0
	private var approximationLow = 0

	// Progressive end-of-band run: how many further blocks are entirely band-complete.
	private var endOfBandRun = 0

	private val idctBlock = IntArray(JpegConstants.BLOCK_COEFFICIENTS)
	private val idctWorkspace = IntArray(JpegConstants.BLOCK_COEFFICIENTS)

	/**
	 * Parses the datastream and decodes its single frame.
	 *
	 * @return JpegImage The decoded image.
	 */
	fun decode(): JpegImage {
		var position = 0
		require(bytes.size >= 2 && readMarkerAt(position) == JpegConstants.MARKER_SOI) { "Not a JPEG datastream (missing SOI)" }
		position += 2

		while (position + 1 < bytes.size) {
			if ((bytes[position].toInt() and 0xFF) != 0xFF) {
				// Resynchronize: stray bytes between segments are not legal but do occur.
				position++
				continue
			}
			val marker = bytes[position + 1].toInt() and 0xFF
			if (marker == 0xFF || marker == 0x00) {
				position++
				continue
			}
			position += 2

			when {
				marker == JpegConstants.MARKER_EOI -> {
					return assembleImage()
				}

				marker == JpegConstants.MARKER_SOI -> {
					// Nested SOI: nothing to do.
				}

				marker in JpegConstants.MARKER_RST0..JpegConstants.MARKER_RST7 -> {
					// A stray restart marker outside a scan; skip it.
				}

				marker == 0x01 -> {
					// TEM: standalone, no payload.
				}

				else -> {
					require(position + 1 < bytes.size) { "Truncated JPEG segment header" }
					val segmentLength = readUnsigned16(position)
					require(segmentLength >= 2) { "Invalid JPEG segment length $segmentLength" }
					val payloadStart = position + 2
					val payloadEnd = minOf(position + segmentLength, bytes.size)

					when {
						marker == JpegConstants.MARKER_DQT -> readQuantizationTables(payloadStart, payloadEnd)
						marker == JpegConstants.MARKER_DHT -> readHuffmanTables(payloadStart, payloadEnd)
						marker == JpegConstants.MARKER_DRI -> restartInterval = readUnsigned16(payloadStart)
						marker == JpegConstants.MARKER_APP0 -> readJfif(payloadStart, payloadEnd)
						marker == JpegConstants.MARKER_APP14 -> readAdobe(payloadStart, payloadEnd)
						marker == JpegConstants.MARKER_DAC -> throw IllegalArgumentException("Unsupported JPEG: arithmetic-coded JPEG")
						JpegConstants.isStartOfFrame(marker) -> readFrame(marker, payloadStart, payloadEnd)

						marker == JpegConstants.MARKER_SOS -> {
							position = decodeScan(payloadStart, payloadEnd)
							continue
						}

						else -> {
							// APPn / COM / DNL and other segments carry no data this decoder needs.
						}
					}
					position += segmentLength
					continue
				}
			}
		}

		return assembleImage()
	}

	/**
	 * Reads the 0xFFxx marker at [position].
	 *
	 * @param Int position Offset of the marker's 0xFF byte.
	 * @return Int The marker code, or -1 when the bytes are not a marker.
	 */
	private fun readMarkerAt(position: Int): Int {
		if (position + 1 >= bytes.size || (bytes[position].toInt() and 0xFF) != 0xFF) {
			return -1
		}
		return bytes[position + 1].toInt() and 0xFF
	}

	/**
	 * Reads a big-endian unsigned 16-bit value (all JPEG multi-byte fields are big-endian).
	 *
	 * @param Int position The offset to read from.
	 * @return Int The value.
	 */
	private fun readUnsigned16(position: Int): Int {
		require(position + 1 < bytes.size) { "Truncated JPEG data" }
		return ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
	}

	/**
	 * Reads one or more quantization tables from a DQT segment (T.81 B.2.4.1).
	 *
	 * @param Int start The payload start offset.
	 * @param Int end   The payload end offset (exclusive).
	 */
	private fun readQuantizationTables(start: Int, end: Int) {
		var position = start
		while (position < end) {
			val precisionAndIndex = bytes[position].toInt() and 0xFF
			position++
			val precision = precisionAndIndex shr 4
			val tableIndex = precisionAndIndex and 0x0F
			require(tableIndex < 4) { "Invalid JPEG quantization table index $tableIndex" }
			require(precision == 0 || precision == 1) { "Invalid JPEG quantization table precision $precision" }

			// DQT values arrive in zig-zag order; store them in natural order for the IDCT.
			val table = IntArray(JpegConstants.BLOCK_COEFFICIENTS)
			for (zigZagIndex in 0 until JpegConstants.BLOCK_COEFFICIENTS) {
				val value =
					if (precision == 0) {
						bytes[position].toInt() and 0xFF
					} else {
						readUnsigned16(position)
					}
				position += if (precision == 0) 1 else 2
				table[JpegConstants.NATURAL_ORDER[zigZagIndex]] = value
			}
			quantizationTables[tableIndex] = table
		}
	}

	/**
	 * Reads one or more Huffman tables from a DHT segment (T.81 B.2.4.2).
	 *
	 * @param Int start The payload start offset.
	 * @param Int end   The payload end offset (exclusive).
	 */
	private fun readHuffmanTables(start: Int, end: Int) {
		var position = start
		while (position < end) {
			val classAndIndex = bytes[position].toInt() and 0xFF
			position++
			val tableClass = classAndIndex shr 4
			val tableIndex = classAndIndex and 0x0F
			require(tableIndex < 4) { "Invalid JPEG Huffman table index $tableIndex" }
			require(tableClass == 0 || tableClass == 1) { "Invalid JPEG Huffman table class $tableClass" }

			val codeCountsPerLength = IntArray(16)
			var totalCodes = 0
			for (lengthIndex in 0 until 16) {
				codeCountsPerLength[lengthIndex] = bytes[position + lengthIndex].toInt() and 0xFF
				totalCodes += codeCountsPerLength[lengthIndex]
			}
			position += 16

			require(position + totalCodes <= end) { "Truncated JPEG Huffman table" }
			val values = IntArray(totalCodes)
			for (valueIndex in 0 until totalCodes) {
				values[valueIndex] = bytes[position + valueIndex].toInt() and 0xFF
			}
			position += totalCodes

			val table = JpegHuffmanTable(codeCountsPerLength, values)
			if (tableClass == 0) {
				dcHuffmanTables[tableIndex] = table
			} else {
				acHuffmanTables[tableIndex] = table
			}
		}
	}

	/**
	 * Records whether an APP0 segment is a JFIF header (which implies YCbCr for 3-component frames).
	 *
	 * @param Int start The payload start offset.
	 * @param Int end   The payload end offset (exclusive).
	 */
	private fun readJfif(start: Int, end: Int) {
		if (end - start < 5) {
			return
		}
		// APP0 identifier "JFIF\0".
		if (bytes[start].toInt() == 0x4A &&
			bytes[start + 1].toInt() == 0x46 &&
			bytes[start + 2].toInt() == 0x49 &&
			bytes[start + 3].toInt() == 0x46 &&
			bytes[start + 4].toInt() == 0x00
		) {
			jfifSeen = true
		}
	}

	/**
	 * Reads the Adobe APP14 color-transform byte, which authoritatively names the frame's color space.
	 *
	 * @param Int start The payload start offset.
	 * @param Int end   The payload end offset (exclusive).
	 */
	private fun readAdobe(start: Int, end: Int) {
		if (end - start < 12) {
			return
		}
		// APP14 identifier "Adobe"; the transform byte is the last of the 12-byte payload.
		if (bytes[start].toInt() == 0x41 &&
			bytes[start + 1].toInt() == 0x64 &&
			bytes[start + 2].toInt() == 0x6F &&
			bytes[start + 3].toInt() == 0x62 &&
			bytes[start + 4].toInt() == 0x65
		) {
			adobeTransform = bytes[start + 11].toInt() and 0xFF
		}
	}

	/**
	 * Reads a Start Of Frame segment and allocates each component's coefficient buffer (T.81 B.2.2).
	 *
	 * @param Int marker The SOFn marker code.
	 * @param Int start  The payload start offset.
	 * @param Int end    The payload end offset (exclusive).
	 */
	private fun readFrame(marker: Int, start: Int, end: Int) {
		require(marker == JpegConstants.MARKER_SOF0 || marker == JpegConstants.MARKER_SOF1 || marker == JpegConstants.MARKER_SOF2) {
			"Unsupported JPEG: ${JpegConstants.startOfFrameDescription(marker)}"
		}
		require(!frameSeen) { "Unsupported JPEG: multiple frames" }
		require(end - start >= 6) { "Truncated JPEG SOF segment" }
		progressive = marker == JpegConstants.MARKER_SOF2

		val samplePrecision = bytes[start].toInt() and 0xFF
		require(samplePrecision == 8) { "Unsupported JPEG sample precision $samplePrecision (only 8-bit is supported)" }
		frameHeight = readUnsigned16(start + 1)
		frameWidth = readUnsigned16(start + 3)
		val componentCount = bytes[start + 5].toInt() and 0xFF
		require(frameWidth > 0 && frameHeight > 0) { "JPEG frame has non-positive dimensions ${frameWidth}x$frameHeight" }
		require(componentCount == 1 || componentCount == 3) {
			"Unsupported JPEG component count $componentCount (only grayscale and 3-component color are supported)"
		}
		require(end - start >= 6 + componentCount * 3) { "Truncated JPEG SOF component list" }

		val parsed = ArrayList<JpegComponent>(componentCount)
		for (componentIndex in 0 until componentCount) {
			val fieldStart = start + 6 + componentIndex * 3
			val samplingFactors = bytes[fieldStart + 1].toInt() and 0xFF
			val horizontal = samplingFactors shr 4
			val vertical = samplingFactors and 0x0F
			require(horizontal in 1..4 && vertical in 1..4) { "Invalid JPEG sampling factors ${horizontal}x$vertical" }
			// Tq selects one of the four DQT slots; a larger value would index past quantizationTables
			// in renderComponent, so reject it here where the message can still name the field.
			val quantizationTableIndex = bytes[fieldStart + 2].toInt() and 0xFF
			require(quantizationTableIndex < 4) { "Invalid JPEG quantization table index $quantizationTableIndex" }
			parsed +=
				JpegComponent(
					identifier = bytes[fieldStart].toInt() and 0xFF,
					horizontalSamplingFactor = horizontal,
					verticalSamplingFactor = vertical,
					quantizationTableIndex = quantizationTableIndex,
				)
		}
		components = parsed
		frameSeen = true

		maxHorizontalSamplingFactor = parsed.maxOf { it.horizontalSamplingFactor }
		maxVerticalSamplingFactor = parsed.maxOf { it.verticalSamplingFactor }
		// Every component must upsample by a whole number, which T.81 permits to be false (Hi/Vi are
		// independent 1..4 fields) but no real encoder emits.  libjpeg rejects these outright
		// ("Fractional sampling not implemented yet", jdsample.c), and matching libjpeg's output is
		// the whole point of this decoder, so decline them rather than invent a fractional filter
		// whose results nothing else would agree with.
		for (component in parsed) {
			require(
				maxHorizontalSamplingFactor % component.horizontalSamplingFactor == 0 &&
					maxVerticalSamplingFactor % component.verticalSamplingFactor == 0,
			) {
				"Unsupported JPEG: fractional sampling (component ${component.identifier} is " +
					"${component.horizontalSamplingFactor}x${component.verticalSamplingFactor} " +
					"against a maximum of ${maxHorizontalSamplingFactor}x$maxVerticalSamplingFactor)"
			}
		}
		mcusPerLine = ceilDivide(frameWidth, 8 * maxHorizontalSamplingFactor)
		mcusPerColumn = ceilDivide(frameHeight, 8 * maxVerticalSamplingFactor)

		for (component in components) {
			component.downsampledWidth = ceilDivide(frameWidth * component.horizontalSamplingFactor, maxHorizontalSamplingFactor)
			component.downsampledHeight = ceilDivide(frameHeight * component.verticalSamplingFactor, maxVerticalSamplingFactor)
			// Scans code whole MCUs, so the buffer is padded out to the MCU grid and cropped on render.
			component.blocksPerLine = mcusPerLine * component.horizontalSamplingFactor
			component.blocksPerColumn = mcusPerColumn * component.verticalSamplingFactor
			component.coefficients = ShortArray(component.blocksPerLine * component.blocksPerColumn * JpegConstants.BLOCK_COEFFICIENTS)
		}
	}

	/**
	 * Decodes one entropy-coded scan (T.81 B.2.3, Annex F for sequential and Annex G for progressive).
	 *
	 * @param Int start The SOS payload start offset.
	 * @param Int end   The SOS payload end offset (exclusive).
	 * @return Int The offset just past the scan's entropy-coded data.
	 */
	private fun decodeScan(start: Int, end: Int): Int {
		require(frameSeen) { "JPEG scan before frame header" }
		val scanComponentCount = bytes[start].toInt() and 0xFF
		require(scanComponentCount in 1..components.size) { "Invalid JPEG scan component count $scanComponentCount" }

		val scanComponents = ArrayList<JpegComponent>(scanComponentCount)
		for (scanIndex in 0 until scanComponentCount) {
			val fieldStart = start + 1 + scanIndex * 2
			val componentIdentifier = bytes[fieldStart].toInt() and 0xFF
			val component =
				components.firstOrNull { it.identifier == componentIdentifier }
					?: throw IllegalArgumentException("JPEG scan names unknown component $componentIdentifier")
			// Td/Ta are 4-bit fields but only four tables of each class exist; validate before they
			// reach dcHuffmanTables/acHuffmanTables, which are sized 4 and would throw an index error
			// over the "references undefined ... table" message dcTableFor/acTableFor exist to give.
			val tableSelectors = bytes[fieldStart + 1].toInt() and 0xFF
			val dcTableIndex = tableSelectors shr 4
			val acTableIndex = tableSelectors and 0x0F
			require(dcTableIndex < 4) { "Invalid JPEG DC Huffman table index $dcTableIndex" }
			require(acTableIndex < 4) { "Invalid JPEG AC Huffman table index $acTableIndex" }
			component.dcTableIndex = dcTableIndex
			component.acTableIndex = acTableIndex
			component.dcPredictor = 0
			scanComponents += component
		}

		spectralStart = bytes[start + 1 + scanComponentCount * 2].toInt() and 0xFF
		spectralEnd = bytes[start + 2 + scanComponentCount * 2].toInt() and 0xFF
		val approximation = bytes[start + 3 + scanComponentCount * 2].toInt() and 0xFF
		approximationHigh = approximation shr 4
		approximationLow = approximation and 0x0F

		if (progressive) {
			require(spectralStart <= spectralEnd && spectralEnd < JpegConstants.BLOCK_COEFFICIENTS) {
				"Invalid progressive JPEG spectral band $spectralStart..$spectralEnd"
			}
			if (spectralStart == 0) {
				require(spectralEnd == 0) { "A progressive JPEG DC scan must cover coefficient 0 alone (band $spectralStart..$spectralEnd)" }
			} else {
				// T.81 G.1.3.2: only DC scans may interleave components.
				require(scanComponentCount == 1) { "A progressive JPEG AC scan must name exactly one component" }
			}
		} else {
			require(spectralStart == 0 && spectralEnd == 63 && approximationHigh == 0 && approximationLow == 0) {
				"Unsupported JPEG: progressive scan parameters in a sequential frame"
			}
		}

		endOfBandRun = 0
		val reader = JpegBitReader(bytes, end)
		if (scanComponentCount == 1) {
			decodeNonInterleavedScan(reader, scanComponents[0])
		} else {
			decodeInterleavedScan(reader, scanComponents)
		}

		// Skip past any trailing bytes to the next marker.
		var position = reader.position
		while (position + 1 < bytes.size) {
			if ((bytes[position].toInt() and 0xFF) == 0xFF) {
				val marker = bytes[position + 1].toInt() and 0xFF
				if (marker != 0x00 && marker != 0xFF && marker !in JpegConstants.MARKER_RST0..JpegConstants.MARKER_RST7) {
					break
				}
			}
			position++
		}
		return position
	}

	/**
	 * Decodes an interleaved scan, walking the MCU grid.
	 *
	 * @param JpegBitReader reader               The entropy-coded bit source.
	 * @param List<JpegComponent> scanComponents The scan's components, in scan order.
	 */
	private fun decodeInterleavedScan(reader: JpegBitReader, scanComponents: List<JpegComponent>) {
		val totalMcus = mcusPerLine * mcusPerColumn
		var mcusSinceRestart = 0
		for (mcuIndex in 0 until totalMcus) {
			if (restartInterval > 0 && mcusSinceRestart == restartInterval) {
				if (!restart(reader, scanComponents)) {
					return
				}
				mcusSinceRestart = 0
			}
			val mcuRow = mcuIndex / mcusPerLine
			val mcuColumn = mcuIndex % mcusPerLine
			for (component in scanComponents) {
				for (blockY in 0 until component.verticalSamplingFactor) {
					for (blockX in 0 until component.horizontalSamplingFactor) {
						decodeBlock(
							reader,
							component,
							mcuRow * component.verticalSamplingFactor + blockY,
							mcuColumn * component.horizontalSamplingFactor + blockX,
						)
					}
				}
			}
			mcusSinceRestart++
		}
	}

	/**
	 * Decodes a single-component (non-interleaved) scan, walking that component's own block grid.
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The scan's only component.
	 */
	private fun decodeNonInterleavedScan(reader: JpegBitReader, component: JpegComponent) {
		// A non-interleaved scan codes only the component's real blocks, not the padded MCU grid.
		val blocksPerLine = ceilDivide(component.downsampledWidth, 8)
		val blocksPerColumn = ceilDivide(component.downsampledHeight, 8)
		val scanComponents = listOf(component)
		var blocksSinceRestart = 0
		for (blockRow in 0 until blocksPerColumn) {
			for (blockColumn in 0 until blocksPerLine) {
				if (restartInterval > 0 && blocksSinceRestart == restartInterval) {
					if (!restart(reader, scanComponents)) {
						return
					}
					blocksSinceRestart = 0
				}
				decodeBlock(reader, component, blockRow, blockColumn)
				blocksSinceRestart++
			}
		}
	}

	/**
	 * Consumes a restart marker and resets the per-interval entropy state (T.81 F.2.1.3.1 / G.1.2.3).
	 *
	 * @param JpegBitReader reader               The entropy-coded bit source.
	 * @param List<JpegComponent> scanComponents The scan's components.
	 * @return Boolean False when no restart marker was found and decoding must stop.
	 */
	private fun restart(reader: JpegBitReader, scanComponents: List<JpegComponent>): Boolean {
		if (!reader.restart()) {
			return false
		}
		for (component in scanComponents) {
			component.dcPredictor = 0
		}
		// A restart interval is independently decodable, so any pending end-of-band run ends with it.
		endOfBandRun = 0
		return true
	}

	/**
	 * Decodes one 8x8 block's contribution to the coefficient buffer, dispatching on the scan type.
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockRow            The block's row in the component's block grid.
	 * @param Int blockColumn         The block's column in the component's block grid.
	 */
	private fun decodeBlock(reader: JpegBitReader, component: JpegComponent, blockRow: Int, blockColumn: Int) {
		val blockOffset = (blockRow * component.blocksPerLine + blockColumn) * JpegConstants.BLOCK_COEFFICIENTS
		if (blockOffset < 0 || blockOffset + JpegConstants.BLOCK_COEFFICIENTS > component.coefficients.size) {
			return
		}
		when {
			!progressive -> decodeBaselineBlock(reader, component, blockOffset)
			spectralStart == 0 && approximationHigh == 0 -> decodeDcFirst(reader, component, blockOffset)
			spectralStart == 0 -> decodeDcRefine(reader, component, blockOffset)
			approximationHigh == 0 -> decodeAcFirst(reader, component, blockOffset)
			else -> decodeAcRefine(reader, component, blockOffset)
		}
	}

	/**
	 * Resolves the scan's DC Huffman table.
	 *
	 * @param JpegComponent component The component being decoded.
	 * @return JpegHuffmanTable The table.
	 */
	private fun dcTableFor(component: JpegComponent): JpegHuffmanTable =
		dcHuffmanTables[component.dcTableIndex]
			?: throw IllegalArgumentException("JPEG references undefined DC Huffman table ${component.dcTableIndex}")

	/**
	 * Resolves the scan's AC Huffman table.
	 *
	 * @param JpegComponent component The component being decoded.
	 * @return JpegHuffmanTable The table.
	 */
	private fun acTableFor(component: JpegComponent): JpegHuffmanTable =
		acHuffmanTables[component.acTableIndex]
			?: throw IllegalArgumentException("JPEG references undefined AC Huffman table ${component.acTableIndex}")

	/**
	 * Decodes one sequential block: the DC difference then the AC run/size pairs (T.81 F.2.2).
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockOffset         Index of the block's first coefficient.
	 */
	private fun decodeBaselineBlock(reader: JpegBitReader, component: JpegComponent, blockOffset: Int) {
		val dcTable = dcTableFor(component)
		val acTable = acTableFor(component)
		component.coefficients.fill(0, blockOffset, blockOffset + JpegConstants.BLOCK_COEFFICIENTS)

		// DC coefficient: a Huffman-coded magnitude category, then that many bits of difference.
		val dcCategory = dcTable.decode(reader)
		val dcDifference = if (dcCategory == 0) 0 else extendJpegValue(reader.readBits(dcCategory), dcCategory)
		component.dcPredictor += dcDifference
		component.coefficients[blockOffset] = component.dcPredictor.toShort()

		// AC coefficients: run-length of zeros in the high nibble, magnitude category in the low.
		var coefficientIndex = 1
		while (coefficientIndex < JpegConstants.BLOCK_COEFFICIENTS) {
			val runAndSize = acTable.decode(reader)
			val magnitudeCategory = runAndSize and 0x0F
			val zeroRunLength = runAndSize shr 4
			if (magnitudeCategory == 0) {
				if (zeroRunLength != 15) {
					// End Of Block: the rest of the block is zero.
					break
				}
				// ZRL: a run of 16 zeros.
				coefficientIndex += 16
				continue
			}
			coefficientIndex += zeroRunLength
			if (coefficientIndex >= JpegConstants.BLOCK_COEFFICIENTS) {
				break
			}
			val value = extendJpegValue(reader.readBits(magnitudeCategory), magnitudeCategory)
			component.coefficients[blockOffset + JpegConstants.NATURAL_ORDER[coefficientIndex]] = value.toShort()
			coefficientIndex++
		}
	}

	/**
	 * Decodes a progressive DC first scan: the DC difference, point-shifted left by Al (T.81 G.1.2.1).
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockOffset         Index of the block's first coefficient.
	 */
	private fun decodeDcFirst(reader: JpegBitReader, component: JpegComponent, blockOffset: Int) {
		val dcCategory = dcTableFor(component).decode(reader)
		val dcDifference = if (dcCategory == 0) 0 else extendJpegValue(reader.readBits(dcCategory), dcCategory)
		component.dcPredictor += dcDifference
		component.coefficients[blockOffset] = (component.dcPredictor shl approximationLow).toShort()
	}

	/**
	 * Decodes a progressive DC refinement scan: one bit per block, OR-ed in at Al (T.81 G.1.2.1).
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockOffset         Index of the block's first coefficient.
	 */
	private fun decodeDcRefine(reader: JpegBitReader, component: JpegComponent, blockOffset: Int) {
		if (reader.readBit() != 0) {
			component.coefficients[blockOffset] = (component.coefficients[blockOffset].toInt() or (1 shl approximationLow)).toShort()
		}
	}

	/**
	 * Decodes a progressive AC first scan over the band Ss..Se (T.81 G.1.2.2).
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockOffset         Index of the block's first coefficient.
	 */
	private fun decodeAcFirst(reader: JpegBitReader, component: JpegComponent, blockOffset: Int) {
		if (endOfBandRun > 0) {
			// This block's whole band is zero, accounted for by a previous EOBn code.
			endOfBandRun--
			return
		}
		val acTable = acTableFor(component)
		var coefficientIndex = spectralStart
		while (coefficientIndex <= spectralEnd) {
			val runAndSize = acTable.decode(reader)
			val magnitudeCategory = runAndSize and 0x0F
			var zeroRunLength = runAndSize shr 4
			if (magnitudeCategory == 0) {
				if (zeroRunLength != 15) {
					// EOBn: this block plus the next 2^n - 1 + extra blocks have an empty rest-of-band.
					endOfBandRun = 1 shl zeroRunLength
					if (zeroRunLength != 0) {
						endOfBandRun += reader.readBits(zeroRunLength)
					}
					endOfBandRun--
					break
				}
				// ZRL: skip 16 zero coefficients.
				coefficientIndex += 16
				continue
			}
			coefficientIndex += zeroRunLength
			if (coefficientIndex > spectralEnd) {
				break
			}
			val value = extendJpegValue(reader.readBits(magnitudeCategory), magnitudeCategory)
			component.coefficients[blockOffset + JpegConstants.NATURAL_ORDER[coefficientIndex]] = (value shl approximationLow).toShort()
			coefficientIndex++
			zeroRunLength = 0
		}
	}

	/**
	 * Decodes a progressive AC refinement scan (T.81 G.1.2.3).
	 *
	 * The subtle scan type: newly nonzero coefficients arrive as run/size pairs whose size is always 1,
	 * while every already-nonzero coefficient passed over consumes one correction bit.  A correction bit
	 * of 1 means the coefficient's magnitude gains the bit at Al.
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int blockOffset         Index of the block's first coefficient.
	 */
	private fun decodeAcRefine(reader: JpegBitReader, component: JpegComponent, blockOffset: Int) {
		val positiveBit = 1 shl approximationLow
		val negativeBit = -1 shl approximationLow
		var coefficientIndex = spectralStart

		if (endOfBandRun == 0) {
			while (coefficientIndex <= spectralEnd) {
				val runAndSize = acTableFor(component).decode(reader)
				val magnitudeCategory = runAndSize and 0x0F
				var zeroRunLength = runAndSize shr 4
				var newValue = 0

				if (magnitudeCategory == 0) {
					if (zeroRunLength != 15) {
						// EOBn: the rest of this band, and of the next blocks, is handled as corrections only.
						endOfBandRun = 1 shl zeroRunLength
						if (zeroRunLength != 0) {
							endOfBandRun += reader.readBits(zeroRunLength)
						}
						break
					}
					// ZRL: skip 16 zero-history coefficients (correcting nonzero ones on the way).
				} else {
					// A newly nonzero coefficient; its magnitude is always exactly 1 at this bit position.
					newValue = if (reader.readBit() != 0) positiveBit else negativeBit
				}

				// Walk forward over already-nonzero coefficients (appending correction bits) until
				// zeroRunLength zero-history coefficients have been passed.
				while (coefficientIndex <= spectralEnd) {
					val position = blockOffset + JpegConstants.NATURAL_ORDER[coefficientIndex]
					val existing = component.coefficients[position].toInt()
					if (existing != 0) {
						appendCorrectionBit(reader, component, position, existing, positiveBit, negativeBit)
					} else {
						if (zeroRunLength == 0) {
							break
						}
						zeroRunLength--
					}
					coefficientIndex++
				}

				if (newValue != 0 && coefficientIndex <= spectralEnd) {
					component.coefficients[blockOffset + JpegConstants.NATURAL_ORDER[coefficientIndex]] = newValue.toShort()
				}
				coefficientIndex++
			}
		}

		if (endOfBandRun > 0) {
			// Inside an end-of-band run: no new coefficients, but existing ones still take correction bits.
			while (coefficientIndex <= spectralEnd) {
				val position = blockOffset + JpegConstants.NATURAL_ORDER[coefficientIndex]
				val existing = component.coefficients[position].toInt()
				if (existing != 0) {
					appendCorrectionBit(reader, component, position, existing, positiveBit, negativeBit)
				}
				coefficientIndex++
			}
			endOfBandRun--
		}
	}

	/**
	 * Reads one correction bit and, when set, grows an already-nonzero coefficient's magnitude by the
	 * bit at Al.  Re-applying an already-applied bit is a no-op, matching libjpeg.
	 *
	 * @param JpegBitReader reader    The entropy-coded bit source.
	 * @param JpegComponent component The component being decoded.
	 * @param Int position            Index of the coefficient in the component's buffer.
	 * @param Int existing            The coefficient's current value.
	 * @param Int positiveBit         1 shifted to the bit position being coded.
	 * @param Int negativeBit         -1 shifted to the bit position being coded.
	 */
	private fun appendCorrectionBit(reader: JpegBitReader, component: JpegComponent, position: Int, existing: Int, positiveBit: Int, negativeBit: Int) {
		if (reader.readBit() == 0) {
			return
		}
		if ((existing and positiveBit) != 0) {
			return
		}
		val corrected = if (existing >= 0) existing + positiveBit else existing + negativeBit
		component.coefficients[position] = corrected.toShort()
	}

	/**
	 * Dequantizes and inverse-transforms every block of a component into its sample plane.
	 *
	 * @param JpegComponent component The component to render.
	 */
	private fun renderComponent(component: JpegComponent) {
		val quantizationTable =
			quantizationTables[component.quantizationTableIndex]
				?: throw IllegalArgumentException("JPEG references undefined quantization table ${component.quantizationTableIndex}")
		component.planeStride = component.blocksPerLine * 8
		component.plane = ByteArray(component.planeStride * component.blocksPerColumn * 8)

		for (blockRow in 0 until component.blocksPerColumn) {
			for (blockColumn in 0 until component.blocksPerLine) {
				val blockOffset = (blockRow * component.blocksPerLine + blockColumn) * JpegConstants.BLOCK_COEFFICIENTS
				for (coefficientIndex in 0 until JpegConstants.BLOCK_COEFFICIENTS) {
					idctBlock[coefficientIndex] = component.coefficients[blockOffset + coefficientIndex] * quantizationTable[coefficientIndex]
				}
				JpegIdct.inverse(idctBlock, idctWorkspace, component.plane, blockRow * 8 * component.planeStride + blockColumn * 8, component.planeStride)
			}
		}
		// The coefficients are spent; release them before the (larger) upsample buffers are allocated.
		component.coefficients = ShortArray(0)
	}

	/**
	 * Renders, upsamples, and color-converts every component into the finished image.
	 *
	 * @return JpegImage The finished image.
	 */
	private fun assembleImage(): JpegImage {
		require(frameSeen) { "JPEG has no frame header" }
		val colorTransform = forcedColorTransform ?: detectColorTransform()
		for (component in components) {
			renderComponent(component)
		}

		if (components.size == 1) {
			val luminance = upsampleComponent(components[0])
			return JpegImage(frameWidth, frameHeight, 1, luminance)
		}

		val firstPlane = upsampleComponent(components[0])
		val secondPlane = upsampleComponent(components[1])
		val thirdPlane = upsampleComponent(components[2])
		val pixelCount = frameWidth * frameHeight
		val rgb = ByteArray(pixelCount * 3)

		if (colorTransform == JpegColorTransform.NONE) {
			for (pixelIndex in 0 until pixelCount) {
				rgb[pixelIndex * 3] = firstPlane[pixelIndex]
				rgb[pixelIndex * 3 + 1] = secondPlane[pixelIndex]
				rgb[pixelIndex * 3 + 2] = thirdPlane[pixelIndex]
			}
			return JpegImage(frameWidth, frameHeight, 3, rgb)
		}

		for (pixelIndex in 0 until pixelCount) {
			val luminance = firstPlane[pixelIndex].toInt() and 0xFF
			val blueChroma = secondPlane[pixelIndex].toInt() and 0xFF
			val redChroma = thirdPlane[pixelIndex].toInt() and 0xFF
			rgb[pixelIndex * 3] = clampToByte(luminance + JpegColorTables.redFromRedChroma[redChroma])
			rgb[pixelIndex * 3 + 1] =
				clampToByte(luminance + ((JpegColorTables.greenFromBlueChroma[blueChroma] + JpegColorTables.greenFromRedChroma[redChroma]) shr JpegColorTables.SCALE_BITS))
			rgb[pixelIndex * 3 + 2] = clampToByte(luminance + JpegColorTables.blueFromBlueChroma[blueChroma])
		}
		return JpegImage(frameWidth, frameHeight, 3, rgb)
	}

	/**
	 * Determines the frame's color space the way libjpeg does: JFIF implies YCbCr, an Adobe marker
	 * names the transform outright, and otherwise the component ids are the only hint.
	 *
	 * @return Int The JpegColorTransform code.
	 */
	private fun detectColorTransform(): Int {
		if (components.size == 1) {
			return JpegColorTransform.NONE
		}
		if (jfifSeen) {
			return JpegColorTransform.YCBCR
		}
		adobeTransform?.let { transform ->
			return if (transform == 0) JpegColorTransform.NONE else JpegColorTransform.YCBCR
		}
		// No markers: component ids 'R','G','B' mean RGB, anything else is assumed YCbCr.
		if (components[0].identifier == 'R'.code && components[1].identifier == 'G'.code && components[2].identifier == 'B'.code) {
			return JpegColorTransform.NONE
		}
		return JpegColorTransform.YCBCR
	}

	/**
	 * Expands one component's plane to the full image grid, cropping the MCU padding away.
	 *
	 * Uses the triangle ("fancy") filter for 2x horizontal expansion, matching libjpeg's default; other
	 * ratios replicate samples, which is also what libjpeg does for them.
	 *
	 * @param JpegComponent component The component to upsample.
	 * @return ByteArray frameWidth * frameHeight samples.
	 */
	private fun upsampleComponent(component: JpegComponent): ByteArray {
		val horizontalExpansion = maxHorizontalSamplingFactor / component.horizontalSamplingFactor
		val verticalExpansion = maxVerticalSamplingFactor / component.verticalSamplingFactor
		val output = ByteArray(frameWidth * frameHeight)

		// Test the factors, not the ratio: integer division makes a 3:2 component's expansion 1, and
		// copying whole rows out of a plane narrower than the frame would run off its end.  readFrame
		// rejects such frames outright, so this only has to not be the thing that discovers it.
		if (component.horizontalSamplingFactor == maxHorizontalSamplingFactor && component.verticalSamplingFactor == maxVerticalSamplingFactor) {
			for (row in 0 until frameHeight) {
				component.plane.copyInto(output, row * frameWidth, row * component.planeStride, row * component.planeStride + frameWidth)
			}
			return output
		}

		val fancyEligible = horizontalExpansion == 2 && component.downsampledWidth > 2 && component.horizontalSamplingFactor * 2 == maxHorizontalSamplingFactor
		if (fancyEligible && verticalExpansion == 1) {
			upsampleHorizontallyFancy(component, output)
			return output
		}
		if (fancyEligible && verticalExpansion == 2) {
			upsampleBothFancy(component, output)
			return output
		}

		// Replicate: the general power-of-two path.
		for (row in 0 until frameHeight) {
			val sourceRow = minOf(row / verticalExpansion, component.downsampledHeight - 1)
			for (column in 0 until frameWidth) {
				val sourceColumn = minOf(column / horizontalExpansion, component.downsampledWidth - 1)
				output[row * frameWidth + column] = component.plane[sourceRow * component.planeStride + sourceColumn]
			}
		}
		return output
	}

	/**
	 * Doubles a component horizontally with libjpeg's triangle filter (h2v1_fancy_upsample).
	 *
	 * @param JpegComponent component The component to upsample.
	 * @param ByteArray output        The full-resolution destination.
	 */
	private fun upsampleHorizontallyFancy(component: JpegComponent, output: ByteArray) {
		val sourceWidth = component.downsampledWidth
		val expanded = ByteArray(sourceWidth * 2)
		for (row in 0 until frameHeight) {
			val sourceRow = minOf(row, component.downsampledHeight - 1)
			val rowBase = sourceRow * component.planeStride

			expanded[0] = component.plane[rowBase]
			expanded[1] = (((component.plane[rowBase].toInt() and 0xFF) * 3 + (component.plane[rowBase + 1].toInt() and 0xFF) + 2) shr 2).toByte()
			for (column in 1 until sourceWidth - 1) {
				// 3/4 of the nearer sample plus 1/4 of the further one.
				val nearer = (component.plane[rowBase + column].toInt() and 0xFF) * 3
				expanded[column * 2] = ((nearer + (component.plane[rowBase + column - 1].toInt() and 0xFF) + 1) shr 2).toByte()
				expanded[column * 2 + 1] = ((nearer + (component.plane[rowBase + column + 1].toInt() and 0xFF) + 2) shr 2).toByte()
			}
			val lastValue = component.plane[rowBase + sourceWidth - 1].toInt() and 0xFF
			expanded[(sourceWidth - 1) * 2] = ((lastValue * 3 + (component.plane[rowBase + sourceWidth - 2].toInt() and 0xFF) + 1) shr 2).toByte()
			expanded[(sourceWidth - 1) * 2 + 1] = lastValue.toByte()

			expanded.copyInto(output, row * frameWidth, 0, minOf(frameWidth, expanded.size))
		}
	}

	/**
	 * Doubles a component in both axes with libjpeg's triangle filter (h2v2_fancy_upsample).
	 *
	 * @param JpegComponent component The component to upsample.
	 * @param ByteArray output        The full-resolution destination.
	 */
	private fun upsampleBothFancy(component: JpegComponent, output: ByteArray) {
		val sourceWidth = component.downsampledWidth
		val columnSums = IntArray(sourceWidth)
		val expanded = ByteArray(sourceWidth * 2)

		for (row in 0 until frameHeight) {
			// Each output row blends its source row 3:1 with the neighbour it sits nearer to.
			val nearRow = minOf(row / 2, component.downsampledHeight - 1)
			val farRow =
				if (row % 2 == 0) {
					maxOf(nearRow - 1, 0)
				} else {
					minOf(nearRow + 1, component.downsampledHeight - 1)
				}
			val nearBase = nearRow * component.planeStride
			val farBase = farRow * component.planeStride
			for (column in 0 until sourceWidth) {
				columnSums[column] = (component.plane[nearBase + column].toInt() and 0xFF) * 3 + (component.plane[farBase + column].toInt() and 0xFF)
			}

			expanded[0] = ((columnSums[0] * 4 + 8) shr 4).toByte()
			expanded[1] = ((columnSums[0] * 3 + columnSums[1] + 7) shr 4).toByte()
			for (column in 1 until sourceWidth - 1) {
				expanded[column * 2] = ((columnSums[column] * 3 + columnSums[column - 1] + 8) shr 4).toByte()
				expanded[column * 2 + 1] = ((columnSums[column] * 3 + columnSums[column + 1] + 7) shr 4).toByte()
			}
			expanded[(sourceWidth - 1) * 2] = ((columnSums[sourceWidth - 1] * 3 + columnSums[sourceWidth - 2] + 8) shr 4).toByte()
			expanded[(sourceWidth - 1) * 2 + 1] = ((columnSums[sourceWidth - 1] * 4 + 7) shr 4).toByte()

			expanded.copyInto(output, row * frameWidth, 0, minOf(frameWidth, expanded.size))
		}
	}
}

/**
 * The fixed-point YCbCr to RGB conversion tables from the Independent JPEG Group's jdcolor.c.
 *
 * Precomputing per chroma value keeps the conversion to one add (and one shift for green), and the
 * exact rounding is what makes output match libjpeg.
 */
private object JpegColorTables {
	const val SCALE_BITS = 16
	private const val ONE_HALF = 1 shl (SCALE_BITS - 1)

	// round(coefficient * 2^SCALE_BITS) for the standard JFIF conversion coefficients.
	private const val FIX_1_40200 = 91881
	private const val FIX_1_77200 = 116130
	private const val FIX_0_71414 = 46802
	private const val FIX_0_34414 = 22554

	val redFromRedChroma = IntArray(256)
	val blueFromBlueChroma = IntArray(256)
	val greenFromRedChroma = IntArray(256)
	val greenFromBlueChroma = IntArray(256)

	init {
		for (sampleValue in 0 until 256) {
			// Chroma samples are stored biased by 128, so recenter before scaling.
			val centered = sampleValue - 128
			redFromRedChroma[sampleValue] = (FIX_1_40200 * centered + ONE_HALF) shr SCALE_BITS
			blueFromBlueChroma[sampleValue] = (FIX_1_77200 * centered + ONE_HALF) shr SCALE_BITS
			// The green terms stay scaled; they are summed and shifted together by the caller.
			greenFromRedChroma[sampleValue] = -FIX_0_71414 * centered
			greenFromBlueChroma[sampleValue] = -FIX_0_34414 * centered + ONE_HALF
		}
	}
}

/**
 * Clamps a value to an 8-bit sample.
 *
 * @param Int value The value to clamp.
 * @return Byte The 0..255 sample.
 */
private fun clampToByte(value: Int): Byte =
	when {
		value < 0 -> 0
		value > 255 -> 255.toByte()
		else -> value.toByte()
	}

/**
 * Divides rounding up, for non-negative operands.
 *
 * @param Int dividend The dividend.
 * @param Int divisor  The divisor.
 * @return Int The rounded-up quotient.
 */
private fun ceilDivide(dividend: Int, divisor: Int): Int = (dividend + divisor - 1) / divisor