// Ported from TwelveMonkeys imageio-webp lossless VP8LDecoder (BSD-3-Clause, Copyright (c) 2017 Harald
// Kuhr, Simon Kammermeier).  VP8L transform chain, Huffman-code reading, and the LZ77 + literal +
// color-cache main loop, over a ByteArray/WebpRaster instead of ImageInputStream/WritableRaster; the
// ImageReadParam/subsampling plumbing is dropped.  See CREDITS.md.

package org.umamo.format.webp

/**
 * VP8L (lossless WebP) decoder.  Fills a full-size [WebpRaster] with straight-alpha RGBA8888 pixels.
 */
internal class Vp8lDecoder(private val reader: WebpLsbBitReader) {
	/**
	 * Decodes the top-level VP8L image into [raster].
	 *
	 * @param WebpRaster raster The full-size output (width x height).
	 * @param Int width         Image width.
	 * @param Int height        Image height.
	 */
	fun decode(raster: WebpRaster, width: Int, height: Int) {
		readVP8Lossless(raster, topLevel = true, width = width, height = height)
	}

	/**
	 * Reads one (sub-)image: its transforms (top level only), color cache, Huffman codes, and pixels,
	 * then applies the transforms' inverses.
	 *
	 * @param WebpRaster raster The raster to fill (full image, or a transform/meta sub-image).
	 * @param Boolean topLevel  Whether this is the top-level image (reads transforms + meta codes).
	 * @param Int width         Raster width.
	 * @param Int height        Raster height.
	 */
	private fun readVP8Lossless(raster: WebpRaster, topLevel: Boolean, width: Int, height: Int) {
		var xSize = width
		val transforms = ArrayList<WebpTransform>()
		while (topLevel && reader.readBit() == 1) {
			xSize = readTransform(xSize, height, transforms)
		}

		var colorCacheBits = 0
		if (reader.readBit() == 1) {
			colorCacheBits = reader.readBits(4).toInt()
			require(colorCacheBits in 1..11) { "Corrupt WebP: color-cache bits $colorCacheBits out of range" }
		}

		val huffmanInfo = readHuffmanCodes(xSize, height, colorCacheBits, topLevel)
		val colorCache = if (colorCacheBits > 0) WebpColorCache(colorCacheBits) else null

		val decodeRaster = if (topLevel) raster.child(0, 0, xSize, height) else raster

		decodeImage(decodeRaster, huffmanInfo, colorCache)

		for (transform in transforms) {
			transform.applyInverse(raster)
		}
	}

	/**
	 * Reads one transform header (and its sub-image data), prepends the inverse transform to
	 * [transforms], and returns the possibly-reduced logical width.
	 *
	 * @param Int xSize                    The current logical width.
	 * @param Int ySize                    The image height.
	 * @param MutableList<WebpTransform> transforms The transform list (newest inserted at index 0).
	 * @return Int The logical width after this transform.
	 */
	private fun readTransform(xSize: Int, ySize: Int, transforms: MutableList<WebpTransform>): Int {
		var newXSize = xSize
		when (val transformType = reader.readBits(2).toInt()) {
			WebpTransformType.PREDICTOR, WebpTransformType.COLOR -> {
				val sizeBits = (reader.readBits(3) + 2).toInt()
				val blockWidth = subSampleSize(xSize, sizeBits)
				val blockHeight = subSampleSize(ySize, sizeBits)
				val block = WebpRaster.create(blockWidth, blockHeight)
				readVP8Lossless(block, topLevel = false, width = blockWidth, height = blockHeight)
				if (transformType == WebpTransformType.PREDICTOR) {
					transforms.add(0, PredictorTransform(block, sizeBits))
				} else {
					transforms.add(0, ColorTransform(block, sizeBits))
				}
			}

			WebpTransformType.SUBTRACT_GREEN -> transforms.add(0, SubtractGreenTransform())

			WebpTransformType.COLOR_INDEXING -> {
				val colorTableSize = reader.readBits(8).toInt() + 1 // 1..256
				val safeColorTableSize =
					when {
						colorTableSize > 16 -> 256
						colorTableSize > 4 -> 16
						colorTableSize > 2 -> 4
						else -> 2
					}
				val colorTable = ByteArray(safeColorTableSize * 4)
				readVP8Lossless(WebpRaster.over(colorTable, colorTableSize, 1), topLevel = false, width = colorTableSize, height = 1)
				// The color table is subtraction-coded to reduce entropy; resolve it.
				for (byteIndex in 4 until colorTable.size) {
					colorTable[byteIndex] = (colorTable[byteIndex] + colorTable[byteIndex - 4]).toByte()
				}
				val widthBits =
					when {
						colorTableSize > 16 -> 0
						colorTableSize > 4 -> 1
						colorTableSize > 2 -> 2
						else -> 3
					}
				newXSize = subSampleSize(xSize, widthBits)
				transforms.add(0, ColorIndexingTransform(colorTable, widthBits))
			}

			else -> throw IllegalArgumentException("Invalid WebP VP8L transform type $transformType")
		}
		return newXSize
	}

