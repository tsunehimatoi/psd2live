package io.github.psd2live.core

import io.github.psd2live.document.LayerCanvasRect
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.isFullyTransparent
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Painted pixels as a commit hands them over: [raster] stretched over the canvas rectangle [rect], or - when
 * [rect] is null - a raster of the whole canvas at one pixel per canvas unit.
 */
internal class PaintPixels(val raster: LayerRaster, val rect: LayerCanvasRect?) {
	init {
		require(raster.rgba.size.toLong() == raster.width.toLong() * raster.height * 4) { "Paint raster does not match its size" }
	}

	/** Where [raster] lies on a [canvasWidth] x [canvasHeight] canvas. */
	fun space(canvasWidth: Int, canvasHeight: Int): LayerSpace = rect?.let { LayerSpace(it.left, it.top, it.width, it.height, raster.width, raster.height) }
		?: LayerSpace(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), canvasWidth, canvasHeight)
}

/** A layer's pixels after a paint: its integer [bounds], its float [rect] when that differs, and its [raster]. */
internal class PaintedLayer(val bounds: LayerBounds, val rect: LayerCanvasRect?, val raster: LayerRaster) {
	/** Whether this is exactly [other]'s placement and pixels. */
	fun samePixels(other: PaintedLayer): Boolean = bounds == other.bounds && rect == other.rect &&
		raster.width == other.raster.width && raster.height == other.raster.height && raster.rgba.contentEquals(other.raster.rgba)

	companion object {
		/** What a fully erased layer commits: a transparent pixel at the canvas origin. */
		val EMPTY get() = PaintedLayer(LayerBounds(0, 0, 1, 1), null, LayerRaster(1, 1, ByteArray(4)))
	}
}

/**
 * The pixel grid a paint session draws on: a working image whose pixels are the painted layer's own raster
 * pixels, extended over the canvas at the layer's density.
 *
 * Image pixel `(i, j)` is pixel `(i + offsetX, j + offsetY)` of [layer]'s raster grid (which may lie outside the
 * raster itself, where the layer can grow). Everything a gesture says is in canvas units and is mapped here: a
 * brush 2 units wide on a layer holding 32 raster pixels per canvas unit paints 64 pixels wide, so painting a
 * dense layer stays as sharp as its raster. A layer whose raster covers its integer bounds one to one (and a
 * fully transparent one) paints on the canvas itself, exactly as before.
 */
