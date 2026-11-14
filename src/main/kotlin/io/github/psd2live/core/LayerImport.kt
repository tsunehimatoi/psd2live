package io.github.psd2live.core

import io.github.psd2live.document.LayerCanvasRect
import io.github.psd2live.document.WorkspaceSourceLayer
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.umamo.format.tiff.parseFirstDirectory
import org.umamo.format.webp.parseVp8lHeader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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

	fun decodeRasterFile(file: File, checkCancelled: () -> Unit = {}): RasterImage {
		checkCancelled()
		require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "Image must be a readable file of at most 64 MiB" }
		val bytes = file.inputStream().use { input ->
			val output = java.io.ByteArrayOutputStream()
			val buffer = ByteArray(8192)
			while (true) {
				checkCancelled()
				val count = input.read(buffer)
				if (count < 0) break
				require(output.size().toLong() + count <= MAX_BYTES) { "Image exceeds 64 MiB" }
				output.write(buffer, 0, count)
			}
			output.toByteArray()
		}
		val codec = FormatRegistry.detect(bytes, file.name) as? RasterCodec
			?: error("Unsupported raster format: ${file.name}")
		require(codec.kind in TRANSPARENT_KINDS) { "Not a transparent raster: ${file.name}" }
		val (width, height) = when (codec.kind) {
			FileKind.Png -> {
				require(bytes.size >= 24 && bytes.copyOfRange(12, 16).decodeToString() == "IHDR") { "Invalid PNG header" }
				ByteBuffer.wrap(bytes).let { it.getInt(16).toLong() to it.getInt(20).toLong() }
			}
			FileKind.Bmp -> {
				require(bytes.size >= 54) { "Invalid BMP header" }
				ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { it.getInt(18).toLong() to kotlin.math.abs(it.getInt(22).toLong()) }
			}
			FileKind.WebP -> parseVp8lHeader(bytes).let { it.width.toLong() to it.height.toLong() }
			FileKind.Tiff -> parseFirstDirectory(bytes).let { it.int(256, -1).toLong() to it.int(257, -1).toLong() }
			else -> error("Unsupported raster format")
		}
		require(width > 0 && height > 0 && width <= MAX_PIXELS && height <= MAX_PIXELS && width * height <= MAX_PIXELS) { "Image exceeds 16 megapixels" }
		checkCancelled()
		return codec.read(bytes).also { checkCancelled() }
	}

	private const val MAX_BYTES = 67_108_864
	private const val MAX_PIXELS = LayerSizeBudget.MAX_PIXELS

	/**
	 * Trims fully-transparent margins and places the content on the document canvas, centred.
	 *
	 * The raster keeps every source pixel. An image larger than the canvas is placed fitted: its canvas
	 * rectangle shrinks while the raster stays at its native resolution, so the layer's density
	 * ([LayerSpace]) exceeds one pixel per canvas unit. An image that fits keeps one pixel per canvas unit
	 * on whole-unit bounds, exactly as before.
	 */
	fun placedLayer(
		image: RasterImage,
		canvasWidth: Int,
		canvasHeight: Int,
		name: String,
		layerId: String = StableIds.stem("import:", name, image.width, image.height),
		order: Int = 0,
		checkCancelled: () -> Unit = {},
		centre: Pair<Float, Float>? = null,
	): WorkspaceSourceLayer {
		val trimmed = trimTransparent(image, checkCancelled)
			?: error("Image is fully transparent: $name")
		val raster = LayerRaster(trimmed.width, trimmed.height, trimmed.rgba)
		val fitted = fittedRect(trimmed.width, trimmed.height, canvasWidth, canvasHeight)
		val rect = centre?.let { centred(fitted, it, canvasWidth, canvasHeight) } ?: fitted
		LayerSizeBudget.require(raster.width, raster.height, rect)
		val (bounds, stored) = LayerSizeBudget.enclosing(rect)
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
			rect = stored,
		)
	}

	fun displayNameOf(file: File): String = file.nameWithoutExtension.ifBlank { file.name }

	private fun trimTransparent(image: RasterImage, checkCancelled: () -> Unit): RasterImage? {
		var minX = image.width
		var minY = image.height
		var maxX = -1
		var maxY = -1
		val rgba = image.rgba
		for (y in 0 until image.height) {
			checkCancelled()
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
			checkCancelled()
			val src = ((minY + y) * image.width + minX) * 4
			val dst = y * width * 4
			System.arraycopy(rgba, src, cropped, dst, width * 4)
		}
		return RasterImage(width, height, cropped)
	}

	/**
	 * The canvas rectangle of a [width] x [height] raster centred on the canvas: one canvas unit per pixel on
	 * whole-unit bounds when it fits, else scaled down uniformly to fit (never up).
	 */
	internal fun fittedRect(width: Int, height: Int, canvasWidth: Int, canvasHeight: Int): LayerCanvasRect {
		val maxW = canvasWidth.coerceAtLeast(1)
		val maxH = canvasHeight.coerceAtLeast(1)
		if (width <= maxW && height <= maxH) {
			return LayerCanvasRect(((maxW - width) / 2).toFloat(), ((maxH - height) / 2).toFloat(), width.toFloat(), height.toFloat())
		}
		val scale = min(maxW.toDouble() / width, maxH.toDouble() / height)
		val w = (width * scale).coerceIn(1.0, maxW.toDouble())
		val h = (height * scale).coerceIn(1.0, maxH.toDouble())
		return LayerSizeBudget.snapped(LayerCanvasRect(((maxW - w) / 2).toFloat(), ((maxH - h) / 2).toFloat(), w.toFloat(), h.toFloat()))
	}

	/** [rect] moved, whole pixels at a time, to centre on [centre] as nearly as the canvas allows. */
	internal fun centred(rect: LayerCanvasRect, centre: Pair<Float, Float>, canvasWidth: Int, canvasHeight: Int): LayerCanvasRect {
		fun axis(at: Float, size: Float, canvas: Int, current: Float): Float =
			if (!at.isFinite() || size > canvas) current else kotlin.math.round(at - size / 2f).coerceIn(0f, kotlin.math.floor(canvas - size))
		return rect.copy(left = axis(centre.first, rect.width, canvasWidth, rect.left), top = axis(centre.second, rect.height, canvasHeight, rect.top))
	}

	/** Nearest-neighbour scale of [image]; only the check for legacy placements, whose rasters were scaled to their bounds, uses it. */
	fun scaleRgba(image: RasterImage, width: Int, height: Int, checkCancelled: () -> Unit): ByteArray {
		val out = ByteArray(width * height * 4)
		for (y in 0 until height) {
			checkCancelled()
			val srcY = (y.toLong() * image.height / height).toInt()
			for (x in 0 until width) {
				val srcX = (x.toLong() * image.width / width).toInt()
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

/**
 * Size limits and canvas placement of a layer whose raster is independent of its canvas rectangle.
 *
 * A layer costs memory twice: its raster at native resolution, and the canvas-resolution view analysis and
 * meshing read ([CanvasDensity]), which covers the integer bounds one pixel per canvas unit. Both are held to
 * [MAX_PIXELS]. The native density (raster pixels per canvas unit) is held to [MAX_DENSITY] on each axis, so a
 * large raster cannot be squeezed onto a speck of canvas, and a rectangle must span at least [MIN_EXTENT].
 */
internal object LayerSizeBudget {
	/** Pixels of one layer raster, and of its canvas-resolution view. */
	const val MAX_PIXELS: Long = 16L * 1024 * 1024
	/** Raster pixels per canvas unit on either axis. */
	const val MAX_DENSITY: Float = 256f
	/** Smallest canvas extent of a rectangle on either axis, in canvas units. */
	const val MIN_EXTENT: Float = 0.5f
	private const val SNAP = 1e-3
	private const val MAX_COORDINATE = 1e8f

	/** Rejects a [rasterWidth] x [rasterHeight] raster on [rect] that exceeds a limit, naming the limit. */
	fun require(rasterWidth: Int, rasterHeight: Int, rect: LayerCanvasRect) {
		require(rasterWidth > 0 && rasterHeight > 0) { "Layer raster is empty" }
		require(rasterWidth.toLong() * rasterHeight <= MAX_PIXELS) {
			"Layer raster ${rasterWidth}x$rasterHeight exceeds ${MAX_PIXELS / (1024 * 1024)} megapixels"
		}
		require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { abs(it) <= MAX_COORDINATE }) {
			"Layer rectangle is outside the canvas coordinate range"
		}
		require(rect.width >= MIN_EXTENT && rect.height >= MIN_EXTENT) {
			"Layer rectangle ${rect.width}x${rect.height} is smaller than $MIN_EXTENT canvas units"
		}
		val (bounds, _) = enclosing(rect)
		require(bounds.width.toLong() * bounds.height <= MAX_PIXELS) {
			"Layer rectangle ${bounds.width}x${bounds.height} exceeds ${MAX_PIXELS / (1024 * 1024)} megapixels at canvas resolution"
		}
		val density = max(rasterWidth / rect.width, rasterHeight / rect.height)
		require(density <= MAX_DENSITY) {
			"Layer density ${"%.1f".format(density)} pixels per canvas unit exceeds ${MAX_DENSITY.toInt()}; enlarge the rectangle or downscale the image"
		}
	}

	/** [rect] with every edge within 1/1000 of a canvas unit of a whole unit snapped to it. */
	fun snapped(rect: LayerCanvasRect): LayerCanvasRect {
		fun snap(value: Double): Double = kotlin.math.round(value).let { if (abs(value - it) <= SNAP) it else value }
		val left = snap(rect.left.toDouble()); val top = snap(rect.top.toDouble())
		val right = snap(rect.left.toDouble() + rect.width); val bottom = snap(rect.top.toDouble() + rect.height)
		return LayerCanvasRect(left.toFloat(), top.toFloat(), (right - left).toFloat(), (bottom - top).toFloat())
	}

	/**
	 * The whole-unit bounds enclosing [rect] (snapped first) and the rectangle to store on the layer: null
	 * when it is exactly those bounds.
	 */
	fun enclosing(rect: LayerCanvasRect): Pair<LayerBounds, LayerCanvasRect?> {
		val snapped = snapped(rect)
		val left = floor(snapped.left.toDouble()).toInt()
		val top = floor(snapped.top.toDouble()).toInt()
		val right = max(left + 1, ceil(snapped.left.toDouble() + snapped.width).toInt())
		val bottom = max(top + 1, ceil(snapped.top.toDouble() + snapped.height).toInt())
		val bounds = LayerBounds(left, top, right - left, bottom - top)
		return bounds to snapped.takeUnless { it.matches(bounds) }
	}
}
