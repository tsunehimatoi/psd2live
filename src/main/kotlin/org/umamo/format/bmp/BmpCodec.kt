package org.umamo.format.bmp

import okio.Buffer
import org.umamo.format.FileKind
import org.umamo.format.raster.RasterCodec
import org.umamo.format.raster.RasterImage

/**
 * Pure-Kotlin BMP codec (desktop JVM and Android), read and write.
 *
 * Reads uncompressed Windows BMPs - 24-bit BGR and 32-bit BGRA (BI_RGB), and 16/32-bit
 * BI_BITFIELDS with explicit channel masks - into the neutral straight-alpha RGBA8888 top-first
 * [RasterImage].  Writes a 32-bit BITMAPV4HEADER file with explicit RGBA channel masks so straight
 * alpha round-trips losslessly.  Palette (<= 8-bit) and RLE BMPs are out of scope and rejected.
 * Trivial and dependency-free, so it doubles as a debug-dump and round-trip test vehicle.
 */
public object BmpCodec : RasterCodec {
	override val kind: FileKind = FileKind.Bmp

	// BMP: compression codes (wingdi.h BI_*).
	private const val BI_RGB = 0
	private const val BI_BITFIELDS = 3
	private const val BI_ALPHABITFIELDS = 6

	private const val FILE_HEADER_SIZE = 14
	private const val V4_HEADER_SIZE = 108

	private const val OPAQUE = 0xFF.toByte()

