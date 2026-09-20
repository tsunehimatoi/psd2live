package org.umamo.render

import org.umamo.format.art.LayerBounds
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.inversePlacementAffine
import org.umamo.runtime.model.placementAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The placement gizmo's page-space bookkeeping: the mesh reach each tile must keep clear, the
 * footprint a placement occupies on its page, and the two regions a collision is made of.
 *
 * A collision is asymmetric and exact on both sides.  What goes wrong when tiles overlap is a mesh
 * SAMPLING another tile's opaque pixels, so every tile has a sampled region - the coverage of its
 * triangles, rasterized in its own pixel frame, through its placement - and a painted region - its
 * opaque texels, the composer never letting a transparent one erase anything - and tile A collides
 * with tile B when A's triangles land on B's paint or B's triangles land on A's paint.  The two sides
 * are different shapes on purpose: the typical mesh extends PAST its art, and those overhanging
 * triangles are exactly where a neighbor's pixels cause damage, while a tile's pixels outside its own
 * mesh are still paint another tile's triangles can land on.  A bounding box on either side lies for
 * strand-shaped art and for meshes that sample only part of their layer.  A mover's paint is its own
 * opaque mask; the bystanders' paint is the shown page itself, whose composed alpha IS what is painted
 * there.
 *
 * Session-free and GL-free.  It lives beside the derivation rather than in the editing module because
 * a footprint is a derivation question (which pixels will the compose write) and needs the trim the
 * derivation draws, and because the repack and the drag must reserve by ONE rule - a reserve computed
 * two ways would let a hand-nudged tile land where the next repack refuses to put it.
 */

/**
 * Per tile, the union of its bound drawables' mesh reach in the tile's own art frame, rounded out to
 * whole pixels - what a pack must keep clear BEYOND the opaque pixels.
 *
 * The pixels alone do not bound what samples a tile: an art mesh rings outside the opaque region
 * (Erica's reach up to 58px past their trim, and past the raster itself), so a pack spaced by
 * opaque bounds puts one tile's mesh footprint over its neighbor's art - the mesh then renders the
 * neighbor's pixels wherever they are opaque.  The official editor's own packing keeps mesh
 * footprints disjoint, and this is how the repack matches it.
 *
 * A drawable whose mapping will not resolve (a placement naming a page the atlas lacks) contributes
 * nothing here; if it is bound, the repack's refusal pass aborts before the reserve could have
 * mattered.
 *
 * @param PuppetModel model The model whose bindings to measure.
 * @return Map Each bound tile's reserve, absent when a tile has no measurable mesh.
 */
public fun meshReserveByTile(model: PuppetModel): Map<AtlasTileId, AtlasPackReserve> {
	val boundsByTile = HashMap<AtlasTileId, FloatArray>()
	for (drawable in model.drawables) {
		val tileId = drawable.atlasTileId ?: continue
		val tile = model.atlas.tileById[tileId] ?: continue
		val uvs = drawable.mesh?.uvs ?: continue
		if (uvs.size < 2) {
			continue
		}
		val storedToArt = model.atlas.storedToArtAffineForTile(tileId) ?: continue
		val artUvs = applyUvAffine(uvs, storedToArt)
		val bounds = boundsByTile.getOrPut(tileId) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
		var componentIndex = 0
		while (componentIndex + 1 < artUvs.size) {
			val artX = artUvs[componentIndex] * tile.width
			val artY = artUvs[componentIndex + 1] * tile.height
			bounds[0] = minOf(bounds[0], artX)
			bounds[1] = minOf(bounds[1], artY)
			bounds[2] = maxOf(bounds[2], artX)
			bounds[3] = maxOf(bounds[3], artY)
			componentIndex += 2
		}
	}
	return boundsByTile.mapValues { (_, bounds) ->
		AtlasPackReserve(
			left = floor(bounds[0]).toInt(),
			top = floor(bounds[1]).toInt(),
			right = ceil(bounds[2]).toInt(),
			bottom = ceil(bounds[3]).toInt(),
		)
	}
}

