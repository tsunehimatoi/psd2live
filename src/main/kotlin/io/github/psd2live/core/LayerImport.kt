package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceLayer
import org.umamo.format.FileKind
import org.umamo.format.FormatRegistry
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.raster.RasterCodec
import org.umamo.format.raster.RasterImage
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Helpers for importing flat transparent rasters (PNG / WebP / …) as source layers.
 */
internal object LayerImport {
	private val TRANSPARENT_KINDS = setOf(
		FileKind.Png,
		FileKind.WebP,
		FileKind.Tiff,
		FileKind.Bmp,
	)

	private val TRANSPARENT_EXTENSIONS = setOf("png", "webp", "tif", "tiff", "bmp")

	fun isTransparentRasterFile(file: File): Boolean {
		if (!file.isFile) return false
		if (file.extension.lowercase() in TRANSPARENT_EXTENSIONS) return true
		return runCatching {
			val head = file.inputStream().use { stream ->
				stream.readNBytes(64)
			}
			val kind = FormatRegistry.detect(head, file.name)?.kind
			kind in TRANSPARENT_KINDS
		}.getOrDefault(false)
	}

	fun transparentRasterFiles(files: List<File>): List<File> =
		files.filter(::isTransparentRasterFile)

	fun decodeRasterFile(file: File): RasterImage {
		val bytes = file.readBytes()
		val codec = FormatRegistry.detect(bytes, file.name) as? RasterCodec
			?: error("Unsupported raster format: ${file.name}")
		require(codec.kind in TRANSPARENT_KINDS) { "Not a transparent raster: ${file.name}" }
		return codec.read(bytes)
	}

	/**
	 * Trims fully-transparent margins and places the content on the document canvas,
	 * centred, scaled down only when larger than the canvas.
	 */
	fun placedLayer(
		image: RasterImage,
		canvasWidth: Int,
		canvasHeight: Int,
		name: String,
		layerId: String = "import:${UUID.randomUUID()}",
		order: Int = 0,
	): WorkspaceSourceLayer {
		val trimmed = trimTransparent(image)
			?: error("Image is fully transparent: $name")
		val (bounds, raster) = fitToCanvas(trimmed, canvasWidth, canvasHeight)
		return WorkspaceSourceLayer(
			id = LayerId(layerId),
			name = name,
			groupPath = "",
			kind = SourceLayerKind.Raster,
			visible = true,
			order = order,
			bounds = bounds,
			opacity = 1f,
			clipped = false,
			blend = LayerBlend.Normal,
			channelMask = ChannelMask.ALL,
			raster = raster,
			sourceAssetId = null,
			sourceSpatialReferenceId = null,
			derived = true,
		)
	}

	fun displayNameOf(file: File): String = file.nameWithoutExtension.ifBlank { file.name }

	private fun trimTransparent(image: RasterImage): RasterImage? {
		var minX = image.width
		var minY = image.height
		var maxX = -1
		var maxY = -1
		val rgba = image.rgba
		for (y in 0 until image.height) {
			val row = y * image.width
			for (x in 0 until image.width) {
				if ((rgba[(row + x) * 4 + 3].toInt() and 0xFF) == 0) continue
				minX = min(minX, x)
				minY = min(minY, y)
				maxX = max(maxX, x)
				maxY = max(maxY, y)
			}
		}
		if (maxX < minX || maxY < minY) return null
		val width = maxX - minX + 1
		val height = maxY - minY + 1
		if (width == image.width && height == image.height && minX == 0 && minY == 0) {
			return image
		}
		val cropped = ByteArray(width * height * 4)
		for (y in 0 until height) {
			val src = ((minY + y) * image.width + minX) * 4
			val dst = y * width * 4
			System.arraycopy(rgba, src, cropped, dst, width * 4)
		}
		return RasterImage(width, height, cropped)
	}

	private fun fitToCanvas(
		image: RasterImage,
		canvasWidth: Int,
		canvasHeight: Int,
	): Pair<LayerBounds, LayerRaster> {
		val maxW = canvasWidth.coerceAtLeast(1)
		val maxH = canvasHeight.coerceAtLeast(1)
		val scale = min(1f, min(maxW.toFloat() / image.width, maxH.toFloat() / image.height))
		val width = max(1, (image.width * scale).roundToInt())
		val height = max(1, (image.height * scale).roundToInt())
		val left = ((maxW - width) / 2).coerceAtLeast(0)
		val top = ((maxH - height) / 2).coerceAtLeast(0)
		val raster = if (width == image.width && height == image.height) {
			LayerRaster(width, height, image.rgba)
		} else {
			LayerRaster(width, height, scaleRgba(image, width, height))
		}
		return LayerBounds(left, top, width, height) to raster
	}

	/** Nearest-neighbour scale — fine for UI placement before the artist confirms size. */
	private fun scaleRgba(image: RasterImage, width: Int, height: Int): ByteArray {
		val out = ByteArray(width * height * 4)
		for (y in 0 until height) {
			val srcY = (y * image.height) / height
			for (x in 0 until width) {
				val srcX = (x * image.width) / width
				val src = (srcY * image.width + srcX) * 4
				val dst = (y * width + x) * 4
				out[dst] = image.rgba[src]
				out[dst + 1] = image.rgba[src + 1]
				out[dst + 2] = image.rgba[src + 2]
				out[dst + 3] = image.rgba[src + 3]
			}
		}
		return out
	}
}

/** Hierarchy drop destination resolved under the cursor. */
data class HierarchyImportTarget(
	/** Parent deformer id, or null for model root. */
	val parentDeformerId: String?,
	val label: String,
)