	/**
	 * Reads the Huffman code groups (and the optional per-block meta-code raster).
	 *
	 * @param Int xSize            The logical width.
	 * @param Int ySize            The image height.
	 * @param Int colorCacheBits   The color-cache size in bits (0 if none).
	 * @param Boolean readMetaCodes Whether to read the meta-code image (top level only).
	 * @return WebpHuffmanInfo The Huffman groups and meta-code selection.
	 */
	private fun readHuffmanCodes(xSize: Int, ySize: Int, colorCacheBits: Int, readMetaCodes: Boolean): WebpHuffmanInfo {
		var huffmanGroupNum = 1
		var metaCodeBits = 0
		var huffmanMetaCodes: WebpRaster? = null

		if (readMetaCodes && reader.readBit() == 1) {
			metaCodeBits = reader.readBits(3).toInt() + 2
			val huffmanXSize = subSampleSize(xSize, metaCodeBits)
			val huffmanYSize = subSampleSize(ySize, metaCodeBits)
			val metaRaster = WebpRaster.create(huffmanXSize, huffmanYSize)
			readVP8Lossless(metaRaster, topLevel = false, width = huffmanXSize, height = huffmanYSize)
			var maxCode = Int.MIN_VALUE
			for (blockY in 0 until huffmanYSize) {
				for (blockX in 0 until huffmanXSize) {
					// The meta group index is (red << 8) | green of the decoded meta pixel.
					val code = (metaRaster.getSample(blockX, blockY, 0) shl 8) or metaRaster.getSample(blockX, blockY, 1)
					maxCode = maxOf(maxCode, code)
				}
			}
			huffmanGroupNum = maxCode + 1
			huffmanMetaCodes = metaRaster
		}

		val huffmanGroups = Array(huffmanGroupNum) { WebpHuffmanCodeGroup(reader, colorCacheBits) }
		return WebpHuffmanInfo(huffmanMetaCodes, metaCodeBits, huffmanGroups)
	}

	/**
	 * The LZ77 + literal + color-cache decode loop, filling [raster].
	 *
	 * @param WebpRaster raster        The raster to fill.
	 * @param WebpHuffmanInfo huffmanInfo The Huffman groups.
	 * @param WebpColorCache? colorCache  The color cache, or null.
	 */
	private fun decodeImage(raster: WebpRaster, huffmanInfo: WebpHuffmanInfo, colorCache: WebpColorCache?) {
		val width = raster.width
		val height = raster.height
		val huffmanMask = if (huffmanInfo.metaCodeBits == 0) -1 else (1 shl huffmanInfo.metaCodeBits) - 1
		var currentGroup = huffmanInfo.huffmanGroups[0]
		val rgba = ByteArray(4)

		var x = 0
		var y = 0
		while (y < height) {
			if ((x and huffmanMask) == 0 && huffmanInfo.huffmanMetaCodes != null) {
				currentGroup = huffmanInfo.huffmanGroups[metaGroupIndex(huffmanInfo, x, y)]
			}

			val code = currentGroup.mainCode.readSymbol(reader)
			if (code < 256) {
				decodeLiteral(raster, colorCache, currentGroup, rgba, x, y, code)
				x++
			} else if (code < 256 + 24) {
				val length = decodeBackwardReference(raster, colorCache, width, currentGroup, rgba, code, x, y)
				val linear = y * width + x + length
				x = linear % width
				y = linear / width
				if (y < height && huffmanInfo.huffmanMetaCodes != null) {
					currentGroup = huffmanInfo.huffmanGroups[metaGroupIndex(huffmanInfo, x, y)]
				}
				continue
			} else {
				decodeCached(raster, colorCache!!, rgba, x, y, code)
				x++
			}

			if (x >= width) {
				x = 0
				y++
			}
		}
	}

