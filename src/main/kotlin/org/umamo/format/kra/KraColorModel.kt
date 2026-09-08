package org.umamo.format.kra

/**
 * Maps a Krita layer's color space onto a converter that packs each source pixel into the neutral
 * model's RGBA8888 straight-alpha format.
 *
 * Two things vary between Krita color spaces and must be handled here. First, channel byte order:
 * Krita's integer RGB spaces store BGRA (KoBgrU8Traits / KoBgrU16Traits with blue at 0, green at 1,
 * red at 2), while its float RGB space stores RGBA (KoRgbColorSpaceTraits with red at 0, green at 1,
 * blue at 2). Grayscale is gray then alpha (KoGrayColorSpaceTraits). Second, bit depth: 8/16-bit
 * integer and 32-bit float, down-converted to 8-bit (16-bit keeps the most-significant byte; float
 * is clamped to 0..1 and scaled). The model is always RGBA8888, so high-depth precision cannot be
 * preserved regardless - this is lossy by the model's contract.
 *
 * Scope: RGBA and GRAYA in U8, U16, and F32. Half-float (F16) and other models (CMYK, LAB, XYZ,
 * YCbCr) raise a clear error rather than silently mis-decoding.
 *
 * Krita のカラースペースを RGBA8888 へ変換する。整数 RGB は BGRA、浮動小数 RGB は RGBA、グレースケールは
 * gray, alpha の順。8/16/32 ビットに対応し 8 ビットへ縮約する。
 */
internal enum class KraColorDepth {
	UnsignedByte, // U8
	UnsignedShort, // U16
	Float32, // F32
}

/**
 * A resolved per-layer pixel layout plus the logic to convert one source pixel to RGBA8888.
 *
 * @property Int channelCount       Channels in the source pixel (RGBA is 4, GRAYA is 2).
 * @property KraColorDepth depth    Source bit depth.
 * @property Boolean isRgb          True for the RGBA family, false for GRAYA.
 */
internal class KraPixelFormat(
	val channelCount: Int,
	val depth: KraColorDepth,
	val isRgb: Boolean,
) {
	val bytesPerChannel: Int =
		when (depth) {
			KraColorDepth.UnsignedByte -> 1
			KraColorDepth.UnsignedShort -> 2
			KraColorDepth.Float32 -> 4
		}

	/** Bytes per source pixel - must equal the tile file's PIXELSIZE. */
	val pixelSize: Int = channelCount * bytesPerChannel

	/**
	 * Converts the source pixel at sourceOffset in source to four RGBA8888 bytes written at
	 * destinationOffset in destination.
	 *
	 * @param ByteArray source            Interleaved native-order pixel bytes (one tile or row).
	 * @param Int sourceOffset            Byte offset of the source pixel.
	 * @param ByteArray destination       RGBA8888 output buffer.
	 * @param Int destinationOffset       Byte offset of the destination pixel (4 bytes written).
	 */
	fun convertPixel(
		source: ByteArray,
		sourceOffset: Int,
		destination: ByteArray,
		destinationOffset: Int,
	) {
		if (isRgb) {
			when (depth) {
				// KRA: KoBgrU8Traits - blue at 0, green at 1, red at 2, alpha at 3.
				KraColorDepth.UnsignedByte -> {
					destination[destinationOffset] = source[sourceOffset + 2] // red
					destination[destinationOffset + 1] = source[sourceOffset + 1] // green
					destination[destinationOffset + 2] = source[sourceOffset] // blue
					destination[destinationOffset + 3] = source[sourceOffset + 3] // alpha
				}
				// KRA: KoBgrU16Traits - BGRA, little-endian; keep the MSB (offset+1 of each pair).
				KraColorDepth.UnsignedShort -> {
					destination[destinationOffset] = source[sourceOffset + 5] // red MSB
					destination[destinationOffset + 1] = source[sourceOffset + 3] // green MSB
					destination[destinationOffset + 2] = source[sourceOffset + 1] // blue MSB
					destination[destinationOffset + 3] = source[sourceOffset + 7] // alpha MSB
				}
				// KRA: KoRgbColorSpaceTraits - float RGB is red-first (RGBA), 32-bit little-endian.
				KraColorDepth.Float32 -> {
					destination[destinationOffset] = floatByteToUnorm8(source, sourceOffset) // red
					destination[destinationOffset + 1] = floatByteToUnorm8(source, sourceOffset + 4) // green
					destination[destinationOffset + 2] = floatByteToUnorm8(source, sourceOffset + 8) // blue
					destination[destinationOffset + 3] = floatByteToUnorm8(source, sourceOffset + 12) // alpha
				}
			}
		} else {
			// Grayscale: replicate the single grey channel into R/G/B. KRA: KoGrayColorSpaceTraits.
			val grayByte: Byte
			val alphaByte: Byte
			when (depth) {
				KraColorDepth.UnsignedByte -> {
					grayByte = source[sourceOffset]
					alphaByte = source[sourceOffset + 1]
				}

				KraColorDepth.UnsignedShort -> {
					grayByte = source[sourceOffset + 1] // gray MSB
					alphaByte = source[sourceOffset + 3] // alpha MSB
				}

				KraColorDepth.Float32 -> {
					grayByte = floatByteToUnorm8(source, sourceOffset)
					alphaByte = floatByteToUnorm8(source, sourceOffset + 4)
				}
			}
			destination[destinationOffset] = grayByte
			destination[destinationOffset + 1] = grayByte
			destination[destinationOffset + 2] = grayByte
			destination[destinationOffset + 3] = alphaByte
		}
	}
}

