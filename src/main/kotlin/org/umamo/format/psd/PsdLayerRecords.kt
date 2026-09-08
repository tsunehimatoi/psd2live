package org.umamo.format.psd

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.binary.ByteReader
import org.umamo.format.binary.decodeLegacyPsdText
import org.umamo.format.binary.decodeUtf16Be
import kotlin.math.abs

/**
 * The PSD file header plus the bits of the file structure the channel decoder needs.
 *
 * EN: Read from the 26-byte File Header.  Canvas size and the per-layer pixel geometry both derive
 *     from depth and color mode, so they are surfaced here rather than guessed downstream.
 * JA: PSD ファイルヘッダ（26 バイト）。キャンバスサイズと深度・カラーモードを公開する。
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md §1 File Header</a>
 */
internal data class PsdHeaderInfo(
	val width: Int,
	val height: Int,
	// PSD: number of color channels including alpha (1..56).
	val channels: Int,
	// PSD: bits per channel - 1, 8, 16, or 32.
	val depth: Int,
	// PSD: color mode - 0=Bitmap, 1=Grayscale, 2=Indexed, 3=RGB, 4=CMYK, 7=Multichannel, 8=Duotone, 9=Lab.
	val colorMode: Int,
)

/**
 * One entry of a layer's channel-info table: which channel it is and how many bytes its image-data
 * block occupies (including the leading 2-byte compression code).
 *
 * PSD: channel id 0/1/2… are color channels (R/G/B for RGB, gray for grayscale, the palette index
 * for indexed); -1 is the transparency (alpha) channel; -2/-3 are user/vector layer masks (no
 * displayable color).
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md §4 Channel image data</a>
 */
internal data class PsdChannelInfo(
	val id: Int,
	val length: Long,
)

/**
 * Per-layer metadata read from a PSD's "Layer and Mask Information" section.
 *
 * We parse this ourselves rather than leaning on a library that keeps its layer structures private:
 * the record headers that carry bounds/name/opacity/clipping/blend and the channel-info table are
 * trivially and reliably walked here.  [channelDataOffset] is the absolute file offset of this
 * layer's channel image data, computed once all records are parsed (the channel data of every layer
 * is stored sequentially after the records), so [PsdRaster] can seek straight to a layer's pixels.
 *
 * PSD のレイヤーレコードから取り出すメタデータ。チャンネルデータの絶対オフセットも保持する。
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md §3 Layer records</a>
 */
internal data class PsdLayerRecord(
	val name: String,
	val bounds: LayerBounds,
	val opacity: Float,
	val clipped: Boolean,
	val blend: LayerBlend,
	val visible: Boolean,
	// PSD: section-divider kind from the lsct block - 0 = normal layer, 1 = open folder header,
	// 2 = closed folder header, 3 = bounding divider (the hidden "</Layer group>" group end).
	val dividerType: Int,
	// PSD: true for a pass-through folder (blend mode key "pass"); only meaningful on folder records.
	val passThrough: Boolean,
	// PSD: the layer's Photoshop id from the lyid additional-layer-info block - stable across rename
	// and reorder for the life of the layer; null when the writer omits lyid (some exporters do), in
	// which case the reader falls back to a name+order identity.
	val layerId: Int?,
	// PSD: this layer's channels, in storage order; their lengths drive the channel-data layout.
	val channels: List<PsdChannelInfo>,
	// Absolute file offset of this layer's channel image data (assigned after all records are read).
	val channelDataOffset: Int,
)

/**
 * The whole of a PSD relevant to art ingestion: the header, the (optional) indexed palette, and the
 * per-layer records with their channel-data offsets.
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md</a>
 */
internal data class PsdParse(
	val header: PsdHeaderInfo,
	// PSD: Color Mode Data section bytes - the 768-byte (256 x RGB) palette for indexed-color files,
	// non-interleaved (all reds, then all greens, then all blues).  Empty for every other color mode.
	val colorModeData: ByteArray,
	val records: List<PsdLayerRecord>,
)

