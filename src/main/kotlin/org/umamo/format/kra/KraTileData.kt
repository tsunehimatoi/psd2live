package org.umamo.format.kra

import org.umamo.format.binary.decodeAscii

/*
 * Parser for one Krita layer's tiled pixel-data file (the imageName/layers/filename entry inside
 * the ZIP).
 *
 * The file is a tiny ASCII header followed by a sequence of tiles. Each tile is a 64x64 block at a
 * given paint-device coordinate; a tile is present only where the layer has content (Krita's canvas
 * is unbounded), so the layer's extent is the union of its tile rectangles.
 *
 * The header is one KEY VALUE pair per newline-terminated line, in this order: VERSION 2,
 * TILEWIDTH 64, TILEHEIGHT 64, PIXELSIZE bytesPerPixel, DATA tileCount. Each tile then has a line
 * x,y,LZF,dataSize followed by dataSize bytes whose first byte flags raw vs LZF-compressed payload
 * (see decodeTilePayload).
 *
 * KRA: header in kis_tiled_data_manager.cc:181-196; per-tile framing in kis_tile_compressor_2.cpp.
 *
 * Krita レイヤーのタイル化ピクセルデータの解析。ASCII ヘッダとタイル列。タイルは内容のある箇所のみ存在する。
 */

/** One decoded 64x64 tile: its top-left paint-device coordinate and interleaved native-order pixels. */
internal class KraTile(
	val left: Int,
	val top: Int,
	val pixels: ByteArray,
)

/** A layer's full tile set plus the geometry needed to assemble it. */
internal class KraLayerTiles(
	val tileWidth: Int,
	val tileHeight: Int,
	val pixelSize: Int,
	val tiles: List<KraTile>,
)

/**
 * Parses a layer data file into its decoded tiles (each tile's pixels are interleaved, native
 * channel order - color conversion happens later, once the layer's KraPixelFormat is known).
 *
 * @param ByteArray data   The complete layer data file bytes.
 * @return KraLayerTiles the geometry and decoded tiles.
 * @throws IllegalArgumentException on an unexpected version, header, or a tile that fails to decode.
 */
internal fun parseKraLayerTiles(data: ByteArray): KraLayerTiles {
	val cursor = AsciiLineCursor(data)

	val version = headerValue(cursor.readLine(), "VERSION")
	require(version == 2) {
		"Unsupported KRA tile format version $version (expected 2)"
	}
	val tileWidth = headerValue(cursor.readLine(), "TILEWIDTH")
	val tileHeight = headerValue(cursor.readLine(), "TILEHEIGHT")
	val pixelSize = headerValue(cursor.readLine(), "PIXELSIZE")
	val tileCount = headerValue(cursor.readLine(), "DATA")

	val tileDataSize = pixelSize * tileWidth * tileHeight
	val tiles = ArrayList<KraTile>(tileCount)

	for (tileIndex in 0 until tileCount) {
		// Tile header: x,y,LZF,dataSize. KRA: kis_tile_compressor_2.cpp:56-66.
		val fields = cursor.readLine().split(',')
		require(fields.size == 4) {
			"Malformed KRA tile header at tile $tileIndex"
		}
		val left = fields[0].trim().toInt()
		val top = fields[1].trim().toInt()
		val compressionName = fields[2].trim()
		val dataSize = fields[3].trim().toInt()
		require(compressionName == "LZF") {
			"Unsupported KRA tile compression '$compressionName' (expected LZF)"
		}

		val payloadOffset = cursor.position
		val decoded = decodeTilePayload(data, payloadOffset, dataSize, tileDataSize, pixelSize)
		cursor.skip(dataSize)

		tiles.add(KraTile(left = left, top = top, pixels = decoded))
	}

	return KraLayerTiles(tileWidth = tileWidth, tileHeight = tileHeight, pixelSize = pixelSize, tiles = tiles)
}

/**
 * Decodes one tile payload into interleaved native-order pixels.
 *
 * The first payload byte is the flag: KRA_RAW_DATA_FLAG means the remaining tileDataSize bytes are
 * the pixels verbatim; KRA_COMPRESSED_DATA_FLAG means an LZF stream that inflates to a planar
 * buffer, which is then de-interleaved with delinearizeColors.
 *
 * @param ByteArray data           Buffer holding the tile payload.
 * @param Int payloadOffset        Offset of the flag byte.
 * @param Int dataSize             Total payload length (flag byte plus data).
 * @param Int tileDataSize         Expected decoded size (pixelSize * tileWidth * tileHeight).
 * @param Int pixelSize            Bytes per pixel (needed to de-interleave).
 * @return ByteArray the decoded, interleaved tile pixels (tileDataSize bytes).
 */
private fun decodeTilePayload(
	data: ByteArray,
	payloadOffset: Int,
	dataSize: Int,
	tileDataSize: Int,
	pixelSize: Int,
): ByteArray {
	val flag = data[payloadOffset].toInt() and 0xFF
	val packed = ByteArray(tileDataSize)
	when (flag) {
		KRA_RAW_DATA_FLAG -> {
			// Already interleaved; copy the tileDataSize bytes after the flag.
			data.copyInto(
				packed,
				destinationOffset = 0,
				startIndex = payloadOffset + 1,
				endIndex = payloadOffset + 1 + tileDataSize,
			)
		}

		KRA_COMPRESSED_DATA_FLAG -> {
			val planar = ByteArray(tileDataSize)
			val written = lzfDecompress(data, payloadOffset + 1, dataSize - 1, planar)
			require(written == tileDataSize) {
				"KRA tile LZF decode produced $written bytes, expected $tileDataSize"
			}
			delinearizeColors(planar, packed, tileDataSize, pixelSize)
		}

		else -> error("Unknown KRA tile data flag $flag")
	}
	return packed
}

/**
 * Parses a KEY value header line and returns the integer value, asserting the key matches.
 *
 * @param String line        The header line (already newline-stripped).
 * @param String expectedKey The key this line must start with.
 * @return Int the parsed value.
 */
private fun headerValue(line: String, expectedKey: String): Int {
	val parts = line.trim().split(' ', limit = 2)
	require(parts.size == 2 && parts[0] == expectedKey) {
		"Expected KRA tile header '$expectedKey <n>', got '$line'"
	}
	return parts[1].trim().toInt()
}

/**
 * A tiny forward cursor that reads newline-terminated ASCII lines out of a mixed text/binary
 * buffer, then hands off raw byte offsets for the binary tile payloads that follow each header line.
 *
 * @property ByteArray data   The backing buffer.
 */
private class AsciiLineCursor(private val data: ByteArray) {
	var position: Int = 0
		private set

	private val newline: Byte = '\n'.code.toByte()

	/**
	 * Reads from the current position up to and consuming the next newline, returning the text.
	 *
	 * @return String the line without its trailing newline.
	 */
	fun readLine(): String {
		val start = position
		while (position < data.size && data[position] != newline) {
			position++
		}
		val line = decodeAscii(data, start, position - start)
		if (position < data.size) {
			position++ // consume the newline
		}
		return line
	}

	/**
	 * Advances the cursor past count bytes of binary payload.
	 *
	 * @param Int count The number of bytes to skip.
	 */
	fun skip(count: Int) {
		position += count
	}
}