	/**
	 * True if [candidateBytes] opens with the "BM" signature.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the BMP magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean =
		// BMP: BITMAPFILEHEADER.bfType @ +0x00 = 'B','M'.
		candidateBytes.size >= 2 && candidateBytes[0] == 0x42.toByte() && candidateBytes[1] == 0x4D.toByte()

	/**
	 * Decodes a `.bmp` into a straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.bmp` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		require(matches(bytes)) { "Not a BMP (bad signature)" }
		require(bytes.size >= FILE_HEADER_SIZE + 40) { "BMP too short for a DIB header" }

		// BMP: BITMAPFILEHEADER.bfOffBits @ +0x0A - offset of the pixel array.
		val pixelOffset = readU32LE(bytes, 10).toInt()
		// BMP: BITMAPINFOHEADER @ +0x0E - biSize, biWidth, biHeight, biPlanes, biBitCount, biCompression.
		val dibStart = FILE_HEADER_SIZE
		val dibSize = readU32LE(bytes, dibStart).toInt()
		require(dibSize >= 40) { "Insupported BMP DIB header size $dibSize (Need BITMAPINFOHEADER or later)" }

		val width = readI32LE(bytes, dibStart + 4)
		val rawHeight = readI32LE(bytes, dibStart + 8)
		val bitCount = readU16LE(bytes, dibStart + 14)
		val compression = readU32LE(bytes, dibStart + 16).toInt()
		require(width > 0 && rawHeight != 0) { "BMP has non-positive width or zero height" }
		val height = kotlin.math.abs(rawHeight)
		val topDown = rawHeight < 0 // BMP: a negative biHeight means the rows are stored top-to-bottom.
		require(bitCount == 16 || bitCount == 24 || bitCount == 32) {
			"Unsupported BMP bit count $bitCount (only uncompressed 16/24/32-bit; palette/RLE rejected)"
		}
		require(compression == BI_RGB || compression == BI_BITFIELDS || compression == BI_ALPHABITFIELDS) {
			"Unsupported BMP compression $compression (only BI_RGB / BI_BITFIELDS)"
		}

		val masks = resolveMasks(bytes, dibStart, dibSize, bitCount, compression)

		val rowStride = ((bitCount * width + 31) / 32) * 4
		val rgba = ByteArray(width * height * 4)
		for (fileRow in 0 until height) {
			val rowStart = pixelOffset + fileRow * rowStride
			// Bottom-up storage (positive height) puts file row 0 at the image bottom; flip to top-first.
			val imageRow = if (topDown) fileRow else (height - 1 - fileRow)
			for (column in 0 until width) {
				val destination = (imageRow * width + column) * 4
				when (bitCount) {
					24 -> {
						// BMP: 24-bit BI_RGB pixels are stored B, G, R (no alpha).
						val pixel = rowStart + column * 3
						if (pixel + 2 < bytes.size) {
							rgba[destination] = bytes[pixel + 2]
							rgba[destination + 1] = bytes[pixel + 1]
							rgba[destination + 2] = bytes[pixel]
						}
						rgba[destination + 3] = OPAQUE
					}

					else -> {
						val pixel = rowStart + column * (bitCount / 8)
						val value =
							if (bitCount == 32) {
								if (pixel + 3 < bytes.size) readU32LE(bytes, pixel).toInt() else 0
							} else {
								if (pixel + 1 < bytes.size) readU16LE(bytes, pixel) else 0
							}
						rgba[destination] = extractChannel(value, masks.red).toByte()
						rgba[destination + 1] = extractChannel(value, masks.green).toByte()
						rgba[destination + 2] = extractChannel(value, masks.blue).toByte()
						rgba[destination + 3] = if (masks.alpha == 0) OPAQUE else extractChannel(value, masks.alpha).toByte()
					}
				}
			}
		}
		return RasterImage(width, height, rgba)
	}

	/**
	 * Encodes a [RasterImage] as a 32-bit BGRA BMP with a BITMAPV4HEADER carrying explicit channel
	 * masks (including alpha), stored bottom-up.  Straight alpha round-trips losslessly.
	 *
	 * @param RasterImage model The image to encode.
	 * @return ByteArray The complete `.bmp` file.
	 */
	override fun write(model: RasterImage): ByteArray {
		val rowStride = model.width * 4 // 32-bit rows are already 4-byte aligned.
		val pixelDataSize = rowStride * model.height
		val pixelOffset = FILE_HEADER_SIZE + V4_HEADER_SIZE
		val fileSize = pixelOffset + pixelDataSize

		val out = Buffer()
		// BITMAPFILEHEADER.
		out.writeByte(0x42)
		out.writeByte(0x4D)
		out.writeIntLe(fileSize)
		out.writeShortLe(0) // bfReserved1
		out.writeShortLe(0) // bfReserved2
		out.writeIntLe(pixelOffset) // bfOffBits

		// BITMAPV4HEADER.
		out.writeIntLe(V4_HEADER_SIZE)
		out.writeIntLe(model.width)
		out.writeIntLe(model.height) // positive: bottom-up
		out.writeShortLe(1) // planes
		out.writeShortLe(32) // bitCount
		out.writeIntLe(BI_BITFIELDS)
		out.writeIntLe(pixelDataSize)
		out.writeIntLe(2835) // xPelsPerMeter (~72 DPI)
		out.writeIntLe(2835) // yPelsPerMeter
		out.writeIntLe(0) // clrUsed
		out.writeIntLe(0) // clrImportant
		out.writeIntLe(0x00FF0000) // redMask
		out.writeIntLe(0x0000FF00) // greenMask
		out.writeIntLe(0x000000FF) // blueMask
		out.writeIntLe(-0x1000000) // alphaMask 0xFF000000
		out.writeIntLe(0x73524742) // CSType 'sRGB'
		repeat(9) { out.writeIntLe(0) } // CIEXYZTRIPLE endpoints (ignored for BI_BITFIELDS)
		out.writeIntLe(0) // gammaRed
		out.writeIntLe(0) // gammaGreen
		out.writeIntLe(0) // gammaBlue

		// Pixel array, bottom-up: file row 0 is the image's bottom row. Each pixel is B, G, R, A.
		for (fileRow in 0 until model.height) {
			val imageRow = model.height - 1 - fileRow
			var source = imageRow * rowStride
			for (column in 0 until model.width) {
				out.writeByte(model.rgba[source + 2].toInt() and 0xFF) // B
				out.writeByte(model.rgba[source + 1].toInt() and 0xFF) // G
				out.writeByte(model.rgba[source].toInt() and 0xFF) // R
				out.writeByte(model.rgba[source + 3].toInt() and 0xFF) // A
				source += 4
			}
		}
		return out.readByteArray()
	}