internal object PsdLayerRecords {
	/**
	 * Reads the header, color-mode data, and every layer record, then assigns each record the
	 * absolute offset of its channel image data.  Records are returned in PSD storage order
	 * (bottom-most layer first), matching the order the channel data is stored in.
	 *
	 * @param ByteArray bytes The complete `.psd` file.
	 * @return PsdParse The header, palette, and per-layer records with channel-data offsets.
	 */
	fun parse(bytes: ByteArray): PsdParse {
		// ByteReader defaults to big-endian, which is exactly PSD's byte order - no manual swaps.
		val buffer = ByteReader(bytes)

		// PSD: File Header - signature '8BPS', version, reserved(6), channels, height, width, depth, mode.
		val signature = buffer.readAscii(4)
		require(signature == "8BPS") { "not a PSD file (signature='$signature')" }
		val version = buffer.readU16()
		// PSD: version 1 = PSD, version 2 = PSB (large document).  PSB widens the channel-length and
		// section-length fields to 64-bit, which this parser does not handle, so reject it cleanly.
		require(version == 1) { "PSB (PSD version 2) is not supported; only version 1" }
		buffer.position = 12 // skip the 6 reserved bytes after the version
		val channels = buffer.readU16()
		val height = buffer.readU32AsInt()
		val width = buffer.readU32AsInt()
		val depth = buffer.readU16()
		val colorMode = buffer.readU16()
		val header = PsdHeaderInfo(width = width, height = height, channels = channels, depth = depth, colorMode = colorMode)

		// PSD: Color Mode Data section (u32 length + data) - holds the indexed palette; empty otherwise.
		val colorModeLength = buffer.readU32AsInt()
		val colorModeData = buffer.readBytes(colorModeLength)

		skipLengthPrefixed(buffer) // PSD: Image Resources section (u32 length + data) - not needed here

		buffer.readU32AsInt() // PSD: Layer and Mask Information section length (unused here)
		buffer.readU32AsInt() // PSD: Layer Info length (unused here)

		// PSD: layer count, signed i16; a negative value means the first alpha channel holds the
		// merged transparency - irrelevant to record parsing, so take the magnitude.
		val layerCount = abs(buffer.readI16())

		val records = ArrayList<PsdLayerRecord>(layerCount)
		repeat(layerCount) {
			// PSD: layer rectangle, top/left/bottom/right as i32 (top-left origin).
			val top = buffer.readU32AsInt()
			val left = buffer.readU32AsInt()
			val bottom = buffer.readU32AsInt()
			val right = buffer.readU32AsInt()

			// PSD: channel count (u16), then that many channel-info records of (id i16, length u32).
			val channelCount = buffer.readU16()
			val channelInfos = ArrayList<PsdChannelInfo>(channelCount)
			repeat(channelCount) {
				val channelId = buffer.readI16() // signed: 0/1/2… color, -1 alpha, -2/-3 masks
				val channelLength = buffer.readU32() // u32 byte length of the data block
				channelInfos += PsdChannelInfo(id = channelId, length = channelLength)
			}

			val blendSignature = buffer.readAscii(4) // PSD: must be '8BIM'
			require(blendSignature == "8BIM") { "bad layer blend signature '$blendSignature'" }
			val blendKey = buffer.readAscii(4) // PSD: 4-char blend mode key, e.g. 'norm'
			val opacityByte = buffer.readU8() // PSD: opacity 0..255
			val clipping = buffer.readU8() // PSD: clipping, 0=base, 1=non-base (clipped)
			// PSD: layer-record flags @ +0x0B; bit 1 (0x02) set means the layer is hidden (eye off).
			val flags = buffer.readU8()
			buffer.readU8() // PSD: filler (unused)

			// PSD: extra data length (u32) bounds the mask + ranges + name + any additional info.
			val extraLength = buffer.readU32AsInt()
			val extraDataStart = buffer.position
			val extraDataEnd = extraDataStart + extraLength
			// PSD: folder membership is carried by lsct (Section Divider Setting) in the additional
			// layer info, scanned out of the whole extra region before we consume mask/ranges/name.
			val dividerType = findAdditionalInfoInt(buffer, extraDataStart, extraDataEnd, "lsct") ?: 0
			// PSD: the lyid block carries Photoshop's stable per-layer id (absent when the writer omits it).
			val layerId = findAdditionalInfoInt(buffer, extraDataStart, extraDataEnd, "lyid")
			// PSD: the luni block carries the definitive UTF-16BE Unicode layer name (Photoshop 5.0+).
			val unicodeName = findAdditionalInfoUnicodeString(buffer, extraDataStart, extraDataEnd, "luni")
			skipLengthPrefixed(buffer) // PSD: layer mask / adjustment data
			skipLengthPrefixed(buffer) // PSD: layer blending ranges
			val nameLength = buffer.readU8() // PSD: legacy Pascal-string name length
			val legacyName = decodeLegacyPsdText(buffer.readBytes(nameLength))
			val name = unicodeName?.takeIf { it.isNotBlank() } ?: legacyName
			buffer.position = extraDataEnd // skip past any additional layer-info blocks we don't read

			records +=
				PsdLayerRecord(
					name = name,
					bounds = LayerBounds(left = left, top = top, width = right - left, height = bottom - top),
					opacity = opacityByte / 255f,
					clipped = clipping != 0,
					blend = blendKeyToBlend(blendKey),
					visible = (flags and 0x02) == 0,
					dividerType = dividerType,
					// PSD: a folder header's blend mode key is "pass" exactly when the folder is pass-through.
					passThrough = blendKey == "pass",
					layerId = layerId,
					channels = channelInfos,
					channelDataOffset = 0, // placeholder; filled in below once the data start is known
				)
		}

		// PSD: the channel image data of every layer follows all the records, stored sequentially in
		// the same order.  The cursor now sits at the first layer's channel data, so walk the records
		// accumulating channel-block lengths to give each layer its own absolute data offset.
		var channelDataCursor = buffer.position.toLong()
		val withOffsets =
			records.map { record ->
				val recordOffset = channelDataCursor.toInt()
				channelDataCursor += record.channels.sumOf { channel -> channel.length }
				record.copy(channelDataOffset = recordOffset)
			}

		return PsdParse(header = header, colorModeData = colorModeData, records = withOffsets)
	}

