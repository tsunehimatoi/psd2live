package org.umamo.interop.cmo3

import org.umamo.format.art.LayerBounds
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.cropped
import org.umamo.format.raster.fittedInto
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The thumbnails a CMO3 carries, as the official editor writes them (docs/format/CMO3.md section 4,
 * Thumbnails): every icon is a CImageIcon over a CWritableImage naming an image*.png entry, a
 * straight-alpha RGBA PNG exactly the square's size, holding the art fitted into the square.  A
 * layer's icons fit its whole raster, a model image's its filtered image, a drawable's the patch its
 * mesh covers on that image, the model's a rendered thumbnail.  One builder serves the fresh graph
 * (entries collected for the assembler) and the retained graph (entries embedded as they are
 * minted, or replaced in place at an icon's existing path).
 */

/**
 * The icon builders.
 */
internal object Cmo3Icons {
	/** The three model icon edges, in the order CModelSource carries them (_icon64, _icon32, _icon16). */
	val MODEL_ICON_SIZES: List<Int> = listOf(64, 32, 16)

	/**
	 * The PNG an icon of [size] holds for [raster].
	 *
	 * @param RasterImage raster The art.
	 * @param Int         size   The square's edge.
	 * @return ByteArray The encoded PNG.
	 */
	fun iconPngOf(raster: RasterImage, size: Int): ByteArray = PngCodec.write(raster.fittedInto(size))

	/**
	 * An icon of [raster] at [size], its PNG collected for the assembler under [path].
	 *
	 * @param RasterImage raster  The art.
	 * @param Int         size    The square's edge.
	 * @param String      path    The unique archive path for the PNG entry.
	 * @param MutableList entries The PNG entry collector.
	 * @return CImageIcon The icon.
	 */
	fun iconOf(raster: RasterImage, size: Int, path: String, entries: MutableList<Cmo3FreshFile.PngEntry>): CImageIcon {
		entries.add(Cmo3FreshFile.PngEntry(path, iconPngOf(raster, size)))
		return iconReferencing(size, path)
	}

	/**
	 * An icon wrapper referencing an embedded PNG entry the caller owns.
	 *
	 * @param Int    size The square's edge.
	 * @param String path The archive entry path the icon references.
	 * @return CImageIcon The icon.
	 */
	fun iconReferencing(size: Int, path: String): CImageIcon =
		CImageIcon().apply {
			image =
				CWritableImage().apply {
					// CMO3: CWritableImage attrs width/height/type + file child (every corpus icon).
					width = size
					height = size
					type = "INT_ARGB"
					image = FileRef().apply { archivePath = path }
				}
		}

	/**
	 * The archive path an icon's PNG lives at, or null when the slot holds no such icon.
	 *
	 * @param Any? icon A CImageIcon slot.
	 * @return String? The path.
	 */
	fun archivePathOf(icon: Any?): String? = (((icon as? CImageIcon)?.image as? CWritableImage)?.image as? FileRef)?.archivePath

	/**
	 * The patch a drawable's icon shows: the mesh's uv bounding box on its art, or the whole art when
	 * the mesh is unknown.
	 *
	 * @param RasterImage raster The art the drawable samples, in its own frame.
	 * @param FloatArray? artUvs The drawable's texture coordinates in that frame, interleaved, or null.
	 * @return RasterImage The patch.
	 */
	fun patchOf(raster: RasterImage, artUvs: FloatArray?): RasterImage {
		if (artUvs == null || artUvs.size < 2 || raster.width <= 0 || raster.height <= 0) {
			return raster
		}
		var minU = Float.POSITIVE_INFINITY
		var minV = Float.POSITIVE_INFINITY
		var maxU = Float.NEGATIVE_INFINITY
		var maxV = Float.NEGATIVE_INFINITY
		var componentIndex = 0
		while (componentIndex + 1 < artUvs.size) {
			minU = minOf(minU, artUvs[componentIndex])
			maxU = maxOf(maxU, artUvs[componentIndex])
			minV = minOf(minV, artUvs[componentIndex + 1])
			maxV = maxOf(maxV, artUvs[componentIndex + 1])
			componentIndex += 2
		}
		if (!minU.isFinite() || !minV.isFinite() || !maxU.isFinite() || !maxV.isFinite()) {
			return raster
		}
		// The box in pixels, clamped to the art and at least one pixel each way.
		val left = floor(minU * raster.width).toInt().coerceIn(0, raster.width - 1)
		val top = floor(minV * raster.height).toInt().coerceIn(0, raster.height - 1)
		val right = ceil(maxU * raster.width).toInt().coerceIn(left + 1, raster.width)
		val bottom = ceil(maxV * raster.height).toInt().coerceIn(top + 1, raster.height)
		return raster.cropped(LayerBounds(left, top, right - left, bottom - top))
	}

	/**
	 * A drawable's texture coordinates in its tile's own frame, or null when it has no mesh or its
	 * tile's mapping cannot be formed - the same mapping the rig-work check reads.
	 *
	 * @param PuppetModel model    The model the drawable belongs to.
	 * @param Drawable    drawable The drawable.
	 * @return FloatArray? The art-frame uvs, interleaved.
	 */
	fun artUvsOf(model: PuppetModel, drawable: Drawable): FloatArray? {
		val uvs = drawable.mesh?.uvs?.takeIf { stored -> stored.isNotEmpty() } ?: return null
		val tileId = drawable.atlasTileId ?: return null
		val storedToArt = model.atlas.storedToArtAffineForTile(tileId) ?: return null
		return applyUvAffine(uvs, storedToArt)
	}
}