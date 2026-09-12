package org.umamo.format.psd

import okio.Buffer
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.binary.encodeUtf16Be
import io.github.psd2live.core.PreviewRenderer
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Pure-Kotlin PSD serializer.
 *
 * Writes standard Adobe Photoshop PSD files (8-bit RGB, PackBits RLE channel compression,
 * folder hierarchies via lsct section divider pairs, Unicode layer names via luni blocks,
 * and a merged composite preview in Section 5).
 *
 * Fully compatible with Adobe Photoshop, Clip Studio Paint, PaintTool SAI, GIMP, Krita,
 * and [PsdReader].
 */
object PsdWriter {

	/**
	 * Encodes [bytes] using standard Macintosh / Photoshop PackBits run-length encoding.
	 */
	internal fun packBits(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
		if (length <= 0) return ByteArray(0)
		val out = Buffer()
		var i = offset
		val end = offset + length
		while (i < end) {
			var runLength = 1
			while (i + runLength < end && runLength < 128 && input[i + runLength] == input[i]) {
				runLength++
			}
			if (runLength >= 2) {
				out.writeByte(1 - runLength)
				out.writeByte(input[i].toInt() and 0xFF)
				i += runLength
			} else {
				var litLength = 1
				while (i + litLength < end && litLength < 128) {
					if (i + litLength + 2 < end &&
						input[i + litLength] == input[i + litLength + 1] &&
						input[i + litLength] == input[i + litLength + 2]
					) {
						break
					}
					litLength++
				}
				out.writeByte(litLength - 1)
				out.write(input, i, litLength)
				i += litLength
			}
		}
		return out.readByteArray()
	}

	internal fun LayerBlend.toBlendKey(): String = when (this) {
		LayerBlend.Normal -> "norm"
		LayerBlend.Darken -> "dark"
		LayerBlend.Multiply -> "mul "
		LayerBlend.ColorBurn -> "idiv"
		LayerBlend.LinearBurn -> "lbrn"
		LayerBlend.DarkerColor -> "dkCl"
		LayerBlend.Lighten -> "lite"
		LayerBlend.Screen -> "scrn"
		LayerBlend.ColorDodge, LayerBlend.GlowDodge -> "div "
		LayerBlend.Add, LayerBlend.AddGlow -> "lddg"
		LayerBlend.LighterColor -> "lgCl"
		LayerBlend.Overlay -> "over"
		LayerBlend.SoftLight -> "sLit"
		LayerBlend.HardLight -> "hLit"
		LayerBlend.VividLight -> "vLit"
		LayerBlend.LinearLight -> "lLit"
		LayerBlend.PinLight -> "pLit"
		LayerBlend.HardMix -> "hMix"
		LayerBlend.Difference -> "diff"
		LayerBlend.Exclusion -> "smud"
		LayerBlend.Subtract -> "fsub"
		LayerBlend.Divide -> "fdiv"
		LayerBlend.Hue -> "hue "
		LayerBlend.Saturation -> "sat "
		LayerBlend.Color -> "colr"
		LayerBlend.Luminosity -> "lum "
	}

	private data class EncodedChannel(val id: Int, val data: ByteArray)

	private sealed class PsdWriteRecord {
		abstract val name: String
		abstract val visible: Boolean
		abstract val opacity: Float
		abstract val blendKey: String
		abstract val clipped: Boolean
		abstract val dividerType: Int
		abstract val bounds: LayerBounds
		abstract val channels: List<EncodedChannel>
		abstract val layerId: Int?
	}

	private class NormalLayerRecord(
		override val name: String,
		override val visible: Boolean,
		override val opacity: Float,
		override val blendKey: String,
		override val clipped: Boolean,
		override val bounds: LayerBounds,
		override val channels: List<EncodedChannel>,
		override val layerId: Int?,
		val raster: LayerRaster,
	) : PsdWriteRecord() {
		override val dividerType: Int get() = 0
	}

	private class StructuralRecord(
		override val name: String,
		override val visible: Boolean,
		override val opacity: Float,
		override val blendKey: String,
		override val clipped: Boolean,
		override val dividerType: Int,
		override val channels: List<EncodedChannel>,
		override val layerId: Int? = null,
	) : PsdWriteRecord() {
		override val bounds: LayerBounds get() = LayerBounds(0, 0, 0, 0)
	}

	/**
	 * Serializes [source] to a complete `.psd` byte array at 1× resolution.
	 */
	fun write(source: SourceArt): ByteArray =
		write(source.widthPx, source.heightPx, source.layers, source.groups)