	/**
	 * Scans a layer record's extra-data region for an additional-layer-info block with the given
	 * 4-char [key] and returns the u32 right after the block's length field, or null when the key is
	 * absent.  Used for both lsct (the Section Divider Setting / folder type) and lyid (the stable
	 * Photoshop layer id).
	 *
	 * The additional layer info is a run of blocks, each a 4-char signature ('8BIM' or '8B64'), a
	 * 4-char key, a u32 length, then that many data bytes - but the data padding varies by writer, so
	 * rather than walk block lengths we scan for the key behind a valid signature (the 8-byte
	 * signature+key prefix makes a false match in mask/range/name bytes effectively impossible). All
	 * reads here are absolute so the caller's cursor is left untouched.
	 *
	 * @param ByteReader buffer      The whole-file reader.
	 * @param Int        regionStart First byte of the extra-data region.
	 * @param Int        regionEnd   One past the last byte of the extra-data region.
	 * @param String     key         The 4-char block key to find (e.g. "lsct", "lyid").
	 * @return Int the u32 after the matched block's length field, or null when [key] is absent.
	 */
	private fun findAdditionalInfoInt(buffer: ByteReader, regionStart: Int, regionEnd: Int, key: String): Int? {
		var scanPosition = regionStart
		// Need 16 bytes available: signature(4) + key(4) + length(4) + value(4).
		while (scanPosition + 16 <= regionEnd) {
			val signature = buffer.asciiAt(scanPosition, 4)
			if ((signature == "8BIM" || signature == "8B64") && buffer.asciiAt(scanPosition + 4, 4) == key) {
				return buffer.u32AsInt(scanPosition + 12) // the u32 right after the block's length field
			}
			scanPosition++
		}
		return null
	}

