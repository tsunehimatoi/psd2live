package org.umamo.format.clip

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.binary.encodeUtf16Be
import org.umamo.format.binary.inflateZlib

/**
 * Decodes a CLIP layer's tiled raster into a cropped RGBA8888 buffer.
 *
 * EN: A layer's pixels live in an external CHNKExta chunk as a grid of 256x256 tiles.  Each tile is
 *     `[4-byte little-endian length][zlib stream]`; the inflated tile is `2562565` bytes laid out
 *     as a one-byte alpha plane followed by an interleaved BGRA color plane (the color plane's own
 *     alpha slot is unused).  The tile grid (columns/rows and the padded image size) comes from the
 *     Offscreen.Attribute index.  This object walks the block data, inflates each non-empty tile,
 *     converts planes to straight-alpha RGBA, and assembles the tiles cropped to their bounding box.
 * JA: レイヤーのラスタは 256x256 タイル（4バイト長 + zlib, 展開後はアルファ面 + BGRA 面）.  復号して結合する.
 */
internal object ClipRaster {
	private const val TILE = 256
	private const val TILE_PIXELS = TILE * TILE // 65536 - the 8-bit alpha-plane byte count.

	// Every tile is an 8-bit/1-bit alpha plane FIRST, then a color plane.  The inflated byte count
	// identifies the canvas color mode (all corpus samples have CanvasChannelBytes = 1 / 8-bit):
	//   RGBA       alpha(8b) + BGRA(4x8b) = 5 * TILE_PIXELS   (ChannelOrder 33)
	//   Greyscale  alpha(8b) + gray(8b)   = 2 * TILE_PIXELS   (ChannelOrder 17, ColorType 1)
	//   Monochrome alpha(1b) + value(1b)  = TILE_PIXELS / 4   (ChannelOrder 17, ColorType 2)
	private const val RGBA_TILE_BYTES = TILE_PIXELS * 5 // 327680
	private const val GREYSCALE_TILE_BYTES = TILE_PIXELS * 2 // 131072
	private const val MONOCHROME_TILE_BYTES = TILE_PIXELS / 4 // 16384 (two 1-bit planes, MSB-first)

	// One 1-bit plane is TILE_PIXELS/8 bytes; the monochrome value plane follows the alpha plane.
	private const val MONOCHROME_PLANE_BYTES = TILE_PIXELS / 8 // 8192

	// Generous inflate ceiling so a malformed/other-format tile can't overrun (8 bytes/pixel max).
	private const val MAX_TILE_BYTES = TILE_PIXELS * 8

	/** The pixel layout of a tile, identified by its inflated byte count. */
	private enum class TileFormat { Rgba, Greyscale, Monochrome }

	/**
	 * Maps an inflated tile byte count to its [TileFormat], or null for an unrecognized layout.
	 *
	 * @param Int inflatedLength The number of inflated tile bytes.
	 * @return TileFormat The pixel layout, or null if unsupported.
	 */
	private fun tileFormatFor(inflatedLength: Int): TileFormat? =
		when (inflatedLength) {
			RGBA_TILE_BYTES -> TileFormat.Rgba
			GREYSCALE_TILE_BYTES -> TileFormat.Greyscale
			MONOCHROME_TILE_BYTES -> TileFormat.Monochrome
			else -> null
		}

	// Block-data string tags (UTF-16BE) and the marker for "is the 2nd u32 the start of a tag".
	private val BEGIN_TAG = encodeUtf16Be("BlockDataBeginChunk")
	private val END_TAG = encodeUtf16Be("BlockDataEndChunk")
	private val STATUS_TAG = encodeUtf16Be("BlockStatus")
	private val CHECKSUM_TAG = encodeUtf16Be("BlockCheckSum")

	// "\x00B\x00l" - the first two UTF-16BE code units shared by every Block* tag; if the 2nd u32
	// equals this, the first u32 was the string length (no leading size word).
	private const val TAG_PREFIX = 0x0042006CL

	private const val CHUNK_HEADER_SIZE = 16 // 8-byte id + u64 size
	private const val EXTERNAL_HEADER_SIZE = 56 // CHNKExta payload header: u64 + 40-byte id + u64

