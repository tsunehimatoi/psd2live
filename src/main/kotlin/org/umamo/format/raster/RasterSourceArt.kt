package org.umamo.format.raster

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer

/**
 * Wraps a single decoded [RasterImage] as a one-layer [SourceArt], so a flat raster (PNG, BMP, …)
 * rides the same source-art ingest path as the layered readers (PSD, CLIP, KRA) without a second
 * import mechanism.
 *
 * The result is a root-level document with one raster layer covering the whole canvas: no groups,
 * normal blend, full opacity, unclipped.  A flat image carries no per-layer stable identity (the
 * whole file is the layer), so [id] defaults to the file [name].  The pixels are handed through
 * by reference - the caller must not mutate [RasterImage.rgba] afterwards.
 *
 * @param RasterImage image The decoded flat image (RGBA8888, straight alpha, top-first).
 * @param String name        A display name / provisional layer id for the single layer (e.g. the file name).
 * @return SourceArt A one-layer document sized to the image.
 */
public fun rasterToSourceArt(image: RasterImage, name: String): SourceArt =
	RasterSourceArtImpl(
		widthPx = image.width,
		heightPx = image.height,
		layers =
			listOf(
				RasterSourceLayer(
					id = LayerId(name),
					name = name,
					groupPath = "",
					order = 0,
					bounds = LayerBounds(left = 0, top = 0, width = image.width, height = image.height),
					opacity = 1f,
					clipped = false,
					blend = LayerBlend.Normal,
					raster = LayerRaster(width = image.width, height = image.height, rgba = image.rgba),
				),
			),
	)

/** Concrete [SourceLayer] backing a flat raster imported as a single layer. */
private data class RasterSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val groupPath: String,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val raster: LayerRaster,
) : SourceLayer

/** Concrete [SourceArt] backing a flat raster (one layer, no folders). */
private data class RasterSourceArtImpl(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
) : SourceArt