/**
 * A placement's axis-aligned extent on its page, in continuous page pixels: the bounds of the
 * tile-local rectangle (the trim, widened by the mesh reserve) through the placement.
 *
 * A rotated tile's footprint is its bounding box, so a test over one over-warns for a turned tile and
 * never under-warns - the first-cut approximation the gizmo's warnings run on.
 *
 * @property Float left   The left edge.
 * @property Float top    The top edge.
 * @property Float right  The right edge, exclusive.
 * @property Float bottom The bottom edge, exclusive.
 */
data class PlacementFootprint(
	val left: Float,
	val top: Float,
	val right: Float,
	val bottom: Float,
) {
	/**
	 * This footprint grown by [margin] on every side - the gutter a neighbor must stay clear of.
	 *
	 * @param Float margin The growth in page pixels.
	 * @return PlacementFootprint The grown footprint.
	 */
	fun expanded(margin: Float): PlacementFootprint = PlacementFootprint(left - margin, top - margin, right + margin, bottom + margin)

	/**
	 * True when this footprint and [other] share any area (touching edges do not count).
	 *
	 * @param PlacementFootprint other The footprint to test against.
	 * @return Boolean Whether they overlap.
	 */
	fun overlaps(other: PlacementFootprint): Boolean =
		left < other.right && other.left < right && top < other.bottom && other.top < bottom

	/**
	 * True when any part of this footprint lies outside a page of the given size.
	 *
	 * @param Int pageWidth  The page width in pixels.
	 * @param Int pageHeight The page height in pixels.
	 * @return Boolean Whether the footprint spills off the page.
	 */
	fun exceeds(pageWidth: Int, pageHeight: Int): Boolean = left < 0f || top < 0f || right > pageWidth || bottom > pageHeight
}

/**
 * The footprint [placement] occupies: its tile's [trim] widened by [reserve] (when the tile has a
 * measurable mesh), carried through the placement onto the page.
 *
 * @param AtlasPlacement    placement The tile's placement.
 * @param LayerBounds       trim      The tile's opaque bounds, raster-local.
 * @param AtlasPackReserve? reserve   The tile's mesh reach, raster-local, or null when none.
 * @return PlacementFootprint The page-space bounds.
 */
fun placementFootprint(placement: AtlasPlacement, trim: LayerBounds, reserve: AtlasPackReserve?): PlacementFootprint {
	var localLeft = trim.left.toFloat()
	var localTop = trim.top.toFloat()
	var localRight = (trim.left + trim.width).toFloat()
	var localBottom = (trim.top + trim.height).toFloat()
	if (reserve != null) {
		localLeft = minOf(localLeft, reserve.left.toFloat())
		localTop = minOf(localTop, reserve.top.toFloat())
		localRight = maxOf(localRight, reserve.right.toFloat())
		localBottom = maxOf(localBottom, reserve.bottom.toFloat())
	}
	val affine = placementAffine(placement)
	var left = Float.POSITIVE_INFINITY
	var top = Float.POSITIVE_INFINITY
	var right = Float.NEGATIVE_INFINITY
	var bottom = Float.NEGATIVE_INFINITY
	for (cornerIndex in 0 until 4) {
		val cornerX = if (cornerIndex and 1 == 0) localLeft else localRight
		val cornerY = if (cornerIndex and 2 == 0) localTop else localBottom
		val pageX = affine[0] * cornerX + affine[1] * cornerY + affine[2]
		val pageY = affine[3] * cornerX + affine[4] * cornerY + affine[5]
		left = minOf(left, pageX)
		top = minOf(top, pageY)
		right = maxOf(right, pageX)
		bottom = maxOf(bottom, pageY)
	}
	return PlacementFootprint(left, top, right, bottom)
}

/** The side of one occupancy block, in page pixels. */
private const val OCCUPANCY_BLOCK_SIDE = 8

/**
 * A tile's opaque texels within its trim, one bit each, trim-local - a mover's painted region.
 *
 * @property LayerBounds trim The trim the mask covers, raster-local.
 */