	/**
	 * Decodes one layer's raster (optionally baking in its grayscale mask), or returns null when
	 * there is no decodable content (no non-empty tiles, an unparseable Attribute, or an unexpected
	 * pixel format) so the caller can fall back to a placeholder.
	 *
	 * The mask, when present, modulates alpha: `outputAlpha = colorAlpha * mask / 255`.  Mask tiles
	 * align with color tiles by `(col, row)` - both offscreens are canvas-anchored identically - so a
	 * color tile with no co-located mask tile is left fully visible (the mask InitColor is 255).
	 *
	 * @param ByteArray fileBytes        The complete .clip file (chunks are read from it).
	 * @param Int colorChunkOffset       Absolute file offset of the layer's color CHNKExta chunk.
	 * @param ByteArray colorAttribute   The color Offscreen.Attribute tile index.
	 * @param Int maskChunkOffset        Absolute file offset of the mask CHNKExta chunk, or -1 if none.
	 * @param ByteArray maskAttribute    The mask Offscreen.Attribute, or null if the layer has no mask.
	 * @param Int anchorX                Canvas X of tile column 0 (LayerOffsetX + LayerRenderOffscrOffsetX).
	 * @param Int anchorY                Canvas Y of tile row 0.
	 * @return ClipDecodedRaster The placed, cropped RGBA raster, or null if nothing decodable.
	 */
	fun decodeLayer(
		fileBytes: ByteArray,
		colorChunkOffset: Int,
		colorAttribute: ByteArray,
		maskChunkOffset: Int,
		maskAttribute: ByteArray?,
		anchorX: Int,
		anchorY: Int,
	): ClipDecodedRaster? {
		val grid = parseAttributeGrid(colorAttribute) ?: return null
		if (grid.columns <= 0 || grid.rows <= 0) {
			return null
		}

		val tiles = walkBlockData(fileBytes, colorChunkOffset)
		val nonEmpty = tiles.filter { tile -> tile.bytes.isNotEmpty() }
		if (nonEmpty.isEmpty()) {
			return null
		}

		// Mask tiles keyed by (col, row), aligned to color tiles (shared canvas-0,0 anchor).  Absent
		// for layers without a mask, and per-tile-absent where the mask InitColor (255) applies.
		val maskTilesByColRow =
			if (maskAttribute != null && maskChunkOffset >= 0) {
				buildMaskTiles(fileBytes, maskChunkOffset, maskAttribute)
			} else {
				emptyMap()
			}

		// Crop to the bounding box of non-empty tiles - bounds memory for large, mostly-empty layers
		// and matches the KRA reader's union-of-tiles cropping.
		var minColumn = Int.MAX_VALUE
		var minRow = Int.MAX_VALUE
		var maxColumn = Int.MIN_VALUE
		var maxRow = Int.MIN_VALUE
		for (tile in nonEmpty) {
			val column = tile.index % grid.columns
			val row = tile.index / grid.columns
			if (column < minColumn) {
				minColumn = column
			}
			if (row < minRow) {
				minRow = row
			}
			if (column > maxColumn) {
				maxColumn = column
			}
			if (row > maxRow) {
				maxRow = row
			}
		}

		val widthPx = (maxColumn - minColumn + 1) * TILE
		val heightPx = (maxRow - minRow + 1) * TILE
		val rgba = ByteArray(widthPx * heightPx * 4)

		val inflateBuffer = ByteArray(MAX_TILE_BYTES)
		for (tile in nonEmpty) {
			val inflatedLength = inflateTile(tile.bytes, inflateBuffer)
			// Identify the color mode from the tile size; skip an unrecognized layout rather than guess.
			val format = tileFormatFor(inflatedLength) ?: continue
			val column = tile.index % grid.columns
			val row = tile.index / grid.columns
			val maskTile = maskTilesByColRow[packColumnRow(column, row)]
			blitTile(
				tileBytes = inflateBuffer,
				format = format,
				rgba = rgba,
				destXTile = (column - minColumn) * TILE,
				destYTile = (row - minRow) * TILE,
				rowStridePx = widthPx,
				maskTile = maskTile,
			)
		}

		val bounds =
			LayerBounds(
				left = anchorX + minColumn * TILE,
				top = anchorY + minRow * TILE,
				width = widthPx,
				height = heightPx,
			)
		return ClipDecodedRaster(bounds, LayerRaster(width = widthPx, height = heightPx, rgba = rgba))
	}