	/**
	 * Serializes layers and folder hierarchy to a complete `.psd` byte array.
	 *
	 * @param width Canvas width in unscaled units.
	 * @param height Canvas height in unscaled units.
	 * @param layers Layers in painter's (bottom-to-top) order.
	 * @param groups Folder hierarchy metadata.
	 * @param scale Integer scale factor (1, 2, or 4).
	 * @param upscaledTextures Optional map from layer ID to upscaled PNG file paths.
	 */
	fun write(
		width: Int,
		height: Int,
		layers: List<SourceLayer>,
		groups: List<SourceGroup> = emptyList(),
		scale: Int = 1,
		upscaledTextures: Map<String, Path> = emptyMap(),
	): ByteArray {
		require(scale in listOf(1, 2, 4)) { "Scale must be 1, 2, or 4" }
		val canvasWidth = width * scale
		val canvasHeight = height * scale
		require(canvasWidth in 1..30000 && canvasHeight in 1..30000) {
			"PSD canvas dimensions must be between 1 and 30,000 pixels (was ${canvasWidth}x${canvasHeight})"
		}

		// Empty/structural channel data: 4 channels (0, 1, 2, -1), 2 bytes each (RAW compression 0, 0 data).
		val emptyChannels = listOf(0, 1, 2, -1).map { chId ->
			val buf = Buffer()
			buf.writeShort(0)
			EncodedChannel(chId, buf.readByteArray())
		}

		fun encodeNormalChannels(raster: LayerRaster): List<EncodedChannel> {
			val w = raster.width
			val h = raster.height
			if (w <= 0 || h <= 0) return emptyChannels
			val rgba = raster.rgba
			val channelConfigs = listOf(0 to 0, 1 to 1, 2 to 2, -1 to 3) // id to RGBA offset
			return channelConfigs.map { (chId, offset) ->
				val rowCounts = IntArray(h)
				val packedRows = ArrayList<ByteArray>(h)
				for (y in 0 until h) {
					val row = ByteArray(w)
					val base = y * w * 4 + offset
					for (x in 0 until w) {
						row[x] = rgba[base + x * 4]
					}
					val packed = packBits(row)
					packedRows += packed
					rowCounts[y] = packed.size
				}
				val chBuf = Buffer()
				chBuf.writeShort(1) // compression 1 = RLE
				for (count in rowCounts) {
					chBuf.writeShort(count)
				}
				for (packed in packedRows) {
					chBuf.write(packed)
				}
				EncodedChannel(chId, chBuf.readByteArray())
			}
		}

		fun resolveRaster(layer: SourceLayer): LayerRaster {
			if (scale == 1) return layer.raster
			val upscaledPath = upscaledTextures[layer.id.raw]
			if (upscaledPath != null && Files.isRegularFile(upscaledPath)) {
				val img = ImageIO.read(upscaledPath.toFile())
				if (img != null && img.width == layer.raster.width * scale && img.height == layer.raster.height * scale) {
					val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
					val rgba = ByteArray(img.width * img.height * 4)
					for (i in argb.indices) {
						val p = argb[i]
						rgba[i * 4] = ((p ushr 16) and 0xFF).toByte()
						rgba[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
						rgba[i * 4 + 2] = (p and 0xFF).toByte()
						rgba[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
					}
					return LayerRaster(img.width, img.height, rgba)
				}
			}
			// Bilinear fallback for layers without explicit neural upscale PNG
			val srcW = layer.raster.width
			val srcH = layer.raster.height
			if (srcW <= 0 || srcH <= 0) return LayerRaster(0, 0, ByteArray(0))
			val dstW = srcW * scale
			val dstH = srcH * scale
			val srcImg = PreviewRenderer.rasterImage(srcW, srcH, layer.raster.rgba)
			val dstImg = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
			val g = dstImg.createGraphics()
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
			g.drawImage(srcImg, 0, 0, dstW, dstH, null)
			g.dispose()
			val argb = dstImg.getRGB(0, 0, dstW, dstH, null, 0, dstW)
			val rgba = ByteArray(dstW * dstH * 4)
			for (i in argb.indices) {
				val p = argb[i]
				rgba[i * 4] = ((p ushr 16) and 0xFF).toByte()
				rgba[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte()
				rgba[i * 4 + 2] = (p and 0xFF).toByte()
				rgba[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte()
			}
			return LayerRaster(dstW, dstH, rgba)
		}

		fun parseLayerId(raw: String): Int? {
			if (raw.startsWith("lyid:")) return raw.removePrefix("lyid:").toIntOrNull()
			return null
		}

		// Construct records in top-to-bottom order, then reverse to bottom-to-top for PSD storage.
		val recordsTopToBottom = ArrayList<PsdWriteRecord>()
		val folderStack = ArrayDeque<String>()
		val layersTopToBottom = layers.asReversed()
		val ids = PsdLayerIds(layers.mapNotNull { parseLayerId(it.id.raw) })

		for (layer in layersTopToBottom) {
			val targetFolders = if (layer.groupPath.isBlank()) emptyList() else layer.groupPath.split('/')
			var commonPrefix = 0
			while (commonPrefix < folderStack.size && commonPrefix < targetFolders.size && folderStack.elementAt(commonPrefix) == targetFolders[commonPrefix]) {
				commonPrefix++
			}
			while (folderStack.size > commonPrefix) {
				folderStack.removeLast()
				recordsTopToBottom += StructuralRecord(
					name = "</Layer group>",
					visible = true,
					opacity = 1f,
					blendKey = "norm",
					clipped = false,
					dividerType = 3,
					channels = emptyChannels,
				)
			}
			for (i in commonPrefix until targetFolders.size) {
				val folderName = targetFolders[i]
				folderStack.addLast(folderName)
				val currentPath = folderStack.joinToString("/")
				val group = groups.firstOrNull { it.path == currentPath }
				val passThrough = group?.passThrough ?: true
				val blendKey = if (passThrough) "pass" else group.blend.toBlendKey()
				recordsTopToBottom += StructuralRecord(
					name = folderName,
					visible = group?.visible ?: true,
					opacity = group?.opacity ?: 1f,
					blendKey = blendKey,
					clipped = group?.clipped ?: false,
					dividerType = 1,
					channels = emptyChannels,
				)
			}

			val scaledRaster = resolveRaster(layer)
			val scaledBounds = LayerBounds(
				left = layer.bounds.left * scale,
				top = layer.bounds.top * scale,
				width = scaledRaster.width,
				height = scaledRaster.height,
			)
			val channels = encodeNormalChannels(scaledRaster)
			val id = ids.allocate(parseLayerId(layer.id.raw))
			recordsTopToBottom += NormalLayerRecord(
				name = layer.name,
				visible = layer.visible,
				opacity = layer.opacity,
				blendKey = layer.blend.toBlendKey(),
				clipped = layer.clipped,
				bounds = scaledBounds,
				channels = channels,
				layerId = id,
				raster = scaledRaster,
			)
		}

		while (folderStack.isNotEmpty()) {
			folderStack.removeLast()
			recordsTopToBottom += StructuralRecord(
				name = "</Layer group>",
				visible = true,
				opacity = 1f,
				blendKey = "norm",
				clipped = false,
				dividerType = 3,
				channels = emptyChannels,
			)
		}

		val records = recordsTopToBottom.asReversed()

		// Encode Layer Records and Additional Layer Info
		val recordBytesList = ArrayList<ByteArray>(records.size)
		for (record in records) {
			val addInfoBuf = Buffer()

			// luni (Unicode layer name)
			val luniBytes = encodeUtf16Be(record.name)
			addInfoBuf.writeUtf8("8BIM")
			addInfoBuf.writeUtf8("luni")
			addInfoBuf.writeInt(4 + luniBytes.size)
			addInfoBuf.writeInt(record.name.length)
			addInfoBuf.write(luniBytes)

			// lsct (Section Divider Setting for folders)
			if (record.dividerType != 0) {
				addInfoBuf.writeUtf8("8BIM")
				addInfoBuf.writeUtf8("lsct")
				addInfoBuf.writeInt(4)
				addInfoBuf.writeInt(record.dividerType)
			}

			// lyid (Layer ID)
			if (record.layerId != null) {
				addInfoBuf.writeUtf8("8BIM")
				addInfoBuf.writeUtf8("lyid")
				addInfoBuf.writeInt(4)
				addInfoBuf.writeInt(record.layerId!!)
			}

			val additionalInfoBytes = addInfoBuf.readByteArray()

			val nameBytes = record.name.encodeToByteArray().let { if (it.size > 255) it.copyOf(255) else it }
			val namePad = (4 - ((1 + nameBytes.size) % 4)) % 4

			val extraBuf = Buffer()
			extraBuf.writeInt(0) // layer mask length = 0
			extraBuf.writeInt(0) // blending ranges length = 0
			extraBuf.writeByte(nameBytes.size)
			extraBuf.write(nameBytes)
			if (namePad > 0) extraBuf.write(ByteArray(namePad))
			extraBuf.write(additionalInfoBytes)
			val extraBytes = extraBuf.readByteArray()

			val recBuf = Buffer()
			val b = record.bounds
			recBuf.writeInt(b.top)
			recBuf.writeInt(b.left)
			recBuf.writeInt(b.top + b.height)
			recBuf.writeInt(b.left + b.width)
			recBuf.writeShort(record.channels.size)
			for (ch in record.channels) {
				recBuf.writeShort(ch.id)
				recBuf.writeInt(ch.data.size)
			}
			recBuf.writeUtf8("8BIM")
			recBuf.writeUtf8(record.blendKey)
			recBuf.writeByte((record.opacity * 255f).roundToInt().coerceIn(0, 255))
			recBuf.writeByte(if (record.clipped) 1 else 0)
			recBuf.writeByte(if (record.visible) 0x00 else 0x02) // bit 1 set = hidden
			recBuf.writeByte(0) // filler
			recBuf.writeInt(extraBytes.size)
			recBuf.write(extraBytes)
			recordBytesList += recBuf.readByteArray()
		}

		// Layer Info Section: layerCount, layer records, channel image data
		val layerInfoBuf = Buffer()
		layerInfoBuf.writeShort(records.size)
		for (recBytes in recordBytesList) {
			layerInfoBuf.write(recBytes)
		}
		for (record in records) {
			for (ch in record.channels) {
				layerInfoBuf.write(ch.data)
			}
		}
		if (layerInfoBuf.size % 2 != 0L) {
			layerInfoBuf.writeByte(0)
		}
		val layerInfoBytes = layerInfoBuf.readByteArray()

		// Layer and Mask Information Section
		val lmBuf = Buffer()
		lmBuf.writeInt(layerInfoBytes.size)
		lmBuf.write(layerInfoBytes)
		lmBuf.writeInt(0) // Global layer mask length = 0
		val lmBytes = lmBuf.readByteArray()

		// Section 5: Merged Composite Image (RGB, 3 channels)
		val composite = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB)
		val g = composite.createGraphics()
		g.color = Color.WHITE
		g.fillRect(0, 0, canvasWidth, canvasHeight)
		for (record in records) {
			if (record is NormalLayerRecord && record.visible && record.raster.width > 0 && record.raster.height > 0) {
				val img = PreviewRenderer.rasterImage(record.raster.width, record.raster.height, record.raster.rgba)
				g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, record.opacity.coerceIn(0f, 1f))
				g.drawImage(img, record.bounds.left, record.bounds.top, null)
			}
		}
		g.dispose()

		val rgbPixels = composite.getRGB(0, 0, canvasWidth, canvasHeight, null, 0, canvasWidth)
		val rPlane = ByteArray(canvasWidth * canvasHeight)
		val gPlane = ByteArray(canvasWidth * canvasHeight)
		val bPlane = ByteArray(canvasWidth * canvasHeight)
		for (i in rgbPixels.indices) {
			val p = rgbPixels[i]
			rPlane[i] = ((p ushr 16) and 0xFF).toByte()
			gPlane[i] = ((p ushr 8) and 0xFF).toByte()
			bPlane[i] = (p and 0xFF).toByte()
		}

		val planes = listOf(rPlane, gPlane, bPlane)
		val planePackedRows = planes.map { plane ->
			(0 until canvasHeight).map { y ->
				packBits(plane, y * canvasWidth, canvasWidth)
			}
		}

		val s5Buf = Buffer()
		s5Buf.writeShort(1) // compression 1 = RLE
		for (packedRows in planePackedRows) {
			for (row in packedRows) {
				s5Buf.writeShort(row.size)
			}
		}
		for (packedRows in planePackedRows) {
			for (row in packedRows) {
				s5Buf.write(row)
			}
		}
		val section5Bytes = s5Buf.readByteArray()

		// Assemble complete PSD file
		val fileBuf = Buffer()
		fileBuf.writeUtf8("8BPS")
		fileBuf.writeShort(1) // version 1 (PSD)
		fileBuf.write(ByteArray(6)) // reserved
		fileBuf.writeShort(3) // channels = 3 (RGB)
		fileBuf.writeInt(canvasHeight)
		fileBuf.writeInt(canvasWidth)
		fileBuf.writeShort(8) // depth = 8
		fileBuf.writeShort(3) // mode = RGB
		fileBuf.writeInt(0) // Color Mode Data length = 0
		fileBuf.writeInt(0) // Image Resources length = 0
		fileBuf.writeInt(lmBytes.size)
		fileBuf.write(lmBytes)
		fileBuf.write(section5Bytes)

		return fileBuf.readByteArray()
	}
}