internal class PaintSpace(
	/** The rectangle and raster whose grid the image follows. */
	val layer: LayerSpace,
	val offsetX: Int,
	val offsetY: Int,
	val width: Int,
	val height: Int,
	/**
	 * Where the layer's current raster lies on the image, unclipped, pixel for pixel; null when it holds nothing
	 * to show (a fully transparent layer).
	 */
	val raster: Rectangle? = Rectangle(-offsetX, -offsetY, layer.rasterWidth, layer.rasterHeight),
) {
	init { require(width > 0 && height > 0) { "Paint image must not be empty" } }

	/** Whether this is the canvas itself, one image pixel per canvas unit from the origin. */
	val isCanvas: Boolean get() = originX == 0f && originY == 0f && scaleX == 1f && scaleY == 1f

	/** Image pixels per canvas unit. */
	val scaleX: Float get() = layer.scaleX
	val scaleY: Float get() = layer.scaleY

	/** Canvas units of the image's left and top edges. */
	val originX: Float = layer.left + offsetX / layer.scaleX
	val originY: Float = layer.top + offsetY / layer.scaleY

	/** The canvas extent the image covers. */
	val canvasWidth: Float get() = width / scaleX
	val canvasHeight: Float get() = height / scaleY

	fun canvasToImageX(x: Float): Float = (x - originX) * scaleX
	fun canvasToImageY(y: Float): Float = (y - originY) * scaleY
	fun imageToCanvasX(x: Float): Float = originX + x / scaleX
	fun imageToCanvasY(y: Float): Float = originY + y / scaleY

	/** A canvas length (a brush radius, a stroke width) in image pixels; the mean of both axes when they differ. */
	fun imageLength(canvas: Float): Float = if (scaleX == scaleY) canvas * scaleX else canvas * sqrt(scaleX * scaleY)

	/** The image pixel holding the canvas pixel whose top-left corner is ([x], [y]), or null off the image. */
	fun imagePixel(x: Int, y: Int): Pair<Int, Int>? {
		val px = floor(canvasToImageX(x + 0.5f)).toInt(); val py = floor(canvasToImageY(y + 0.5f)).toInt()
		return if (px in 0 until width && py in 0 until height) px to py else null
	}

	/** The layer's current raster on the image, clipped to it; empty when there is none or it is outside. */
	fun layerArea(): Rectangle = raster?.intersection(Rectangle(0, 0, width, height)) ?: Rectangle()

	/** A new image of this space holding [source]'s raster (the layer this space was made for). */
	fun image(source: SourceLayer, checkpoint: () -> Unit = {}): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val at = raster ?: return image
		val area = layerArea()
		if (area.isEmpty) return image
		val pixels = source.raster
		require(pixels.width == at.width && pixels.height == at.height) { "Paint space does not match its layer" }
		val row = IntArray(area.width)
		for (y in area.y until area.y + area.height) {
			checkpoint()
			val from = ((y - at.y) * pixels.width + (area.x - at.x)) * 4
			for (x in 0 until area.width) {
				val i = from + x * 4
				row[x] = ((pixels.rgba[i + 3].toInt() and 255) shl 24) or ((pixels.rgba[i].toInt() and 255) shl 16) or
					((pixels.rgba[i + 1].toInt() and 255) shl 8) or (pixels.rgba[i + 2].toInt() and 255)
			}
			image.setRGB(area.x, y, area.width, 1, row, 0, area.width)
		}
		return image
	}

	/**
	 * What [crop] gives for an image made by [image] and left untouched: [source]'s pixels on this space, cropped.
	 * Reads the raster directly, without an image.
	 */
	fun cropLayer(source: SourceLayer, checkpoint: () -> Unit = {}): PaintedLayer {
		val at = raster ?: return PaintedLayer.EMPTY
		val area = layerArea()
		if (area.isEmpty) return PaintedLayer.EMPTY
		val pixels = source.raster
		require(pixels.width == at.width && pixels.height == at.height) { "Paint space does not match its layer" }
		val rgba = pixels.rgba
		var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
		for (y in area.y - at.y until area.y - at.y + area.height) {
			checkpoint()
			var first = -1; var last = -1
			for (x in area.x - at.x until area.x - at.x + area.width) {
				if (rgba[(y * pixels.width + x) * 4 + 3].toInt() != 0) { if (first < 0) first = x; last = x }
			}
			if (first < 0) continue
			if (first < minX) minX = first
			if (last > maxX) maxX = last
			if (y < minY) minY = y
			maxY = y
		}
		if (maxX < minX || maxY < minY) return PaintedLayer.EMPTY
		val cropW = maxX - minX + 1; val cropH = maxY - minY + 1
		val cropped = if (cropW == pixels.width && cropH == pixels.height) pixels else {
			val out = ByteArray(cropW * cropH * 4)
			for (y in 0 until cropH) System.arraycopy(rgba, ((minY + y) * pixels.width + minX) * 4, out, y * cropW * 4, cropW * 4)
			LayerRaster(cropW, cropH, out)
		}
		return placed(minX + at.x, minY + at.y, cropW, cropH, cropped)
	}

	/**
	 * The layer's pixels after a paint: [image]'s non-transparent pixels within [region] (all of it when null),
	 * cropped to their bounding box at the layer's own density. The rectangle is measured from the layer's own,
	 * so an untouched edge keeps exactly its old coordinate and density never drifts by cropping.
	 */
	fun crop(image: BufferedImage, region: Rectangle? = null, checkpoint: () -> Unit = {}): PaintedLayer {
		require(image.width == width && image.height == height) { "Paint image does not match its space" }
		val area = (region ?: Rectangle(0, 0, width, height)).intersection(Rectangle(0, 0, width, height))
		if (area.isEmpty) return PaintedLayer.EMPTY
		var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
		val row = IntArray(area.width)
		for (y in area.y until area.y + area.height) {
			checkpoint()
			image.getRGB(area.x, y, area.width, 1, row, 0, area.width)
			var first = -1; var last = -1
			for (x in 0 until area.width) if (row[x] ushr 24 != 0) { if (first < 0) first = x; last = x }
			if (first < 0) continue
			if (area.x + first < minX) minX = area.x + first
			if (area.x + last > maxX) maxX = area.x + last
			if (y < minY) minY = y
			maxY = y
		}
		if (maxX < minX || maxY < minY) return PaintedLayer.EMPTY
		val cropW = maxX - minX + 1; val cropH = maxY - minY + 1
		val pixels = IntArray(cropW * cropH)
		image.getRGB(minX, minY, cropW, cropH, pixels, 0, cropW)
		val rgba = ByteArray(cropW * cropH * 4)
		for (i in pixels.indices) {
			if (i % cropW == 0) checkpoint()
			val argb = pixels[i]
			rgba[i * 4] = (argb ushr 16).toByte(); rgba[i * 4 + 1] = (argb ushr 8).toByte()
			rgba[i * 4 + 2] = argb.toByte(); rgba[i * 4 + 3] = (argb ushr 24).toByte()
		}
		return placed(minX, minY, cropW, cropH, LayerRaster(cropW, cropH, rgba))
	}

	/** [raster] as the layer's pixels from image pixel ([imageX], [imageY]), [width] x [height] pixels. */
	private fun placed(imageX: Int, imageY: Int, width: Int, height: Int, raster: LayerRaster): PaintedLayer =
		placedOn(layer, imageX + offsetX, imageY + offsetY, width, height, raster)

	companion object {
		/** Working images above this many pixels shrink their margins around the layer. */
		const val MAX_PIXELS: Long = 16L * 1024 * 1024

		/** The canvas itself, one pixel per canvas unit, with a layer raster at [raster] (none when null). */
		fun canvas(width: Int, height: Int, raster: Rectangle? = null): PaintSpace =
			PaintSpace(LayerSpace(0f, 0f, width.toFloat(), height.toFloat(), width, height), 0, 0, width, height, raster)

		/**
		 * The space [layer] is painted in on a [canvasWidth] x [canvasHeight] canvas: the canvas itself for a layer
		 * at one pixel per canvas unit over its integer bounds (or a fully transparent one), else the layer's raster
		 * grid extended to the canvas edges - with margins shrunk evenly around the layer when that would exceed
		 * [budget] pixels.
		 */
		fun of(layer: SourceLayer, canvasWidth: Int, canvasHeight: Int, budget: Long = MAX_PIXELS): PaintSpace {
			if (!CanvasDensity.dense(layer)) {
				// One raster pixel per canvas unit over the integer bounds - or nothing to show.
				require(canvasWidth.toLong() * canvasHeight <= budget) { "Painting requires a canvas of at most 16 megapixels" }
				val bounds = layer.bounds
				val fits = layer.raster.width == bounds.width && layer.raster.height == bounds.height &&
					layer.raster.width > 0 && layer.raster.height > 0 && !layer.raster.isFullyTransparent()
				return canvas(canvasWidth, canvasHeight, if (fits) Rectangle(bounds.left, bounds.top, bounds.width, bounds.height) else null)
			}
			val space = LayerSpace.of(layer)
			val sx = space.scaleX; val sy = space.scaleY
			// The grid pixels whose cells start on the canvas: from the first at or right of 0 to the last
			// ending at or left of the far edge.
			val x0 = -floor(space.left * sx).toInt(); val y0 = -floor(space.top * sy).toInt()
			val x1 = floor((canvasWidth - space.left) * sx).toInt(); val y1 = floor((canvasHeight - space.top) * sy).toInt()
			var left = x0; var top = y0; var right = x1; var bottom = y1
			if (right <= left) { left = 0; right = maxOf(1, space.rasterWidth) }
			if (bottom <= top) { top = 0; bottom = maxOf(1, space.rasterHeight) }
			if ((right - left).toLong() * (bottom - top) > budget) {
				// Keep the raster's on-canvas part and grow around it as far as the budget allows.
				val cl = left.coerceAtLeast(0).coerceAtMost(right - 1); val cr = right.coerceAtMost(space.rasterWidth).coerceAtLeast(cl + 1)
				val ct = top.coerceAtLeast(0).coerceAtMost(bottom - 1); val cb = bottom.coerceAtMost(space.rasterHeight).coerceAtLeast(ct + 1)
				fun size(f: Double): Long {
					val w = (cr - cl) + floor((cl - left) * f).toLong() + floor((right - cr) * f).toLong()
					val h = (cb - ct) + floor((ct - top) * f).toLong() + floor((bottom - cb) * f).toLong()
					return w * h
				}
				var lo = 0.0; var hi = 1.0
				repeat(40) { val mid = (lo + hi) / 2; if (size(mid) <= budget) lo = mid else hi = mid }
				left = cl - floor((cl - left) * lo).toInt(); right = cr + floor((right - cr) * lo).toInt()
				top = ct - floor((ct - top) * lo).toInt(); bottom = cb + floor((bottom - cb) * lo).toInt()
			}
			return PaintSpace(space, left, top, right - left, bottom - top)
		}

		/**
		 * [raster], [width] x [height] pixels of [space]'s grid from pixel ([gridX], [gridY]), as a layer: its
		 * rectangle measured from [space]'s, with an edge that did not move kept exactly.
		 */
		fun placedOn(space: LayerSpace, gridX: Int, gridY: Int, width: Int, height: Int, raster: LayerRaster): PaintedLayer {
			val left = if (gridX == 0) space.left else space.left + gridX / space.scaleX
			val top = if (gridY == 0) space.top else space.top + gridY / space.scaleY
			val right = if (gridX + width == space.rasterWidth) space.right else space.left + (gridX + width) / space.scaleX
			val bottom = if (gridY + height == space.rasterHeight) space.bottom else space.top + (gridY + height) / space.scaleY
			val bl = floor(left).toInt(); val bt = floor(top).toInt()
			val bounds = LayerBounds(bl, bt, maxOf(1, ceil(right - EDGE).toInt() - bl), maxOf(1, ceil(bottom - EDGE).toInt() - bt))
			val rect = LayerCanvasRect(left, top, maxOf(0f, right - left), maxOf(0f, bottom - top))
			return PaintedLayer(bounds, rect.takeUnless { it.matches(bounds) }, raster)
		}

		/**
		 * [raster] over [rect] (the whole [canvasWidth] x [canvasHeight] canvas when null) cropped to its
		 * non-transparent pixels at its own density.
		 */
		fun crop(pixels: PaintPixels, canvasWidth: Int, canvasHeight: Int, checkpoint: () -> Unit = {}): PaintedLayer {
			val raster = pixels.raster
			val rect = pixels.rect
			if (rect == null) require(raster.width == canvasWidth && raster.height == canvasHeight) {
				"Paint image dimensions must match the source canvas"
			}
			if (raster.width <= 0 || raster.height <= 0) return PaintedLayer.EMPTY
			val space = pixels.space(canvasWidth, canvasHeight)
			val rgba = raster.rgba
			var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
			for (y in 0 until raster.height) {
				checkpoint()
				val row = y * raster.width
				var first = -1; var last = -1
				for (x in 0 until raster.width) if (rgba[(row + x) * 4 + 3].toInt() != 0) { if (first < 0) first = x; last = x }
				if (first < 0) continue
				if (first < minX) minX = first
				if (last > maxX) maxX = last
				if (y < minY) minY = y
				maxY = y
			}
			if (maxX < minX || maxY < minY) return PaintedLayer.EMPTY
			val cropW = maxX - minX + 1; val cropH = maxY - minY + 1
			val cropped = if (cropW == raster.width && cropH == raster.height) raster else {
				val out = ByteArray(cropW * cropH * 4)
				for (y in 0 until cropH) System.arraycopy(rgba, ((minY + y) * raster.width + minX) * 4, out, y * cropW * 4, cropW * 4)
				LayerRaster(cropW, cropH, out)
			}
			return placedOn(space, minX, minY, cropW, cropH, cropped)
		}

		/**
		 * [target]'s raster over [target]'s rectangle with every pixel whose centre lies on the canvas taken from
		 * [painted] (transparent where it has none), and pixels off the canvas kept: the pixels of a layer whose
		 * size may not change. Grids that line up copy exactly.
		 */
		fun resampleInto(target: SourceLayer, painted: PaintPixels, canvasWidth: Int, canvasHeight: Int,
		                 checkpoint: () -> Unit = {}): LayerRaster {
			val into = LayerSpace.of(target)
			val from = painted.space(canvasWidth, canvasHeight)
			val fromRgba = painted.raster.rgba
			val raster = target.raster
			val rgba = raster.rgba.copyOf()
			for (y in 0 until raster.height) {
				checkpoint()
				val cy = into.rasterToCanvasY(y + 0.5f)
				if (cy < 0f || cy >= canvasHeight) continue
				val fy = floor(from.canvasToRasterY(cy)).toInt()
				for (x in 0 until raster.width) {
					val cx = into.rasterToCanvasX(x + 0.5f)
					if (cx < 0f || cx >= canvasWidth) continue
					val fx = floor(from.canvasToRasterX(cx)).toInt()
					val index = (y * raster.width + x) * 4
					if (fx in 0 until from.rasterWidth && fy in 0 until from.rasterHeight) {
						System.arraycopy(fromRgba, (fy * from.rasterWidth + fx) * 4, rgba, index, 4)
					} else {
						rgba[index] = 0; rgba[index + 1] = 0; rgba[index + 2] = 0; rgba[index + 3] = 0
					}
				}
			}
			return LayerRaster(raster.width, raster.height, rgba)
		}

		/** Float slack on a far edge: a rectangle ending a rounding error past a whole unit keeps that unit. */
		private const val EDGE = 1e-4f
	}
}