/**
 * Reads a 32-bit little-endian float at offset in source and quantises it to an 8-bit unorm byte
 * (clamped to 0..1, scaled by 255, rounded).
 *
 * @param ByteArray source   Buffer holding the float.
 * @param Int offset         Byte offset of the 4-byte little-endian float.
 * @return Byte the quantised 0..255 channel value (as a signed Byte).
 */
private fun floatByteToUnorm8(source: ByteArray, offset: Int): Byte {
	val bits =
		(source[offset].toInt() and 0xFF) or
			((source[offset + 1].toInt() and 0xFF) shl 8) or
			((source[offset + 2].toInt() and 0xFF) shl 16) or
			((source[offset + 3].toInt() and 0xFF) shl 24)
	val value = Float.fromBits(bits)
	val clamped = value.coerceIn(0f, 1f)
	return (clamped * 255f + 0.5f).toInt().toByte()
}

/**
 * Resolves a Krita colorspacename (such as RGBA, RGBA16, RGBAF32, or GRAYA) to a KraPixelFormat,
 * cross-checking the implied pixel size against the tile file's authoritative PIXELSIZE.
 *
 * Family comes from the prefix (RGBA or GRAYA) and depth from a suffix scan (F32 means float, F16
 * is unsupported, any 16 means uint16, otherwise uint8) - robust across Krita's historically
 * inconsistent names without enumerating every exact string.
 *
 * @param String colorspaceName     The layer's colorspacename attribute.
 * @param Int tilePixelSize         Bytes per pixel from the tile header (PIXELSIZE).
 * @return KraPixelFormat the resolved layout and converter.
 * @throws IllegalArgumentException on an unsupported model/depth or a size mismatch.
 */
internal fun resolveKraPixelFormat(colorspaceName: String, tilePixelSize: Int): KraPixelFormat {
	val normalized = colorspaceName.uppercase()
	val isRgb = normalized.startsWith("RGBA")
	val isGray = normalized.startsWith("GRAYA")
	require(isRgb || isGray) {
		"Unsupported KRA color model '$colorspaceName' - only RGBA and GRAYA are supported"
	}

	val depth =
		when {
			normalized.contains("F32") -> KraColorDepth.Float32
			normalized.contains("F16") ->
				error("Unsupported KRA color depth (half-float) '$colorspaceName'")

			normalized.contains("16") -> KraColorDepth.UnsignedShort
			else -> KraColorDepth.UnsignedByte
		}

	val format = KraPixelFormat(channelCount = if (isRgb) 4 else 2, depth = depth, isRgb = isRgb)
	require(format.pixelSize == tilePixelSize) {
		"KRA pixel-size mismatch: tile header says $tilePixelSize, '$colorspaceName' implies ${format.pixelSize}"
	}
	return format
}