class TileOpaqueMask private constructor(
	val trim: LayerBounds,
	private val words: LongArray,
) {
	/**
	 * Whether the raster texel at ([tileX], [tileY]) is opaque; false outside the trim.
	 *
	 * @param Int tileX The texel column, raster-local.
	 * @param Int tileY The texel row, raster-local.
	 * @return Boolean True when the texel meets the mask's threshold.
	 */
	fun isOpaque(tileX: Int, tileY: Int): Boolean {
		val column = tileX - trim.left
		val row = tileY - trim.top
		if (column < 0 || row < 0 || column >= trim.width || row >= trim.height) {
			return false
		}
		val bitIndex = row * trim.width + column
		return (words[bitIndex ushr 6] ushr (bitIndex and 63)) and 1L == 1L
	}

	companion object {
		/**
		 * The mask of [raster]'s texels inside [trim] whose alpha reaches [alphaThreshold].
		 *
		 * @param DecodedImage raster         The tile's decoded art.
		 * @param LayerBounds  trim           The sub-rectangle to mask, raster-local.
		 * @param Int          alphaThreshold The alpha a texel needs to count as opaque.
		 * @return TileOpaqueMask The mask.
		 */
		fun of(raster: DecodedImage, trim: LayerBounds, alphaThreshold: Int): TileOpaqueMask {
			val bitCount = trim.width * trim.height
			val words = LongArray((bitCount + 63) ushr 6)
			for (row in 0 until trim.height) {
				val rowOffset = (trim.top + row) * raster.width + trim.left
				for (column in 0 until trim.width) {
					val alpha = raster.rgba[(rowOffset + column) * 4 + 3].toInt() and 0xFF
					if (alpha >= alphaThreshold) {
						val bitIndex = row * trim.width + column
						words[bitIndex ushr 6] = words[bitIndex ushr 6] or (1L shl (bitIndex and 63))
					}
				}
			}
			return TileOpaqueMask(trim, words)
		}
	}
}

/**
 * An integer pixel rectangle on a page, right and bottom exclusive.
 *
 * @property Int left   The left edge.
 * @property Int top    The top edge.
 * @property Int right  The right edge, exclusive.
 * @property Int bottom The bottom edge, exclusive.
 */