	/**
	 * The meta group index for block containing pixel (x, y).
	 *
	 * @param WebpHuffmanInfo huffmanInfo The Huffman info (with the meta raster).
	 * @param Int x                       Pixel column.
	 * @param Int y                       Pixel row.
	 * @return Int The group index.
	 */
	private fun metaGroupIndex(huffmanInfo: WebpHuffmanInfo, x: Int, y: Int): Int {
		val meta = huffmanInfo.huffmanMetaCodes!!
		val blockX = x shr huffmanInfo.metaCodeBits
		val blockY = y shr huffmanInfo.metaCodeBits
		return (meta.getSample(blockX, blockY, 0) shl 8) or meta.getSample(blockX, blockY, 1)
	}

	/**
	 * Decodes a literal pixel: green is the main code, red/blue/alpha come from their own trees.
	 */
	private fun decodeLiteral(raster: WebpRaster, colorCache: WebpColorCache?, group: WebpHuffmanCodeGroup, rgba: ByteArray, x: Int, y: Int, green: Int) {
		val red = group.redCode.readSymbol(reader)
		val blue = group.blueCode.readSymbol(reader)
		val alpha = group.alphaCode.readSymbol(reader)
		rgba[0] = red.toByte()
		rgba[1] = green.toByte()
		rgba[2] = blue.toByte()
		rgba[3] = alpha.toByte()
		raster.setPixel(x, y, rgba)
		colorCache?.insert(((alpha and 0xff) shl 24) or ((red and 0xff) shl 16) or ((green and 0xff) shl 8) or (blue and 0xff))
	}

	/**
	 * Decodes a color-cache reference into pixel (x, y).
	 */
	private fun decodeCached(raster: WebpRaster, colorCache: WebpColorCache, rgba: ByteArray, x: Int, y: Int, code: Int) {
		val argb = colorCache.lookup(code - 256 - 24)
		rgba[0] = ((argb shr 16) and 0xff).toByte()
		rgba[1] = ((argb shr 8) and 0xff).toByte()
		rgba[2] = (argb and 0xff).toByte()
		rgba[3] = (argb ushr 24).toByte()
		raster.setPixel(x, y, rgba)
	}

	/**
	 * Decodes an LZ77 backward reference starting at (x, y), copying `length` pixels from the resolved
	 * source position, and returns the copied length.
	 */
	private fun decodeBackwardReference(raster: WebpRaster, colorCache: WebpColorCache?, width: Int, group: WebpHuffmanCodeGroup, rgba: ByteArray, code: Int, x: Int, y: Int): Int {
		val length = lz77decode(code - 256)
		val remaining = width * raster.height - (y * width + x)
		require(length <= remaining) { "Corrupt WebP: backward reference exceeds image bounds" }

		val distancePrefix = group.distanceCode.readSymbol(reader)
		val distanceCode = lz77decode(distancePrefix)

		var sourceX: Int
		var sourceY: Int
		if (distanceCode > 120) {
			val distance = distanceCode - 120
			sourceY = y - (distance / width)
			sourceX = x - (distance % width)
		} else {
			val packed = DISTANCES[distanceCode - 1].toInt()
			sourceX = x - (8 - (packed and 0xf))
			sourceY = y - (packed shr 4)
		}
		if (sourceX < 0) {
			sourceY--
			sourceX += width
		} else if (sourceX >= width) {
			sourceX -= width
			sourceY++
		}
		require(sourceY >= 0 && sourceY < raster.height) { "Corrupt WebP: backward reference outside image" }

		var copyX = x
		var copyY = y
		var readX = sourceX
		var readY = sourceY
		var remainingLength = length
		while (remainingLength > 0) {
			if (copyX == width) {
				copyX = 0
				copyY++
			}
			raster.getPixel(readX, readY, rgba)
			readX++
			raster.setPixel(copyX, copyY, rgba)
			if (readX == width) {
				readX = 0
				readY++
			}
			colorCache?.insert(((rgba[3].toInt() and 0xff) shl 24) or ((rgba[0].toInt() and 0xff) shl 16) or ((rgba[1].toInt() and 0xff) shl 8) or (rgba[2].toInt() and 0xff))
			copyX++
			remainingLength--
		}
		return length
	}

