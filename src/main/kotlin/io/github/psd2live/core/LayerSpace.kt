package io.github.psd2live.core

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import io.github.psd2live.document.storedCanvasRect

/**
 * One layer's space: where the layer sits on the canvas and how many raster pixels cover it.
 *
 * The canvas rectangle ([left], [top], [width], [height]) is in canvas units and may be fractional;
 * the raster ([rasterWidth] x [rasterHeight]) is the layer's own pixel grid stretched over that
 * rectangle. The two are independent, so a layer can hold more (or fewer) pixels than the canvas
 * area it covers, with different densities per axis.
 *
 * Three coordinate systems are related here:
 * - canvas: canvas units, top-left origin, the space the rig is generated in;
 * - raster: pixels of the layer raster, `(0, 0)` at the rectangle's top-left corner;
 * - layer unit: `0..1` across the rectangle on each axis, independent of both canvas and raster
 *   resolution.
 *
 * A degenerate rectangle (zero width or height) maps every raster coordinate onto its edge, and
 * reports a scale of 1 on that axis instead of an infinite one.
 */
data class LayerSpace(
	val left: Float,
	val top: Float,
	val width: Float,
	val height: Float,
	val rasterWidth: Int,
	val rasterHeight: Int,
) {
	init {
		require(left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite()) { "Layer rectangle must be finite" }
		require(width >= 0f && height >= 0f) { "Layer rectangle must not be negative" }
		require(rasterWidth >= 0 && rasterHeight >= 0) { "Layer raster size must not be negative" }
	}

	val right: Float get() = left + width
	val bottom: Float get() = top + height

	/** Raster pixels per canvas unit horizontally; 1 for a zero-width rectangle. */
	val scaleX: Float get() = if (width > 0f) rasterWidth / width else 1f

	/** Raster pixels per canvas unit vertically; 1 for a zero-height rectangle. */
	val scaleY: Float get() = if (height > 0f) rasterHeight / height else 1f

	/** The canvas rectangle as edges. */
	fun canvasBounds(): Bounds = Bounds(left, top, right, bottom)

	fun canvasToRasterX(x: Float): Float = (x - left) * scaleX
	fun canvasToRasterY(y: Float): Float = (y - top) * scaleY
	fun rasterToCanvasX(x: Float): Float = left + x / scaleX
	fun rasterToCanvasY(y: Float): Float = top + y / scaleY

	fun canvasToLayerUnitX(x: Float): Float = if (width > 0f) (x - left) / width else 0f
	fun canvasToLayerUnitY(y: Float): Float = if (height > 0f) (y - top) / height else 0f
	fun layerUnitToCanvasX(u: Float): Float = left + u * width
	fun layerUnitToCanvasY(v: Float): Float = top + v * height

	fun canvasToRaster(x: Float, y: Float): Pair<Float, Float> = canvasToRasterX(x) to canvasToRasterY(y)
	fun rasterToCanvas(x: Float, y: Float): Pair<Float, Float> = rasterToCanvasX(x) to rasterToCanvasY(y)
	fun canvasToLayerUnit(x: Float, y: Float): Pair<Float, Float> = canvasToLayerUnitX(x) to canvasToLayerUnitY(y)
	fun layerUnitToCanvas(u: Float, v: Float): Pair<Float, Float> = layerUnitToCanvasX(u) to layerUnitToCanvasY(v)

	companion object {
		/** The space of a layer whose rectangle is exactly its integer [bounds]. */
		fun fromBounds(bounds: LayerBounds, rasterWidth: Int, rasterHeight: Int): LayerSpace = LayerSpace(
			left = bounds.left.toFloat(),
			top = bounds.top.toFloat(),
			width = bounds.width.toFloat(),
			height = bounds.height.toFloat(),
			rasterWidth = rasterWidth,
			rasterHeight = rasterHeight,
		)

		fun fromBounds(bounds: LayerBounds, raster: LayerRaster): LayerSpace =
			fromBounds(bounds, raster.width, raster.height)

		/** The space of a source layer: its stored canvas rectangle, else its integer bounds, over its raster. */
		fun of(layer: SourceLayer): LayerSpace {
			val rect = layer.storedCanvasRect ?: return fromBounds(layer.bounds, layer.raster)
			return LayerSpace(rect.left, rect.top, rect.width, rect.height, layer.raster.width, layer.raster.height)
		}
	}
}