	/** The four channel bit-masks a BI_BITFIELDS (or defaulted BI_RGB) BMP uses. */
	private class ChannelMasks(val red: Int, val green: Int, val blue: Int, val alpha: Int)

	/**
	 * Resolves the per-channel bit masks: explicit ones for BI_BITFIELDS (from the V4+ header, or the
	 * DWORDs after a 40-byte BITMAPINFOHEADER), else the format defaults for BI_RGB.
	 *
	 * @param ByteArray bytes    The complete file.
	 * @param Int dibStart       Offset of the DIB header.
	 * @param Int dibSize        DIB header size in bytes.
	 * @param Int bitCount       Bits per pixel (16 or 32 here).
	 * @param Int compression    The BMP compression code.
	 * @return ChannelMasks The resolved masks (alpha = 0 means "no alpha, treat as opaque").
	 */
	private fun resolveMasks(bytes: ByteArray, dibStart: Int, dibSize: Int, bitCount: Int, compression: Int): ChannelMasks {
		if (compression == BI_BITFIELDS || compression == BI_ALPHABITFIELDS) {
			// The masks sit at the same place either way: inside a V4/V5 header at +40, or as the DWORDs
			// immediately following a bare 40-byte BITMAPINFOHEADER (which bfOffBits skips past).
			val maskBase = dibStart + 40
			val red = readU32LE(bytes, maskBase).toInt()
			val green = readU32LE(bytes, maskBase + 4).toInt()
			val blue = readU32LE(bytes, maskBase + 8).toInt()
			val alpha =
				if (compression == BI_ALPHABITFIELDS || dibSize >= 56) {
					readU32LE(bytes, maskBase + 12).toInt()
				} else {
					0
				}
			return ChannelMasks(red, green, blue, alpha)
		}
		// BI_RGB defaults: 32-bit is X8R8G8B8 (alpha unused → opaque); 16-bit is X1R5G5B5.
		return if (bitCount == 32) {
			ChannelMasks(0x00FF0000, 0x0000FF00, 0x000000FF, 0)
		} else {
			ChannelMasks(0x7C00, 0x03E0, 0x001F, 0)
		}
	}

	/**
	 * Extracts one channel from a packed pixel value via its mask, scaled to 8 bits.
	 *
	 * @param Int value The packed little-endian pixel value.
	 * @param Int mask  The channel bit mask (0 → returns 255, the opaque default for a missing channel).
	 * @return Int The channel value in 0..255.
	 */
	private fun extractChannel(value: Int, mask: Int): Int {
		if (mask == 0) {
			return 0xFF
		}
		val shift = mask.countTrailingZeroBits()
		val bits = mask.countOneBits()
		val raw = (value ushr shift) and ((1 shl bits) - 1)
		val maxValue = (1 shl bits) - 1
		return if (bits == 8) raw else (raw * 255 + maxValue / 2) / maxValue
	}

	/**
	 * Reads a little-endian unsigned 16-bit value.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the least-significant byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16LE(bytes: ByteArray, at: Int): Int =
		(bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

	/**
	 * Reads a little-endian unsigned 32-bit value as a Long.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the least-significant byte.
	 * @return Long The value in 0..4294967295.
	 */
	private fun readU32LE(bytes: ByteArray, at: Int): Long =
		(bytes[at].toLong() and 0xFF) or
			((bytes[at + 1].toLong() and 0xFF) shl 8) or
			((bytes[at + 2].toLong() and 0xFF) shl 16) or
			((bytes[at + 3].toLong() and 0xFF) shl 24)

	/**
	 * Reads a little-endian signed 32-bit value (BMP width/height are signed).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the least-significant byte.
	 * @return Int The signed value.
	 */
	private fun readI32LE(bytes: ByteArray, at: Int): Int =
		(bytes[at].toInt() and 0xFF) or
			((bytes[at + 1].toInt() and 0xFF) shl 8) or
			((bytes[at + 2].toInt() and 0xFF) shl 16) or
			((bytes[at + 3].toInt() and 0xFF) shl 24)
}