data class PixelRect(
	val left: Int,
	val top: Int,
	val right: Int,
	val bottom: Int,
) {
	/**
	 * Whether ([x], [y]) lies inside.
	 *
	 * @param Int x The pixel column.
	 * @param Int y The pixel row.
	 * @return Boolean True when inside.
	 */
	fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * The shown page's painted pixels - the bystanders' painted region as one surface.
 *
 * The composed page's alpha IS what is painted there (extrusion bands included), so no tile needs
 * decoding: one pass over the page reduces it to a bitset of 8x8 blocks for fast rejection, and the
 * exact alpha stays behind it for the pixels a sampled region actually touches.  The rectangles a
 * caller names are read as empty - the spots the movers are leaving.
 *
 * Holds the page's pixel buffer by reference; it is never written.
 *
 * @property Int width  The page width in pixels.
 * @property Int height The page height in pixels.
 */
class PageOccupancy private constructor(
	val width: Int,
	val height: Int,
	private val rgba: ByteArray,
	private val blockColumns: Int,
	private val blockWords: LongArray,
	private val excluded: List<PixelRect>,
) {
	/**
	 * Whether the page pixel at ([x], [y]) is painted: on the page, outside every excluded
	 * rectangle, and not fully transparent.
	 *
	 * @param Int x The pixel column.
	 * @param Int y The pixel row.
	 * @return Boolean True when painted.
	 */
	fun isPainted(x: Int, y: Int): Boolean {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return false
		}
		for (rect in excluded) {
			if (rect.contains(x, y)) {
				return false
			}
		}
		return (rgba[(y * width + x) * 4 + 3].toInt() and 0xFF) != 0
	}

	/**
	 * Whether the 8x8 block at ([blockX], [blockY]) holds any painted pixel - the cheap rejection the
	 * exact test hides behind.  Excluded rectangles are not applied here; a block that overlaps one may
	 * answer true and the exact test then says no.
	 *
	 * @param Int blockX The block column.
	 * @param Int blockY The block row.
	 * @return Boolean True when the block may be painted.
	 */
	fun blockMayBePainted(blockX: Int, blockY: Int): Boolean {
		val bitIndex = blockY * blockColumns + blockX
		return (blockWords[bitIndex ushr 6] ushr (bitIndex and 63)) and 1L == 1L
	}

	/**
	 * Whether any painted pixel lies inside [region].
	 *
	 * Walks the blocks under the region's bounds, and only for a block that may be painted tests its
	 * pixels exactly against the region and the page.
	 *
	 * @param SampledRegion region The region to test.
	 * @return PixelRect? A one-pixel rectangle at the first painted pixel found, or null when none.
	 */
	fun firstPaintedPixelIn(region: SampledRegion): PixelRect? {
		val bounds = region.bounds
		val startX = floor(bounds.left).toInt().coerceAtLeast(0)
		val endX = ceil(bounds.right).toInt().coerceAtMost(width)
		val startY = floor(bounds.top).toInt().coerceAtLeast(0)
		val endY = ceil(bounds.bottom).toInt().coerceAtMost(height)
		if (startX >= endX || startY >= endY) {
			return null
		}
		for (blockY in (startY / OCCUPANCY_BLOCK_SIDE)..((endY - 1) / OCCUPANCY_BLOCK_SIDE)) {
			for (blockX in (startX / OCCUPANCY_BLOCK_SIDE)..((endX - 1) / OCCUPANCY_BLOCK_SIDE)) {
				if (!blockMayBePainted(blockX, blockY)) {
					continue
				}
				val pixelStartX = maxOf(startX, blockX * OCCUPANCY_BLOCK_SIDE)
				val pixelEndX = minOf(endX, (blockX + 1) * OCCUPANCY_BLOCK_SIDE)
				val pixelStartY = maxOf(startY, blockY * OCCUPANCY_BLOCK_SIDE)
				val pixelEndY = minOf(endY, (blockY + 1) * OCCUPANCY_BLOCK_SIDE)
				for (y in pixelStartY until pixelEndY) {
					for (x in pixelStartX until pixelEndX) {
						if (region.contains(x + 0.5f, y + 0.5f) && isPainted(x, y)) {
							return PixelRect(x, y, x + 1, y + 1)
						}
					}
				}
			}
		}
		return null
	}

	companion object {
		/**
		 * The occupancy of [page], with [excluded] read as empty.
		 *
		 * @param DecodedImage    page     The shown page.
		 * @param List<PixelRect> excluded Rectangles to read as unpainted (the movers' old spots).
		 * @return PageOccupancy The occupancy.
		 */
		fun of(page: DecodedImage, excluded: List<PixelRect>): PageOccupancy {
			val blockColumns = (page.width + OCCUPANCY_BLOCK_SIDE - 1) / OCCUPANCY_BLOCK_SIDE
			val blockRows = (page.height + OCCUPANCY_BLOCK_SIDE - 1) / OCCUPANCY_BLOCK_SIDE
			val blockWords = LongArray((blockColumns * blockRows + 63) ushr 6)
			for (y in 0 until page.height) {
				val rowOffset = y * page.width
				val blockRow = (y / OCCUPANCY_BLOCK_SIDE) * blockColumns
				for (x in 0 until page.width) {
					if (page.rgba[(rowOffset + x) * 4 + 3].toInt() != 0) {
						val bitIndex = blockRow + x / OCCUPANCY_BLOCK_SIDE
						blockWords[bitIndex ushr 6] = blockWords[bitIndex ushr 6] or (1L shl (bitIndex and 63))
					}
				}
			}
			return PageOccupancy(page.width, page.height, page.rgba, blockColumns, blockWords, excluded)
		}
	}
}

/**
 * The coverage of a tile's meshes in the tile's own pixel frame, one bit per pixel over the meshes'
 * bounding box - which may extend past the raster, since a mesh typically overhangs its art.
 *
 * @property LayerBounds bounds The box the bits cover, raster-local (left / top may be negative).
 */