	/**
	 * Decodes a layer's mask offscreen into its non-empty grayscale tiles, keyed by `(col, row)`.
	 *
	 * Each mask tile inflates to a single-channel 256x256 plane (TILE_PIXELS bytes).  Empty tiles are
	 * omitted: their absence signals the mask InitColor (255, fully visible) at decode time.
	 *
	 * @param ByteArray fileBytes        The complete .clip file.
	 * @param Int maskChunkOffset        Absolute file offset of the mask CHNKExta chunk.
	 * @param ByteArray maskAttribute    The mask Offscreen.Attribute (gives the mask grid columns).
	 * @return Map Non-empty mask tiles (65536-byte planes) keyed by packed (col, row).
	 */
	private fun buildMaskTiles(fileBytes: ByteArray, maskChunkOffset: Int, maskAttribute: ByteArray): Map<Long, ByteArray> {
		val grid = parseAttributeGrid(maskAttribute) ?: return emptyMap()
		if (grid.columns <= 0) {
			return emptyMap()
		}
		val tiles = walkBlockData(fileBytes, maskChunkOffset)
		val result = HashMap<Long, ByteArray>()
		val buffer = ByteArray(MAX_TILE_BYTES)
		for (tile in tiles) {
			if (tile.bytes.isEmpty()) {
				continue
			}
			val inflatedLength = inflateTile(tile.bytes, buffer)
			// A mask follows the layer's color mode: 8-bit grayscale (TILE_PIXELS bytes) on RGBA/greyscale
			// layers, or 1-bit (TILE_PIXELS/8 bytes, MSB-first) on monochrome layers.  Normalize both to a
			// per-pixel 0..255 plane so blitTile's alpha multiply is uniform.  Skip any other size.
			val maskPlane =
				when (inflatedLength) {
					TILE_PIXELS -> buffer.copyOf(TILE_PIXELS)
					MONOCHROME_PLANE_BYTES -> expandBitPlaneToBytes(buffer)
					else -> continue
				}
			val column = tile.index % grid.columns
			val row = tile.index / grid.columns
			result[packColumnRow(column, row)] = maskPlane
		}
		return result
	}

	/**
	 * Expands an MSB-first 1-bit plane (TILE_PIXELS/8 bytes) into a TILE_PIXELS-byte plane of 0/255,
	 * so a 1-bit mask multiplies alpha identically to an 8-bit mask (bit set => 255 reveals).
	 *
	 * @param ByteArray bits The inflated 1-bit plane (its first MONOCHROME_PLANE_BYTES bytes are used).
	 * @return ByteArray A TILE_PIXELS-byte plane, 255 where the bit is set and 0 otherwise.
	 */
	private fun expandBitPlaneToBytes(bits: ByteArray): ByteArray {
		val plane = ByteArray(TILE_PIXELS)
		for (pixelIndex in 0 until TILE_PIXELS) {
			if (bitAt(bits, 0, pixelIndex)) {
				plane[pixelIndex] = 0xFF.toByte()
			}
		}
		return plane
	}

	/**
	 * Packs a tile (column, row) into a single Long map key.
	 *
	 * @param Int column The tile column.
	 * @param Int row    The tile row.
	 * @return Long The packed key.
	 */
	private fun packColumnRow(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)

	/**
	 * Writes one inflated 256x256 tile into the assembled RGBA buffer at the given tile origin,
	 * expanding the tile's color mode to RGBA8888 and optionally baking in a grayscale mask tile.
	 *
	 * @param ByteArray tileBytes    The inflated tile (alpha plane first, then the color plane).
	 * @param TileFormat format      The tile's color mode (RGBA / greyscale / monochrome).
	 * @param ByteArray rgba         The destination RGBA8888 buffer.
	 * @param Int destXTile          Destination X of the tile's left edge, in pixels.
	 * @param Int destYTile          Destination Y of the tile's top edge, in pixels.
	 * @param Int rowStridePx        Destination buffer width in pixels.
	 * @param ByteArray? maskTile    A 256x256 single-byte mask plane to bake into alpha, or null.
	 */
	private fun blitTile(
		tileBytes: ByteArray,
		format: TileFormat,
		rgba: ByteArray,
		destXTile: Int,
		destYTile: Int,
		rowStridePx: Int,
		maskTile: ByteArray?,
	) {
		for (y in 0 until TILE) {
			val sourceRow = y * TILE
			var destination = ((destYTile + y) * rowStridePx + destXTile) * 4
			for (x in 0 until TILE) {
				val sourcePixel = sourceRow + x
				val red: Int
				val green: Int
				val blue: Int
				var alpha: Int
				when (format) {
					TileFormat.Rgba -> {
						// CLIP: alpha plane [0, TILE_PIXELS), then interleaved B,G,R,(unused).
						alpha = tileBytes[sourcePixel].toInt() and 0xFF
						val colorBase = TILE_PIXELS + sourcePixel * 4
						red = tileBytes[colorBase + 2].toInt() and 0xFF
						green = tileBytes[colorBase + 1].toInt() and 0xFF
						blue = tileBytes[colorBase].toInt() and 0xFF
					}

					TileFormat.Greyscale -> {
						// CLIP: alpha plane [0, TILE_PIXELS), then an 8-bit gray plane.
						alpha = tileBytes[sourcePixel].toInt() and 0xFF
						val gray = tileBytes[TILE_PIXELS + sourcePixel].toInt() and 0xFF
						red = gray
						green = gray
						blue = gray
					}

					TileFormat.Monochrome -> {
						// CLIP: 1-bit alpha plane, then a 1-bit value plane (MSB-first); value 0 = black.
						alpha = if (bitAt(tileBytes, 0, sourcePixel)) 0xFF else 0
						val gray = if (bitAt(tileBytes, MONOCHROME_PLANE_BYTES, sourcePixel)) 0xFF else 0
						red = gray
						green = gray
						blue = gray
					}
				}
				if (maskTile != null) {
					// Bake the mask: outputAlpha = layerAlpha * mask / 255 (mask byte is grayscale 0..255).
					alpha = alpha * (maskTile[sourcePixel].toInt() and 0xFF) / 255
				}
				rgba[destination] = red.toByte()
				rgba[destination + 1] = green.toByte()
				rgba[destination + 2] = blue.toByte()
				rgba[destination + 3] = alpha.toByte()
				destination += 4
			}
		}
	}