	/**
	 * Scans a layer record's extra-data region for an additional-layer-info block with the given
	 * 4-char [key] and returns its UTF-16BE decoded string payload, or null when the key is absent.
	 * Used for `luni` (the Unicode layer name block introduced in Photoshop 5.0).
	 *
	 * @param ByteReader buffer      The whole-file reader.
	 * @param Int        regionStart First byte of the extra-data region.
	 * @param Int        regionEnd   One past the last byte of the extra-data region.
	 * @param String     key         The 4-char block key to find (e.g. "luni").
	 * @return String the decoded UTF-16BE string, or null when absent or malformed.
	 */
	private fun findAdditionalInfoUnicodeString(buffer: ByteReader, regionStart: Int, regionEnd: Int, key: String): String? {
		var scanPosition = regionStart
		// Need at least 16 bytes: signature(4) + key(4) + length(4) + charCount(4).
		while (scanPosition + 16 <= regionEnd) {
			val signature = buffer.asciiAt(scanPosition, 4)
			if ((signature == "8BIM" || signature == "8B64") && buffer.asciiAt(scanPosition + 4, 4) == key) {
				val blockLength = buffer.u32(scanPosition + 8)
				val charCount = buffer.u32(scanPosition + 12)
				val byteLength = charCount * 2
				if (byteLength in 0..Int.MAX_VALUE.toLong() &&
					scanPosition + 16 + byteLength <= regionEnd &&
					4 + byteLength <= blockLength + 4
				) {
					val str = decodeUtf16Be(buffer.bytes, scanPosition + 16, byteLength.toInt()).trimEnd('\u0000')
					if (str.isNotBlank()) return str
				}
			}
			scanPosition++
		}
		return null
	}

	/**
	 * Reads a u32-length-prefixed block and advances the cursor past its payload.
	 *
	 * @param ByteReader buffer Positioned at the 4-byte length field.
	 */
	private fun skipLengthPrefixed(buffer: ByteReader) {
		val length = buffer.readU32AsInt()
		buffer.position += length
	}

	/**
	 * Maps a PSD 4-char blend-mode key to our [LayerBlend].
	 *
	 * The keys are the space-padded 4-char codes Photoshop writes (PSD.java BLEND_*).  Keys with no
	 * neutral-model equivalent (Dissolve) and any unknown key degrade to [LayerBlend.Normal]; "pass"
	 * (folder pass-through) is handled separately by the caller and also reads as Normal here.
	 *
	 * @param String key The 4-character blend mode key (e.g. `"norm"`, `"mul "`).
	 * @return LayerBlend the matching mode, or Normal.
	 */
	private fun blendKeyToBlend(key: String): LayerBlend =
		// PSD: blend mode keys (Adobe Photoshop File Format, "Blend mode key"); see PSD.java BLEND_*.
		when (key) {
			"norm" -> LayerBlend.Normal
			"dark" -> LayerBlend.Darken
			"mul " -> LayerBlend.Multiply
			"idiv" -> LayerBlend.ColorBurn // PSD "idiv" = Color Burn
			"lbrn" -> LayerBlend.LinearBurn
			"dkCl" -> LayerBlend.DarkerColor
			"lite" -> LayerBlend.Lighten
			"scrn" -> LayerBlend.Screen
			"div " -> LayerBlend.ColorDodge
			"lddg" -> LayerBlend.Add // linear dodge (add)
			"lgCl" -> LayerBlend.LighterColor
			"over" -> LayerBlend.Overlay
			"sLit" -> LayerBlend.SoftLight
			"hLit" -> LayerBlend.HardLight
			"vLit" -> LayerBlend.VividLight
			"lLit" -> LayerBlend.LinearLight
			"pLit" -> LayerBlend.PinLight
			"hMix" -> LayerBlend.HardMix
			"diff" -> LayerBlend.Difference
			"smud" -> LayerBlend.Exclusion // PSD "smud" = Exclusion
			"fsub" -> LayerBlend.Subtract
			"fdiv" -> LayerBlend.Divide
			"hue " -> LayerBlend.Hue
			"sat " -> LayerBlend.Saturation
			"colr" -> LayerBlend.Color
			"lum " -> LayerBlend.Luminosity
			else -> LayerBlend.Normal
		}
}