class TileMeshMask private constructor(
	val bounds: LayerBounds,
	private val words: LongArray,
) {
	/**
	 * Whether the tile pixel at ([tileX], [tileY]) is covered by a triangle (within the one-pixel
	 * conservative margin); false outside the box.
	 *
	 * @param Int tileX The pixel column, raster-local.
	 * @param Int tileY The pixel row, raster-local.
	 * @return Boolean True when covered.
	 */
	fun isCovered(tileX: Int, tileY: Int): Boolean {
		val column = tileX - bounds.left
		val row = tileY - bounds.top
		if (column < 0 || row < 0 || column >= bounds.width || row >= bounds.height) {
			return false
		}
		val bitIndex = row * bounds.width + column
		return (words[bitIndex ushr 6] ushr (bitIndex and 63)) and 1L == 1L
	}

	companion object {
		/**
		 * The mask of [triangles] (art-pixel coordinates, nine floats per triangle: three interleaved
		 * (x, y) corners) filled by pixel-center test and dilated by one pixel in every direction, so a
		 * bilinear sampler reading half a texel past any covered point is inside it.  Every corner's own
		 * pixel is marked too, so a sliver too thin to cover a center still contributes.
		 *
		 * @param List<FloatArray> triangles The triangles, art-local.
		 * @return TileMeshMask? The mask, or null when there are no triangles.
		 */
		fun of(triangles: List<FloatArray>): TileMeshMask? {
			if (triangles.isEmpty()) {
				return null
			}
			var minX = Float.POSITIVE_INFINITY
			var minY = Float.POSITIVE_INFINITY
			var maxX = Float.NEGATIVE_INFINITY
			var maxY = Float.NEGATIVE_INFINITY
			for (triangle in triangles) {
				for (cornerIndex in 0 until 3) {
					minX = minOf(minX, triangle[cornerIndex * 2])
					maxX = maxOf(maxX, triangle[cornerIndex * 2])
					minY = minOf(minY, triangle[cornerIndex * 2 + 1])
					maxY = maxOf(maxY, triangle[cornerIndex * 2 + 1])
				}
			}
			// One pixel of margin for the dilation on every side.
			val left = floor(minX).toInt() - 1
			val top = floor(minY).toInt() - 1
			val width = ceil(maxX).toInt() + 1 - left
			val height = ceil(maxY).toInt() + 1 - top
			val raw = LongArray((width * height + 63) ushr 6)

			fun mark(column: Int, row: Int) {
				if (column < 0 || row < 0 || column >= width || row >= height) {
					return
				}
				val bitIndex = row * width + column
				raw[bitIndex ushr 6] = raw[bitIndex ushr 6] or (1L shl (bitIndex and 63))
			}
			for (triangle in triangles) {
				val x0 = triangle[0] - left
				val y0 = triangle[1] - top
				val x1 = triangle[2] - left
				val y1 = triangle[3] - top
				val x2 = triangle[4] - left
				val y2 = triangle[5] - top
				mark(floor(x0).toInt(), floor(y0).toInt())
				mark(floor(x1).toInt(), floor(y1).toInt())
				mark(floor(x2).toInt(), floor(y2).toInt())
				val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
				if (area == 0f) {
					continue
				}
				val startColumn = floor(minOf(x0, x1, x2)).toInt().coerceAtLeast(0)
				val endColumn = ceil(maxOf(x0, x1, x2)).toInt().coerceAtMost(width - 1)
				val startRow = floor(minOf(y0, y1, y2)).toInt().coerceAtLeast(0)
				val endRow = ceil(maxOf(y0, y1, y2)).toInt().coerceAtMost(height - 1)
				val orientation = if (area > 0f) 1f else -1f
				for (row in startRow..endRow) {
					val centerY = row + 0.5f
					for (column in startColumn..endColumn) {
						val centerX = column + 0.5f
						val edge0 = ((x1 - x0) * (centerY - y0) - (y1 - y0) * (centerX - x0)) * orientation
						val edge1 = ((x2 - x1) * (centerY - y1) - (y2 - y1) * (centerX - x1)) * orientation
						val edge2 = ((x0 - x2) * (centerY - y2) - (y0 - y2) * (centerX - x2)) * orientation
						if (edge0 >= 0f && edge1 >= 0f && edge2 >= 0f) {
							mark(column, row)
						}
					}
				}
			}
			// Dilate by one pixel: a bit set anywhere in the 3x3 neighborhood sets the pixel.
			val dilated = LongArray(raw.size)
			for (row in 0 until height) {
				for (column in 0 until width) {
					var covered = false
					for (rowOffset in -1..1) {
						val neighborRow = row + rowOffset
						if (neighborRow < 0 || neighborRow >= height) {
							continue
						}
						for (columnOffset in -1..1) {
							val neighborColumn = column + columnOffset
							if (neighborColumn < 0 || neighborColumn >= width) {
								continue
							}
							val bitIndex = neighborRow * width + neighborColumn
							if ((raw[bitIndex ushr 6] ushr (bitIndex and 63)) and 1L == 1L) {
								covered = true
							}
						}
					}
					if (covered) {
						val bitIndex = row * width + column
						dilated[bitIndex ushr 6] = dilated[bitIndex ushr 6] or (1L shl (bitIndex and 63))
					}
				}
			}
			return TileMeshMask(LayerBounds(left, top, width, height), dilated)
		}
	}
}