	/**
	 * Reads one bit from an MSB-first 1-bit plane starting at [planeOffset].
	 *
	 * @param ByteArray bytes        The tile buffer.
	 * @param Int planeOffset        Byte offset of the 1-bit plane.
	 * @param Int pixelIndex         Pixel index within the 256x256 tile.
	 * @return Boolean Whether the bit is set.
	 */
	private fun bitAt(bytes: ByteArray, planeOffset: Int, pixelIndex: Int): Boolean {
		val byte = bytes[planeOffset + (pixelIndex shr 3)].toInt()
		return (byte shr (7 - (pixelIndex and 7))) and 1 != 0
	}

	/**
	 * Inflates a tile body (`[4-byte LE length][zlib stream]`) into [destination], returning the
	 * number of decompressed bytes (0 on failure).
	 *
	 * @param ByteArray tileBytes    The tile body including its 4-byte length prefix.
	 * @param ByteArray destination  The reusable inflate buffer (sized to MAX_TILE_BYTES).
	 * @return Int The number of bytes inflated, or 0 if the stream was invalid.
	 */
	private fun inflateTile(tileBytes: ByteArray, destination: ByteArray): Int {
		// The 4-byte little-endian prefix is the zlib stream length; inflating from offset 4 is enough.
		val inflated = inflateZlib(tileBytes, 4, tileBytes.size - 4, destination.size)
		inflated.copyInto(destination, 0, 0, minOf(inflated.size, destination.size))
		return minOf(inflated.size, destination.size)
	}

	/** Columns/rows of a layer's tile grid, read from Offscreen.Attribute. */
	private class AttributeGrid(val columns: Int, val rows: Int)

	/**
	 * Parses the tile-grid dimensions out of Offscreen.Attribute.
	 *
	 * The Attribute is a serialized property block; the "Parameter" entry is followed by four
	 * big-endian u32s: padded width, padded height, column count, row count.  Only the grid shape is
	 * needed to place tiles by their block index.
	 *
	 * @param ByteArray attribute The Offscreen.Attribute bytes.
	 * @return AttributeGrid The columns/rows, or null if the "Parameter" entry was not found.
	 */
	private fun parseAttributeGrid(attribute: ByteArray): AttributeGrid? {
		// CLIP: Offscreen.Attribute - UTF-16BE "Parameter", then width, height, cols, rows (u32 BE).
		val parameterTag = encodeUtf16Be("Parameter")
		val tagAt = indexOf(attribute, parameterTag, 0)
		if (tagAt < 0) {
			return null
		}
		val fieldsAt = tagAt + parameterTag.size
		if (fieldsAt + 16 > attribute.size) {
			return null
		}
		val columns = readUInt32BE(attribute, fieldsAt + 8)
		val rows = readUInt32BE(attribute, fieldsAt + 12)
		return AttributeGrid(columns = columns, rows = rows)
	}

	/** One tile within a layer's external block data: its grid index and (possibly empty) body. */
	private class BlockTile(val index: Int, val bytes: ByteArray)