	/**
	 * Decodes an LZ77 length/distance prefix code to its value (VP8L spec §4.2.2).
	 *
	 * @param Int prefixCode The prefix code.
	 * @return Int The decoded length or distance.
	 */
	private fun lz77decode(prefixCode: Int): Int {
		if (prefixCode < 4) {
			return prefixCode + 1
		}
		val extraBits = (prefixCode - 2) shr 1
		val offset = (2 + (prefixCode and 1)) shl extraBits
		return offset + reader.readBits(extraBits).toInt() + 1
	}

	companion object {
		// Short backward-reference distances: high nibble is the y distance, low nibble is 8 minus the x distance.
		private val DISTANCES =
			byteArrayOf(
				0x18,
				0x07,
				0x17,
				0x19,
				0x28,
				0x06,
				0x27,
				0x29,
				0x16,
				0x1a,
				0x26,
				0x2a,
				0x38,
				0x05,
				0x37,
				0x39,
				0x15,
				0x1b,
				0x36,
				0x3a,
				0x25,
				0x2b,
				0x48,
				0x04,
				0x47,
				0x49,
				0x14,
				0x1c,
				0x35,
				0x3b,
				0x46,
				0x4a,
				0x24,
				0x2c,
				0x58,
				0x45,
				0x4b,
				0x34,
				0x3c,
				0x03,
				0x57,
				0x59,
				0x13,
				0x1d,
				0x56,
				0x5a,
				0x23,
				0x2d,
				0x44,
				0x4c,
				0x55,
				0x5b,
				0x33,
				0x3d,
				0x68,
				0x02,
				0x67,
				0x69,
				0x12,
				0x1e,
				0x66,
				0x6a,
				0x22,
				0x2e,
				0x54,
				0x5c,
				0x43,
				0x4d,
				0x65,
				0x6b,
				0x32,
				0x3e,
				0x78,
				0x01,
				0x77,
				0x79,
				0x53,
				0x5d,
				0x11,
				0x1f,
				0x64,
				0x6c,
				0x42,
				0x4e,
				0x76,
				0x7a,
				0x21,
				0x2f,
				0x75,
				0x7b,
				0x31,
				0x3f,
				0x63,
				0x6d,
				0x52,
				0x5e,
				0x00,
				0x74,
				0x7c,
				0x41,
				0x4f,
				0x10,
				0x20,
				0x62,
				0x6e,
				0x30,
				0x73,
				0x7d,
				0x51,
				0x5f,
				0x40,
				0x72,
				0x7e,
				0x61,
				0x6f,
				0x50,
				0x71,
				0x7f,
				0x60,
				0x70,
			)

		/**
		 * Sub-samples a size by [samplingBits] (ceil division by 2^bits).
		 *
		 * @param Int size         The full size.
		 * @param Int samplingBits The sampling shift.
		 * @return Int The reduced size.
		 */
		private fun subSampleSize(size: Int, samplingBits: Int): Int = (size + (1 shl samplingBits) - 1) shr samplingBits
	}
}