/**
 * The triangles of every drawable bound to [tileId], in the tile's own pixel frame - the same
 * uv-to-art conversion [meshReserveByTile] measures with.
 *
 * @param PuppetModel model The model.
 * @param AtlasTileId tileId The tile.
 * @return List<FloatArray> The triangles, nine floats each; empty when nothing measurable is bound.
 */
fun meshTrianglesOf(model: PuppetModel, tileId: AtlasTileId): List<FloatArray> {
	val tile = model.atlas.tileById[tileId] ?: return emptyList()
	val storedToArt = model.atlas.storedToArtAffineForTile(tileId) ?: return emptyList()
	val triangles = ArrayList<FloatArray>()
	for (drawable in model.drawables) {
		if (drawable.atlasTileId != tileId) {
			continue
		}
		val mesh = drawable.mesh ?: continue
		if (mesh.uvs.size < 2 || mesh.indices.size < 3) {
			continue
		}
		val artUvs = applyUvAffine(mesh.uvs, storedToArt)
		val vertexCount = artUvs.size / 2
		var indexOffset = 0
		while (indexOffset + 2 < mesh.indices.size) {
			val cornerA = mesh.indices[indexOffset]
			val cornerB = mesh.indices[indexOffset + 1]
			val cornerC = mesh.indices[indexOffset + 2]
			indexOffset += 3
			if (cornerA >= vertexCount || cornerB >= vertexCount || cornerC >= vertexCount) {
				continue
			}
			triangles.add(
				floatArrayOf(
					artUvs[cornerA * 2] * tile.width,
					artUvs[cornerA * 2 + 1] * tile.height,
					artUvs[cornerB * 2] * tile.width,
					artUvs[cornerB * 2 + 1] * tile.height,
					artUvs[cornerC * 2] * tile.width,
					artUvs[cornerC * 2 + 1] * tile.height,
				),
			)
		}
	}
	return triangles
}

/**
 * The coverage mask of every mesh bound to [tileId], or null when the tile has no measurable mesh.
 *
 * @param PuppetModel model The model.
 * @param AtlasTileId tileId The tile.
 * @return TileMeshMask? The mask.
 */
fun meshMaskOf(model: PuppetModel, tileId: AtlasTileId): TileMeshMask? = TileMeshMask.of(meshTrianglesOf(model, tileId))

/**
 * A tile's sampled region on the page: the coverage of its meshes carried through its placement and
 * tested exactly - a page point maps back into the tile through the placement inverse and reads the
 * coverage bit, so a rotated tile's region stays its true shape.
 *
 * @property AtlasPlacement placement The tile's placement.
 * @property TileMeshMask   mask      The meshes' coverage, raster-local.
 */