	/**
	 * Walks the external chunk's block data, returning each BlockDataBeginChunk's tile body (empty
	 * for tiles flagged as having no data).
	 *
	 * @param ByteArray fileBytes  The complete .clip file.
	 * @param Int chunkOffset      Absolute file offset of the CHNKExta chunk.
	 * @return List The tiles in encounter order.
	 */
	private fun walkBlockData(fileBytes: ByteArray, chunkOffset: Int): List<BlockTile> {
		val chunkSize = readUInt64(fileBytes, chunkOffset + 8)
		val blockStart = chunkOffset + CHUNK_HEADER_SIZE + EXTERNAL_HEADER_SIZE
		val blockEnd = minOf(chunkOffset + CHUNK_HEADER_SIZE + chunkSize, fileBytes.size)

		val tiles = ArrayList<BlockTile>()
		var position = blockStart
		while (position + 8 <= blockEnd) {
			val first = readUInt32BE(fileBytes, position)
			val second = readUInt32BELong(fileBytes, position + 4)
			position += 8
			val stringLength: Int
			if (second == TAG_PREFIX) {
				// No leading size word: the first u32 was the string length; rewind to the tag.
				stringLength = first
				position -= 4
			} else {
				stringLength = readUInt32BE(fileBytes, position - 4)
			}
			val tagBytes = stringLength * 2
			if (position + tagBytes > blockEnd) {
				break
			}
			val tag = fileBytes.copyOfRange(position, position + tagBytes)
			position += tagBytes

			when {
				tag.contentEquals(BEGIN_TAG) -> {
					if (position + 20 > blockEnd) {
						break
					}
					val blockIndex = readUInt32BE(fileBytes, position)
					val notEmpty = readUInt32BE(fileBytes, position + 16)
					position += 20
					if (notEmpty > 0) {
						if (position + 4 > blockEnd) {
							break
						}
						val tileLength = readUInt32BE(fileBytes, position)
						position += 4
						val tileEnd = minOf(position + tileLength, blockEnd)
						tiles.add(BlockTile(blockIndex, fileBytes.copyOfRange(position, tileEnd)))
						position = tileEnd
					} else {
						tiles.add(BlockTile(blockIndex, ByteArray(0)))
					}
				}

				tag.contentEquals(END_TAG) -> Unit
				tag.contentEquals(STATUS_TAG) -> position += 28
				tag.contentEquals(CHECKSUM_TAG) -> {
					position += 28
					break
				}

				else -> break
			}
		}
		return tiles
	}

	/**
	 * Returns the start index of needle within haystack at or after from, or -1.
	 *
	 * @param ByteArray haystack The buffer to search.
	 * @param ByteArray needle   The byte sequence to find.
	 * @param Int from           The first index to consider.
	 * @return Int The match start, or -1.
	 */
	private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
		if (needle.isEmpty() || haystack.size < needle.size) {
			return -1
		}
		var start = maxOf(from, 0)
		val lastStart = haystack.size - needle.size
		while (start <= lastStart) {
			var matchIndex = 0
			while (matchIndex < needle.size && haystack[start + matchIndex] == needle[matchIndex]) {
				matchIndex++
			}
			if (matchIndex == needle.size) {
				return start
			}
			start++
		}
		return -1
	}

	/**
	 * Reads a big-endian unsigned 32-bit integer as an Int (CLIP block sizes fit comfortably).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          The offset of the most-significant byte.
	 * @return Int The decoded value.
	 */
	private fun readUInt32BE(bytes: ByteArray, at: Int): Int =
		((bytes[at].toInt() and 0xFF) shl 24) or
			((bytes[at + 1].toInt() and 0xFF) shl 16) or
			((bytes[at + 2].toInt() and 0xFF) shl 8) or
			(bytes[at + 3].toInt() and 0xFF)

	/**
	 * Reads a big-endian unsigned 32-bit integer as a Long (for an unsigned tag-prefix compare).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          The offset of the most-significant byte.
	 * @return Long The decoded value in 0..0xFFFFFFFF.
	 */
	private fun readUInt32BELong(bytes: ByteArray, at: Int): Long = readUInt32BE(bytes, at).toLong() and 0xFFFFFFFFL

	/**
	 * Reads a big-endian unsigned 64-bit integer as an Int (the whole file fits one ByteArray).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          The offset of the most-significant byte.
	 * @return Int The decoded value.
	 */
	private fun readUInt64(bytes: ByteArray, at: Int): Int {
		var value = 0L
		for (byteIndex in 0 until 8) {
			value = (value shl 8) or (bytes[at + byteIndex].toLong() and 0xFF)
		}
		return value.toInt()
	}
}

/** A decoded, placed layer raster: its canvas bounds and the cropped RGBA pixels. */
internal class ClipDecodedRaster(val bounds: LayerBounds, val raster: LayerRaster)