package org.umamo.format.raster

import org.umamo.format.art.LayerBounds
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Thumbnail rasterization over the neutral raster: the fit every CMO3 icon takes (the art scaled so
 * its longer edge fills a square, centered, transparent elsewhere - the shape the official editor
 * writes for layer, model-image, drawable, and model icons), and the rectangle crop a texture patch
 * is cut with.  Pure Kotlin, so the desktop and Android exports render identical icons.
 */

/**
 * Scales this raster so its longer edge equals [size] and centers it in a transparent
 * [size] x [size] square, the aspect preserved.
 *
 * Each destination pixel is the area average of the source rectangle it covers, computed in
 * premultiplied space and converted back: averaging straight RGB across an alpha edge drags the
 * matte color into the visible pixels and darkens the fringe, the same reason the atlas composer
 * samples premultiplied.  A raster smaller than the square is scaled up by the same rule (a
 * destination pixel then covers part of one source pixel and takes it whole).  An empty raster
 * yields the blank square.
 *
 * @param Int size The square's edge in pixels, at least 1.
 * @return RasterImage The fitted square, straight alpha, top row first.
 */
public fun RasterImage.fittedInto(size: Int): RasterImage {
	require(size > 0) { "a thumbnail needs a positive size, not $size" }
	val square = ByteArray(size * size * 4)
	if (width <= 0 || height <= 0) {
		return RasterImage(size, size, square)
	}
	val scale = size.toDouble() / maxOf(width, height)
	val fittedWidth = (width * scale).roundToInt().coerceIn(1, size)
	val fittedHeight = (height * scale).roundToInt().coerceIn(1, size)
	val offsetX = (size - fittedWidth) / 2
	val offsetY = (size - fittedHeight) / 2
	val columnSpans = axisSpans(width, fittedWidth)
	val rowSpans = axisSpans(height, fittedHeight)
	for (destinationRow in 0 until fittedHeight) {
		val rowSpan = rowSpans[destinationRow]
		for (destinationColumn in 0 until fittedWidth) {
			val columnSpan = columnSpans[destinationColumn]
			var red = 0.0
			var green = 0.0
			var blue = 0.0
			var alpha = 0.0
			var coverage = 0.0
			for (rowTap in 0 until rowSpan.count) {
				val sourceRow = rowSpan.first + rowTap
				val rowWeight = rowSpan.weights[rowTap]
				for (columnTap in 0 until columnSpan.count) {
					val sourceColumn = columnSpan.first + columnTap
					val weight = rowWeight * columnSpan.weights[columnTap]
					val offset = (sourceRow * width + sourceColumn) * 4
					val sourceAlpha = (rgba[offset + 3].toInt() and 0xFF).toDouble()
					// Premultiplied taps: a transparent source pixel contributes coverage but no color.
					red += (rgba[offset].toInt() and 0xFF) * sourceAlpha * weight
					green += (rgba[offset + 1].toInt() and 0xFF) * sourceAlpha * weight
					blue += (rgba[offset + 2].toInt() and 0xFF) * sourceAlpha * weight
					alpha += sourceAlpha * weight
					coverage += weight
				}
			}
			if (coverage <= 0.0 || alpha <= 0.0) {
				continue
			}
			val destinationOffset = ((offsetY + destinationRow) * size + offsetX + destinationColumn) * 4
			// Un-premultiply: the color sums carry an alpha factor the alpha sum divides back out.
			square[destinationOffset] = (red / alpha).roundToInt().coerceIn(0, 255).toByte()
			square[destinationOffset + 1] = (green / alpha).roundToInt().coerceIn(0, 255).toByte()
			square[destinationOffset + 2] = (blue / alpha).roundToInt().coerceIn(0, 255).toByte()
			square[destinationOffset + 3] = (alpha / coverage).roundToInt().coerceIn(0, 255).toByte()
		}
	}
	return RasterImage(size, size, square)
}

/**
 * The source pixels one destination pixel covers along one axis, with the fraction of each it covers.
 *
 * @property Int         first   The first source index.
 * @property Int         count   How many source indices, at least one.
 * @property DoubleArray weights The covered fraction of each, in order.
 */
private class AxisSpan(val first: Int, val count: Int, val weights: DoubleArray)

/**
 * The per-destination source spans of one axis: destination index d covers the source interval
 * [d * ratio, (d + 1) * ratio), where ratio is source over destination length.
 *
 * @param Int sourceLength      The source extent in pixels.
 * @param Int destinationLength The destination extent in pixels.
 * @return Array<AxisSpan> One span per destination index.
 */
private fun axisSpans(sourceLength: Int, destinationLength: Int): Array<AxisSpan> {
	val ratio = sourceLength.toDouble() / destinationLength
	return Array(destinationLength) { destinationIndex ->
		val start = destinationIndex * ratio
		val end = ((destinationIndex + 1) * ratio).coerceAtMost(sourceLength.toDouble())
		val first = floor(start).toInt().coerceIn(0, sourceLength - 1)
		val last = (ceil(end).toInt() - 1).coerceIn(first, sourceLength - 1)
		val weights =
			DoubleArray(last - first + 1) { tap ->
				val sourceIndex = first + tap
				// The overlap of [sourceIndex, sourceIndex + 1) with [start, end).
				(minOf(end, sourceIndex + 1.0) - maxOf(start, sourceIndex.toDouble())).coerceAtLeast(0.0)
			}
		AxisSpan(first, weights.size, weights)
	}
}

/**
 * Extracts [bounds] out of this raster, returning the raster itself when the bounds cover it.
 *
 * @param LayerBounds bounds The raster-local rect to keep, inside the raster.
 * @return RasterImage The extracted pixels.
 */
public fun RasterImage.cropped(bounds: LayerBounds): RasterImage {
	if (bounds.left == 0 && bounds.top == 0 && bounds.width == width && bounds.height == height) {
		return this
	}
	val out = ByteArray(bounds.width * bounds.height * 4)
	for (rowIndex in 0 until bounds.height) {
		val sourceOffset = ((bounds.top + rowIndex) * width + bounds.left) * 4
		rgba.copyInto(out, rowIndex * bounds.width * 4, sourceOffset, sourceOffset + bounds.width * 4)
	}
	return RasterImage(bounds.width, bounds.height, out)
}