class SampledRegion(
	val placement: AtlasPlacement,
	val mask: TileMeshMask,
) {
	private val pageToTile: FloatArray? = inversePlacementAffine(placement)

	/** The region's page-space bounds (the coverage box through the placement). */
	val bounds: PlacementFootprint = placementFootprint(placement, mask.bounds, reserve = null)

	/**
	 * Whether the page point ([pageX], [pageY]) lands on a covered tile pixel.
	 *
	 * @param Float pageX The page x.
	 * @param Float pageY The page y.
	 * @return Boolean True when covered; false for a degenerate placement.
	 */
	fun contains(pageX: Float, pageY: Float): Boolean {
		val inverse = pageToTile ?: return false
		val tileX = inverse[0] * pageX + inverse[1] * pageY + inverse[2]
		val tileY = inverse[3] * pageX + inverse[4] * pageY + inverse[5]
		return mask.isCovered(floor(tileX).toInt(), floor(tileY).toInt())
	}
}

/**
 * Whether [region] samples any opaque texel of [mask] placed at [placement], the composer's extrusion
 * band counted as painted (a page pixel within [extrude] of the trim reads the nearest edge texel).
 *
 * Scans the intersection of the region's bounds with the placed trim's bounds grown by the band, and
 * tests each pixel center both ways: inside the region, and on an opaque texel through the mask
 * placement's inverse.
 *
 * @param SampledRegion  region    The sampling tile's region.
 * @param AtlasPlacement placement Where the painting tile sits.
 * @param TileOpaqueMask mask      The painting tile's opaque texels.
 * @param Int            extrude   The composer's edge extrusion.
 * @return Boolean True when the region samples paint.
 */
fun sampledRegionHitsMask(
	region: SampledRegion,
	placement: AtlasPlacement,
	mask: TileOpaqueMask,
	extrude: Int,
): Boolean {
	val painted = placementFootprint(placement, mask.trim, reserve = null).expanded(extrude.toFloat())
	val bounds = region.bounds
	if (!bounds.overlaps(painted)) {
		return false
	}
	val inverse = inversePlacementAffine(placement) ?: return false
	val forward = placementAffine(placement)
	val startX = floor(maxOf(bounds.left, painted.left)).toInt()
	val endX = ceil(minOf(bounds.right, painted.right)).toInt()
	val startY = floor(maxOf(bounds.top, painted.top)).toInt()
	val endY = ceil(minOf(bounds.bottom, painted.bottom)).toInt()
	val trim = mask.trim
	val trimLeft = trim.left.toFloat()
	val trimTop = trim.top.toFloat()
	val trimRight = (trim.left + trim.width).toFloat()
	val trimBottom = (trim.top + trim.height).toFloat()
	val extrudeSquared = extrude.toFloat() * extrude.toFloat()
	for (y in startY until endY) {
		val centerY = y + 0.5f
		for (x in startX until endX) {
			val centerX = x + 0.5f
			if (!region.contains(centerX, centerY)) {
				continue
			}
			val tileX = inverse[0] * centerX + inverse[1] * centerY + inverse[2]
			val tileY = inverse[3] * centerX + inverse[4] * centerY + inverse[5]
			val clampedX = tileX.coerceIn(trimLeft, trimRight)
			val clampedY = tileY.coerceIn(trimTop, trimBottom)
			if (clampedX != tileX || clampedY != tileY) {
				if (extrude == 0) {
					continue
				}
				val backX = forward[0] * clampedX + forward[1] * clampedY + forward[2]
				val backY = forward[3] * clampedX + forward[4] * clampedY + forward[5]
				val deltaX = backX - centerX
				val deltaY = backY - centerY
				if (deltaX * deltaX + deltaY * deltaY > extrudeSquared) {
					continue
				}
			}
			val texelX = floor(clampedX).toInt().coerceIn(trim.left, trim.left + trim.width - 1)
			val texelY = floor(clampedY).toInt().coerceIn(trim.top, trim.top + trim.height - 1)
			if (mask.isOpaque(texelX, texelY)) {
				return true
			}
		}
	}
	return false
}