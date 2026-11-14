package io.github.psd2live.core

import io.github.psd2live.format.compile.RasterResample
import io.github.psd2live.document.LayerCanvasRect
import io.github.psd2live.document.WorkspaceSourceMetadata
import io.github.psd2live.document.storedCanvasRect
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.isFullyTransparent
import java.util.Collections
import java.util.WeakHashMap

/**
 * The canvas-resolution view of a layer whose raster is not one pixel per canvas unit over its integer bounds.
 *
 * A layer's raster may hold any number of pixels for its canvas rectangle ([LayerSpace]). Everything that
 * works in canvas units - classification, anchors, contours, mesh generation, mouth ribbons, component
 * splitting - reads a layer's raster as covering its integer bounds one to one. So analysis does not hand
 * it the layer itself but a [CanvasDensityLayer]: the same layer whose raster is resampled onto its integer
 * bounds, one pixel per canvas unit. A 1024 x 1024 raster on a 32 x 32 rectangle therefore meshes exactly
 * like a 32 x 32 one, and the generator's layer offsets (its texture coordinates) stay canvas units from
 * the bounds' top-left corner.
 *
 * Texture consumers - the atlas, the binding stage, source tiles of exports - read [textureLayer], the
 * layer with its own raster, through [LayerTexture]. A layer whose raster already covers its bounds one to
 * one, or a fully transparent placeholder, is used as it is, so documents without dense layers analyse
 * exactly as before.
 */
internal object CanvasDensity {
	private data class Key(val bounds: LayerBounds, val rect: LayerCanvasRect?)
	private val proxies = Collections.synchronizedMap(WeakHashMap<ByteArray, Pair<Key, LayerRaster>>())
	private val aligned = Collections.synchronizedMap(WeakHashMap<ByteArray, Pair<Key, LayerRaster>>())

	/** Whether [layer]'s raster is not its integer bounds at one pixel per canvas unit. */
	fun dense(layer: SourceLayer): Boolean {
		if (layer is CanvasDensityLayer) return false
		val raster = layer.raster
		if (raster.width <= 0 || raster.height <= 0 || layer.bounds.width <= 0 || layer.bounds.height <= 0) return false
		if (raster.width == layer.bounds.width && raster.height == layer.bounds.height && layer.storedCanvasRect == null) return false
		return !raster.isFullyTransparent()
	}

	/** [layer] at canvas resolution: itself when it already is, else a [CanvasDensityLayer]. */
	fun canvasLayer(layer: SourceLayer): SourceLayer {
		if (!dense(layer)) return layer
		val key = Key(layer.bounds, layer.storedCanvasRect)
		proxies[layer.raster.rgba]?.takeIf { it.first == key }?.let { return CanvasDensityLayer(layer, it.second) }
		val raster = canvasRaster(layer)
		proxies[layer.raster.rgba] = key to raster
		return CanvasDensityLayer(layer, raster)
	}

	/** [layer]'s raster resampled onto its integer bounds, one pixel per canvas unit; outside its rectangle is transparent. */
	fun canvasRaster(layer: SourceLayer): LayerRaster {
		val space = LayerSpace.of(layer)
		val bounds = layer.bounds
		val rgba = RasterResample.resample(layer.raster.rgba, layer.raster.width, layer.raster.height, bounds.width, bounds.height,
			((bounds.left - space.left) * space.scaleX).toDouble(), space.scaleX.toDouble(),
			((bounds.top - space.top) * space.scaleY).toDouble(), space.scaleY.toDouble())
		return LayerRaster(bounds.width, bounds.height, rgba)
	}

	/**
	 * [layer]'s raster laid at its own density over its integer bounds, its float rectangle folded in: the raster
	 * itself when its rectangle is its bounds, else resampled so its pixel grid starts at the bounds' corner. It is
	 * what the texture trace meshes ([MeshResolution.input]), and exactly what a saved generation input - bounds
	 * and raster, no rectangle - can hold of a dense layer, so a pinned layer meshes like the layer it pins.
	 */
	fun alignedRaster(layer: SourceLayer): LayerRaster {
		val rect = layer.storedCanvasRect ?: return layer.raster
		val key = Key(layer.bounds, rect)
		aligned[layer.raster.rgba]?.takeIf { it.first == key }?.let { return it.second }
		val space = LayerSpace.of(layer)
		val bounds = layer.bounds
		val width = Math.round(bounds.width * space.scaleX.toDouble()).toInt().coerceAtLeast(1)
		val height = Math.round(bounds.height * space.scaleY.toDouble()).toInt().coerceAtLeast(1)
		val rgba = RasterResample.resample(layer.raster.rgba, layer.raster.width, layer.raster.height, width, height,
			(bounds.left - space.left) * space.scaleX.toDouble(), space.scaleX.toDouble() * bounds.width / width,
			(bounds.top - space.top) * space.scaleY.toDouble(), space.scaleY.toDouble() * bounds.height / height)
		return LayerRaster(width, height, rgba).also { aligned[layer.raster.rgba] = key to it }
	}
}

/**
 * A layer seen at canvas resolution: [texture]'s metadata with its raster resampled onto its integer bounds
 * ([CanvasDensity]). Its own rectangle is exactly those bounds; the texture keeps the original raster.
 */
internal class CanvasDensityLayer(val texture: SourceLayer, override val raster: LayerRaster) :
	WorkspaceSourceMetadata, SourceLayer by texture {
	override val derived: Boolean get() = (texture as? WorkspaceSourceMetadata)?.derived == true
	override val sourceAssetId: String? get() = (texture as? WorkspaceSourceMetadata)?.sourceAssetId
	override val sourceSpatialReferenceId: String? get() = (texture as? WorkspaceSourceMetadata)?.sourceSpatialReferenceId
	override val rect: LayerCanvasRect? get() = null
}

/** The layer whose raster textures this one: the original behind a canvas-resolution view, else the layer itself. */
internal val SourceLayer.textureLayer: SourceLayer get() = (this as? CanvasDensityLayer)?.texture ?: this
