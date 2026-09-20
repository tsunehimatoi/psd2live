package org.umamo.format.atlas

import org.umamo.format.art.LayerBounds
import org.umamo.format.raster.RasterImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Pixel composition for the atlas packer: writing one trimmed tile into a page buffer, optionally
 * quarter-turned, and extruding its edge outward into the surrounding gutter - plus the general
 * affine blit a hand-authored placement needs, since a rigger may rotate or scale a tile on the page
 * where the packer never would.
 *
 * Every writer here goes through ONE rule, writeSample: a sample never erases content.  A destination
 * pixel nobody has painted (alpha 0) takes the sample verbatim, transparent or not - which is what keeps
 * the packer's disjoint pages byte-identical, since every pixel it touches is untouched.  Over painted
 * content a transparent sample is skipped, an opaque one replaces (later placements win), a
 * translucent one composites source-over, and an extrusion band - synthetic pixels - never beats real
 * ones.  Hand-authored placements overlap; without the rule a later tile's transparent margin cut a
 * box out of the tile beneath it.
 *
 * Kept apart from the packing geometry so the blit can be read and tested as pixels, which is where
 * a packer's silent bugs live - an orientation flipped, an extrusion off by one, a stride computed
 * from the wrong width.
 */

/**
 * Writes one straight-alpha sample into a page pixel under the content-preserving rule (see the file
 * comment).
 *
 * @param ByteArray page      The destination page, RGBA8888 row-major from the top.
 * @param Int       offset    The destination pixel's byte offset.
 * @param Int       red       The sample's red, 0..255.
 * @param Int       green     The sample's green, 0..255.
 * @param Int       blue      The sample's blue, 0..255.
 * @param Int       alpha     The sample's alpha, 0..255.
 * @param Boolean   synthetic True for an extrusion sample, which only ever fills an unpainted pixel.
 */
private fun writeSample(
	page: ByteArray,
	offset: Int,
	red: Int,
	green: Int,
	blue: Int,
	alpha: Int,
	synthetic: Boolean,
) {
	val destinationAlpha = page[offset + 3].toInt() and 0xFF
	if (destinationAlpha == 0) {
		page[offset] = red.toByte()
		page[offset + 1] = green.toByte()
		page[offset + 2] = blue.toByte()
		page[offset + 3] = alpha.toByte()
		return
	}
	if (synthetic || alpha == 0) {
		return
	}
	if (alpha == 255) {
		page[offset] = red.toByte()
		page[offset + 1] = green.toByte()
		page[offset + 2] = blue.toByte()
		page[offset + 3] = alpha.toByte()
		return
	}
	// Straight-alpha source-over: the sample sits over the earlier content.
	val sourceCoverage = alpha / 255f
	val destinationCoverage = destinationAlpha / 255f * (1f - sourceCoverage)
	val outCoverage = sourceCoverage + destinationCoverage
	page[offset] = compositeChannel(red, page[offset], sourceCoverage, destinationCoverage, outCoverage)
	page[offset + 1] = compositeChannel(green, page[offset + 1], sourceCoverage, destinationCoverage, outCoverage)
	page[offset + 2] = compositeChannel(blue, page[offset + 2], sourceCoverage, destinationCoverage, outCoverage)
	page[offset + 3] = (outCoverage * 255f).roundToInt().coerceIn(0, 255).toByte()
}

/**
 * One color channel of a straight-alpha source-over composite.
 *
 * @param Int   source              The sample's channel, 0..255.
 * @param Byte  destination         The page's channel byte.
 * @param Float sourceCoverage      The sample's alpha as coverage.
 * @param Float destinationCoverage The page's alpha as coverage, already scaled by the sample's transparency.
 * @param Float outCoverage         The composite's coverage (the two summed).
 * @return Byte The composited channel.
 */
private fun compositeChannel(
	source: Int,
	destination: Byte,
	sourceCoverage: Float,
	destinationCoverage: Float,
	outCoverage: Float,
): Byte {
	val destinationValue = destination.toInt() and 0xFF
	val composite = (source * sourceCoverage + destinationValue * destinationCoverage) / outCoverage
	return composite.roundToInt().coerceIn(0, 255).toByte()
}

/**
 * Writes one source raster pixel into a page pixel under the content-preserving rule.
 *
 * @param ByteArray page              The destination page.
 * @param Int       destinationOffset The destination pixel's byte offset.
 * @param ByteArray source            The source raster, RGBA8888.
 * @param Int       sourceOffset      The source pixel's byte offset.
 * @param Boolean   synthetic         True for an extrusion sample.
 */
private fun writeSourcePixel(
	page: ByteArray,
	destinationOffset: Int,
	source: ByteArray,
	sourceOffset: Int,
	synthetic: Boolean,
) {
	writeSample(
		page,
		destinationOffset,
		source[sourceOffset].toInt() and 0xFF,
		source[sourceOffset + 1].toInt() and 0xFF,
		source[sourceOffset + 2].toInt() and 0xFF,
		source[sourceOffset + 3].toInt() and 0xFF,
		synthetic,
	)
}

/**
 * The source raster byte offset of the pixel that lands at a placed tile's (column, row), under the
 * tile's quarter turn - the reader half of [blitTile], shared with the extrusion so both read the
 * same texel.
 *
 * @param Int         sourceWidth  The source raster's row stride in pixels.
 * @param LayerBounds trim         The trimmed sub-rectangle, raster-local.
 * @param Int         column       The placed tile's column (post-rotation frame).
 * @param Int         row          The placed tile's row (post-rotation frame).
 * @param Int         quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 * @return Int The source pixel's byte offset.
 */
private fun sourceOffsetOfPlaced(
	sourceWidth: Int,
	trim: LayerBounds,
	column: Int,
	row: Int,
	quarterTurns: Int,
): Int =
	if (quarterTurns == 0) {
		((trim.top + row) * sourceWidth + trim.left + column) * 4
	} else {
		// A quarter turn sends source (tileX, tileY) to placed (tileY, trimWidth - 1 - tileX).
		((trim.top + column) * sourceWidth + trim.left + trim.width - 1 - row) * 4
	}

/**
 * Copies one trimmed tile into a page buffer under the content-preserving rule.
 *
 * A quarter turn is COUNTER-CLOCKWISE in the page's y-down frame, matching how a placement records
 * rotation: source pixel (tileX, tileY) lands at (destinationX + tileY, destinationY + trimWidth - 1
 * - tileX), so the destination footprint is trimHeight wide by trimWidth tall.
 *
 * @param ByteArray   page         The destination page, RGBA8888 row-major from the top.
 * @param Int         pageWidth    The page width in pixels.
 * @param ByteArray   sourceRgba   The source raster, RGBA8888 row-major from the top.
 * @param Int         sourceWidth  The source raster's width in pixels (its row stride).
 * @param LayerBounds trim         The opaque sub-rectangle of the source to copy, raster-local.
 * @param Int         destinationX The tile's left edge on the page.
 * @param Int         destinationY The tile's top edge on the page.
 * @param Int         quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 */
internal fun blitTile(
	page: ByteArray,
	pageWidth: Int,
	sourceRgba: ByteArray,
	sourceWidth: Int,
	trim: LayerBounds,
	destinationX: Int,
	destinationY: Int,
	quarterTurns: Int,
) {
	val placedWidth = if (quarterTurns == 0) trim.width else trim.height
	val placedHeight = if (quarterTurns == 0) trim.height else trim.width
	for (row in 0 until placedHeight) {
		val destinationRowOffset = ((destinationY + row) * pageWidth + destinationX) * 4
		for (column in 0 until placedWidth) {
			writeSourcePixel(
				page,
				destinationRowOffset + column * 4,
				sourceRgba,
				sourceOffsetOfPlaced(sourceWidth, trim, column, row, quarterTurns),
				synthetic = false,
			)
		}
	}
}

/**
 * Replicates a placed tile's edge pixels outward into the surrounding gutter.
 *
 * Bilinear sampling at a tile's border reads half a texel past it; without this the neighboring
 * tile's artwork bleeds in.  The edge is read from the SOURCE raster, not from the page: under the
 * content-preserving rule a tile's transparent edge may not have landed on the page at all, and the
 * band must replicate the tile's own texel, never whatever another tile left there.  The caller
 * guarantees room by reserving a gutter of at least [extrude] on every side, so no clamping against
 * the page edge is needed here - and a silent clamp would hide exactly the arithmetic mistake this is
 * most likely to make.  Band pixels are synthetic: they fill unpainted pixels only.
 *
 * @param ByteArray   page         The destination page, RGBA8888 row-major from the top.
 * @param Int         pageWidth    The page width in pixels.
 * @param ByteArray   sourceRgba   The source raster, RGBA8888 row-major from the top.
 * @param Int         sourceWidth  The source raster's row stride in pixels.
 * @param LayerBounds trim         The trimmed sub-rectangle, raster-local.
 * @param Int         tileX        The placed tile's left edge on the page.
 * @param Int         tileY        The placed tile's top edge on the page.
 * @param Int         quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 * @param Int         extrude      How many pixels of edge color to replicate on every side.
 */
internal fun extrudeTileEdges(
	page: ByteArray,
	pageWidth: Int,
	sourceRgba: ByteArray,
	sourceWidth: Int,
	trim: LayerBounds,
	tileX: Int,
	tileY: Int,
	quarterTurns: Int,
	extrude: Int,
) {
	val tileWidth = if (quarterTurns == 0) trim.width else trim.height
	val tileHeight = if (quarterTurns == 0) trim.height else trim.width
	if (extrude <= 0 || tileWidth <= 0 || tileHeight <= 0) {
		return
	}
	for (rowOffset in -extrude until tileHeight + extrude) {
		val clampedRow = rowOffset.coerceIn(0, tileHeight - 1)
		val insideVertically = rowOffset == clampedRow
		for (columnOffset in -extrude until tileWidth + extrude) {
			val clampedColumn = columnOffset.coerceIn(0, tileWidth - 1)
			if (insideVertically && columnOffset == clampedColumn) {
				continue
			}
			writeSourcePixel(
				page,
				((tileY + rowOffset) * pageWidth + tileX + columnOffset) * 4,
				sourceRgba,
				sourceOffsetOfPlaced(sourceWidth, trim, clampedColumn, clampedRow, quarterTurns),
				synthetic = true,
			)
		}
	}
}

/**
 * Composes atlas pages from already-decided placements.
 *
 * This is the packer's own composition loop opened to callers that hold placements rather than a
 * pack request: [packAtlas] composes through here, and a repack's undo re-derives an earlier page
 * set through here, so the two can never drift apart.  Placement footprints including their
 * extrusion are pairwise disjoint (every placement reserves at least [extrude] of gutter on every
 * side, which the bounds checks below enforce against the page and the packer enforced between
 * tiles), so the composition is order-independent.
 *
 * @param IntArray pageWidths  Page widths in pixels, indexed by [AtlasPackPlacement.pageIndex].
 * @param IntArray pageHeights Page heights in pixels, index-parallel to [pageWidths].
 * @param List     items       The tiles' pixels; every placement's key must resolve here.
 * @param List     placements  Where each tile goes.
 * @param Int      extrude     How many pixels of each tile's edge color are replicated into the gutter.
 * @return List The composed pages, RGBA8888, in page-index order.
 */
public fun composeAtlasPages(
	pageWidths: IntArray,
	pageHeights: IntArray,
	items: List<AtlasPackItem>,
	placements: List<AtlasPackPlacement>,
	extrude: Int,
): List<RasterImage> {
	require(pageWidths.size == pageHeights.size) {
		"page width and height lists must be index-parallel: ${pageWidths.size} vs ${pageHeights.size}"
	}
	require(extrude >= 0) { "extrude must be non-negative: $extrude" }
	val itemByKey = items.associateBy { item -> item.key }
	require(itemByKey.size == items.size) { "atlas pack item keys must be unique" }
	val pageBuffers = List(pageWidths.size) { pageIndex -> ByteArray(pageWidths[pageIndex] * pageHeights[pageIndex] * 4) }
	for (placement in placements) {
		val item = requireNotNull(itemByKey[placement.key]) { "placement '${placement.key}' has no item" }
		require(placement.quarterTurns in 0..1) {
			"placement '${placement.key}' has an unsupported rotation: ${placement.quarterTurns} quarter turns"
		}
		require(
			placement.trimLeft >= 0 &&
				placement.trimTop >= 0 &&
				placement.trimLeft + placement.trimWidth <= item.width &&
				placement.trimTop + placement.trimHeight <= item.height,
		) {
			"placement '${placement.key}' trims outside its ${item.width}x${item.height} raster"
		}
		val pageWidth = pageWidths.getOrNull(placement.pageIndex)
		val pageHeight = pageHeights.getOrNull(placement.pageIndex)
		require(pageWidth != null && pageHeight != null) {
			"placement '${placement.key}' names page ${placement.pageIndex} of ${pageWidths.size}"
		}
		require(
			placement.pageX - extrude >= 0 &&
				placement.pageY - extrude >= 0 &&
				placement.pageX + placement.pageWidth + extrude <= pageWidth &&
				placement.pageY + placement.pageHeight + extrude <= pageHeight,
		) {
			"placement '${placement.key}' plus its $extrude px extrusion falls outside its ${pageWidth}x$pageHeight page"
		}
		val trim = LayerBounds(placement.trimLeft, placement.trimTop, placement.trimWidth, placement.trimHeight)
		blitTile(
			page = pageBuffers[placement.pageIndex],
			pageWidth = pageWidth,
			sourceRgba = item.rgba,
			sourceWidth = item.width,
			trim = trim,
			destinationX = placement.pageX,
			destinationY = placement.pageY,
			quarterTurns = placement.quarterTurns,
		)
		extrudeTileEdges(
			page = pageBuffers[placement.pageIndex],
			pageWidth = pageWidth,
			sourceRgba = item.rgba,
			sourceWidth = item.width,
			trim = trim,
			tileX = placement.pageX,
			tileY = placement.pageY,
			quarterTurns = placement.quarterTurns,
			extrude = extrude,
		)
	}
	return pageBuffers.mapIndexed { pageIndex, buffer -> RasterImage(pageWidths[pageIndex], pageHeights[pageIndex], buffer) }
}

/**
 * One tile placed on a page by an arbitrary 2x3 affine over tile pixels - the shape a hand-authored
 * placement takes once a rigger has rotated or scaled it, which [AtlasPackPlacement]'s integer rect
 * plus quarter turn cannot hold.
 *
 * @property String      key        The tile's key into the items list.
 * @property Int         pageIndex  The page the tile lands on.
 * @property LayerBounds trim       The opaque sub-rectangle of the tile's raster that is drawn, raster-local.
 * @property FloatArray  tileToPage The affine (m00, m01, m02, m10, m11, m12) mapping tile pixels to
 *   page pixels, both frames y-down with pixel (x, y) spanning [x, x + 1) by [y, y + 1).
 */
public class AtlasTilePlacement(
	public val key: String,
	public val pageIndex: Int,
	public val trim: LayerBounds,
	public val tileToPage: FloatArray,
) {
	init {
		require(tileToPage.size == 6) { "placement '$key' needs a 2x3 affine, got ${tileToPage.size} components" }
	}
}

/**
 * Composes atlas pages from placements given as affines - [composeAtlasPages] for tiles that may sit
 * rotated, scaled, off the pixel grid, or partly off the page.
 *
 * A placement that is an exact integer translation, or an exact counter-clockwise quarter turn on
 * integer pixels, with room for its extrusion takes the packer's own blit and edge extrusion, so a
 * page composed here from a repack's placements is byte-identical to the page the packer composed,
 * turned tiles included.  Every other placement is resampled: each page pixel inside the
 * tile's footprint (its trim rectangle through the affine) reads the tile bilinearly at the pixel
 * center's pre-image, and each page pixel within [extrude] pixels outside the footprint reads the
 * nearest edge of the trim - the packer's edge replication, expressed so it holds under rotation.
 * Pixels that fall off the page are dropped rather than refused: the placement is a rigger's authored
 * choice, and clipping is what the page would show.
 *
 * Unlike the packer's placements, hand-authored footprints may overlap; they paint in list order
 * under the content-preserving rule (a later transparent sample never erases an earlier tile, a later
 * opaque one wins).
 *
 * @param IntArray pageWidths  Page widths in pixels, indexed by [AtlasTilePlacement.pageIndex].
 * @param IntArray pageHeights Page heights in pixels, index-parallel to [pageWidths].
 * @param List     items       The tiles' pixels; every placement's key must resolve here.
 * @param List     placements  Where each tile goes.
 * @param Int      extrude     How many pixels of each tile's edge color are replicated outward.
 * @return List The composed pages, RGBA8888 straight alpha, in page-index order.
 */
public fun composeAtlasPagesAffine(
	pageWidths: IntArray,
	pageHeights: IntArray,
	items: List<AtlasPackItem>,
	placements: List<AtlasTilePlacement>,
	extrude: Int,
): List<RasterImage> {
	require(pageWidths.size == pageHeights.size) {
		"page width and height lists must be index-parallel: ${pageWidths.size} vs ${pageHeights.size}"
	}
	require(extrude >= 0) { "extrude must be non-negative: $extrude" }
	val itemByKey = items.associateBy { item -> item.key }
	require(itemByKey.size == items.size) { "atlas pack item keys must be unique" }
	val pageBuffers = List(pageWidths.size) { pageIndex -> ByteArray(pageWidths[pageIndex] * pageHeights[pageIndex] * 4) }
	for (placement in placements) {
		val item = requireNotNull(itemByKey[placement.key]) { "placement '${placement.key}' has no item" }
		val trim = placement.trim
		require(
			trim.left >= 0 &&
				trim.top >= 0 &&
				trim.left + trim.width <= item.width &&
				trim.top + trim.height <= item.height,
		) {
			"placement '${placement.key}' trims outside its ${item.width}x${item.height} raster"
		}
		val pageWidth = pageWidths.getOrNull(placement.pageIndex)
		val pageHeight = pageHeights.getOrNull(placement.pageIndex)
		require(pageWidth != null && pageHeight != null) {
			"placement '${placement.key}' names page ${placement.pageIndex} of ${pageWidths.size}"
		}
		if (trim.width <= 0 || trim.height <= 0) {
			continue
		}
		paintTilePlacement(pageBuffers[placement.pageIndex], pageWidth, pageHeight, item, trim, placement.tileToPage, extrude)
	}
	return pageBuffers.mapIndexed { pageIndex, buffer -> RasterImage(pageWidths[pageIndex], pageHeights[pageIndex], buffer) }
}

/**
 * Paints one tile onto a page through its affine: the packer's own blit and edge extrusion when the
 * affine is one of the packer's exact placements with room for the band, else the resampling blit.
 *
 * The ONE path a hand-authored placement takes, whether [composeAtlasPagesAffine] derives it from a
 * model or [packAtlas] keeps it as a fixed tile beside the tiles it packed, so a page the packer
 * composed and the page a derivation composes from the same placement cannot differ.
 *
 * @param ByteArray     page       The destination page, RGBA8888 row-major from the top.
 * @param Int           pageWidth  The page width in pixels.
 * @param Int           pageHeight The page height in pixels.
 * @param AtlasPackItem item       The tile's pixels.
 * @param LayerBounds   trim       The opaque sub-rectangle of the tile that is drawn, raster-local.
 * @param FloatArray    tileToPage The affine mapping tile pixels to page pixels.
 * @param Int           extrude    How many pixels of edge color to replicate outward.
 */
internal fun paintTilePlacement(
	page: ByteArray,
	pageWidth: Int,
	pageHeight: Int,
	item: AtlasPackItem,
	trim: LayerBounds,
	tileToPage: FloatArray,
	extrude: Int,
) {
	val exact = exactPlacement(tileToPage, trim)
	if (exact != null &&
		exact.originX - extrude >= 0 &&
		exact.originY - extrude >= 0 &&
		exact.originX + exact.placedWidth(trim) + extrude <= pageWidth &&
		exact.originY + exact.placedHeight(trim) + extrude <= pageHeight
	) {
		blitTile(page, pageWidth, item.rgba, item.width, trim, exact.originX, exact.originY, exact.quarterTurns)
		extrudeTileEdges(page, pageWidth, item.rgba, item.width, trim, exact.originX, exact.originY, exact.quarterTurns, extrude)
	} else {
		blitTileAffine(page, pageWidth, pageHeight, item.rgba, item.width, trim, tileToPage, extrude)
	}
}

/**
 * The page-space bounding box of a tile-local rectangle through an affine - its four corners mapped
 * and their extremes taken, so a rotated rectangle's box is the box of its turned corners.
 *
 * @param FloatArray affine The affine (m00, m01, m02, m10, m11, m12) mapping tile pixels to page pixels.
 * @param Float      left   The rectangle's left edge, tile-local.
 * @param Float      top    The rectangle's top edge.
 * @param Float      right  The rectangle's right edge, exclusive.
 * @param Float      bottom The rectangle's bottom edge, exclusive.
 * @return FloatArray The box as (left, top, right, bottom) in continuous page pixels.
 */
internal fun affineBounds(
	affine: FloatArray,
	left: Float,
	top: Float,
	right: Float,
	bottom: Float,
): FloatArray {
	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	for (cornerIndex in 0 until 4) {
		val cornerX = if (cornerIndex and 1 == 0) left else right
		val cornerY = if (cornerIndex and 2 == 0) top else bottom
		val pageX = affine[0] * cornerX + affine[1] * cornerY + affine[2]
		val pageY = affine[3] * cornerX + affine[4] * cornerY + affine[5]
		minX = minOf(minX, pageX)
		minY = minOf(minY, pageY)
		maxX = maxOf(maxX, pageX)
		maxY = maxOf(maxY, pageY)
	}
	return floatArrayOf(minX, minY, maxX, maxY)
}

/**
 * How far a rotation term may sit from zero and still read as one of the packer's quarter turns.
 *
 * A placement rotated by -90 degrees carries cos(-pi / 2) in its diagonal, which is near zero in
 * float, not zero; the sine terms are exact.  Anything a real hand rotation produces is orders of
 * magnitude farther from a quarter turn than this.
 */
private const val EXACT_TURN_EPSILON = 1e-6f

/**
 * A placement the packer's own blit serves: the page pixel the placed tile's top-left lands on and
 * how the tile is turned there.
 *
 * @property Int originX      The placed tile's left edge on the page.
 * @property Int originY      The placed tile's top edge on the page.
 * @property Int quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 */
private class ExactPlacement(
	val originX: Int,
	val originY: Int,
	val quarterTurns: Int,
) {
	/**
	 * The placed footprint's width for [trim]: a turn swaps the trim's sides.
	 *
	 * @param LayerBounds trim The tile's drawn sub-rectangle.
	 * @return Int The on-page width in pixels.
	 */
	fun placedWidth(trim: LayerBounds): Int = if (quarterTurns == 0) trim.width else trim.height

	/**
	 * The placed footprint's height for [trim].
	 *
	 * @param LayerBounds trim The tile's drawn sub-rectangle.
	 * @return Int The on-page height in pixels.
	 */
	fun placedHeight(trim: LayerBounds): Int = if (quarterTurns == 0) trim.height else trim.width
}

/**
 * Classifies [placement] as one the packer's blit serves - an exact integer translation, or an exact
 * counter-clockwise quarter turn landing on integer pixels - else null.
 *
 * The turned case is the affine the packer's own turn induces over the untrimmed tile frame: it sends
 * trim-local (u, v) to (originX + v, originY + trimWidth - u), so the placed top-left sits at
 * (m02 + trim.top, m12 - trim.left - trim.width).  Recognising it here is what lets a repack that
 * turned a tile derive byte-identical pages instead of resampling through the bilinear path, whose
 * extrusion band rounds corners differently from the packer's.
 *
 * @param FloatArray  affine The tile-to-page affine to classify.
 * @param LayerBounds trim   The tile's drawn sub-rectangle, raster-local.
 * @return ExactPlacement? The blit origin and turn, or null when the affine scales, rotates by
 *   anything but a packer turn, or lands between pixels.
 */
private fun exactPlacement(affine: FloatArray, trim: LayerBounds): ExactPlacement? {
	val quarterTurns: Int
	val originX: Float
	val originY: Float
	if (affine[0] == 1f && affine[1] == 0f && affine[3] == 0f && affine[4] == 1f) {
		quarterTurns = 0
		originX = affine[2] + trim.left
		originY = affine[5] + trim.top
	} else if (abs(affine[0]) <= EXACT_TURN_EPSILON && affine[1] == 1f && affine[3] == -1f && abs(affine[4]) <= EXACT_TURN_EPSILON) {
		quarterTurns = 1
		originX = affine[2] + trim.top
		originY = affine[5] - trim.left - trim.width
	} else {
		return null
	}
	val pixelX = originX.roundToInt()
	val pixelY = originY.roundToInt()
	if (pixelX.toFloat() != originX || pixelY.toFloat() != originY) {
		return null
	}
	return ExactPlacement(pixelX, pixelY, quarterTurns)
}

/**
 * Inverts a 2x3 affine, or null when it is singular.
 *
 * @param FloatArray affine The affine (m00, m01, m02, m10, m11, m12).
 * @return FloatArray? The inverse.
 */
private fun invertAffine2x3(affine: FloatArray): FloatArray? {
	val determinant = affine[0] * affine[4] - affine[1] * affine[3]
	if (determinant == 0f) {
		return null
	}
	val m00 = affine[4] / determinant
	val m01 = -affine[1] / determinant
	val m10 = -affine[3] / determinant
	val m11 = affine[0] / determinant
	return floatArrayOf(
		m00,
		m01,
		-(m00 * affine[2] + m01 * affine[5]),
		m10,
		m11,
		-(m10 * affine[2] + m11 * affine[5]),
	)
}

/**
 * Resamples one trimmed tile onto a page through an affine, clipping to the page.
 *
 * Runs over the footprint's bounding box grown by [extrude] and inverse-maps each page pixel center
 * into the tile.  A center inside the trim samples the tile bilinearly there; a center outside it is
 * clamped to the trim's nearest point and drawn only when that point, mapped back to the page, lies
 * within [extrude] pixels - which paints the packer's edge extrusion as a band of constant width around
 * the footprint whatever its orientation, and leaves everything farther out untouched.  Both go
 * through the content-preserving rule; the band as synthetic.
 *
 * @param ByteArray   page        The destination page, RGBA8888 row-major from the top.
 * @param Int         pageWidth   The page width in pixels.
 * @param Int         pageHeight  The page height in pixels.
 * @param ByteArray   sourceRgba  The source raster, RGBA8888 row-major from the top.
 * @param Int         sourceWidth The source raster's width in pixels (its row stride).
 * @param LayerBounds trim        The opaque sub-rectangle of the source to draw, raster-local.
 * @param FloatArray  tileToPage  The affine mapping tile pixels to page pixels.
 * @param Int         extrude     How many pixels of edge color to replicate outward.
 */
internal fun blitTileAffine(
	page: ByteArray,
	pageWidth: Int,
	pageHeight: Int,
	sourceRgba: ByteArray,
	sourceWidth: Int,
	trim: LayerBounds,
	tileToPage: FloatArray,
	extrude: Int,
) {
	val pageToTile = invertAffine2x3(tileToPage) ?: return
	val trimLeft = trim.left.toFloat()
	val trimTop = trim.top.toFloat()
	val trimRight = (trim.left + trim.width).toFloat()
	val trimBottom = (trim.top + trim.height).toFloat()
	// The footprint's page-space bounding box: the four trim corners through the affine.
	val bounds = affineBounds(tileToPage, trimLeft, trimTop, trimRight, trimBottom)
	val startColumn = floor(bounds[0] - extrude).toInt().coerceAtLeast(0)
	val endColumn = ceil(bounds[2] + extrude).toInt().coerceAtMost(pageWidth)
	val startRow = floor(bounds[1] - extrude).toInt().coerceAtLeast(0)
	val endRow = ceil(bounds[3] + extrude).toInt().coerceAtMost(pageHeight)
	if (startColumn >= endColumn || startRow >= endRow) {
		return
	}
	val extrudeSquared = extrude.toFloat() * extrude.toFloat()
	val lastColumn = trim.left + trim.width - 1
	val lastRow = trim.top + trim.height - 1
	val sample = IntArray(4)
	for (pageRow in startRow until endRow) {
		val centerY = pageRow + 0.5f
		for (pageColumn in startColumn until endColumn) {
			val centerX = pageColumn + 0.5f
			val tileX = pageToTile[0] * centerX + pageToTile[1] * centerY + pageToTile[2]
			val tileY = pageToTile[3] * centerX + pageToTile[4] * centerY + pageToTile[5]
			val clampedX = tileX.coerceIn(trimLeft, trimRight)
			val clampedY = tileY.coerceIn(trimTop, trimBottom)
			val outsideTrim = clampedX != tileX || clampedY != tileY
			if (outsideTrim) {
				if (extrude == 0) {
					continue
				}
				val backX = tileToPage[0] * clampedX + tileToPage[1] * clampedY + tileToPage[2]
				val backY = tileToPage[3] * clampedX + tileToPage[4] * clampedY + tileToPage[5]
				val deltaX = backX - centerX
				val deltaY = backY - centerY
				if (deltaX * deltaX + deltaY * deltaY > extrudeSquared) {
					continue
				}
			}
			sampleBilinearStraight(sourceRgba, sourceWidth, trim.left, trim.top, lastColumn, lastRow, clampedX, clampedY, sample)
			writeSample(page, (pageRow * pageWidth + pageColumn) * 4, sample[0], sample[1], sample[2], sample[3], synthetic = outsideTrim)
		}
	}
}

/**
 * Samples the tile's color at a continuous tile-space point, bilinearly over the four surrounding
 * texels with the taps clamped into the trim.
 *
 * Straight-alpha texels interpolate in premultiplied space and convert back: averaging straight RGB
 * across an alpha edge drags the matte color into the visible pixels and darkens the fringe.  A point
 * that sits exactly on a texel center copies that texel verbatim, so an integer-aligned placement
 * (a quarter turn, an exact translation the packer's blit could not take) stays byte-exact.
 *
 * @param ByteArray source      The source raster, RGBA8888 row-major from the top.
 * @param Int       sourceWidth The source raster's row stride in pixels.
 * @param Int       firstColumn The trim's first column (taps clamp here).
 * @param Int       firstRow    The trim's first row.
 * @param Int       lastColumn  The trim's last column, inclusive.
 * @param Int       lastRow     The trim's last row, inclusive.
 * @param Float     sampleX     The tile-space x to sample (texel (x, y) spans [x, x + 1)).
 * @param Float     sampleY     The tile-space y to sample.
 * @param IntArray  out         Receives the sample as (red, green, blue, alpha), 0..255.
 */
private fun sampleBilinearStraight(
	source: ByteArray,
	sourceWidth: Int,
	firstColumn: Int,
	firstRow: Int,
	lastColumn: Int,
	lastRow: Int,
	sampleX: Float,
	sampleY: Float,
	out: IntArray,
) {
	val x = sampleX - 0.5f
	val y = sampleY - 0.5f
	val floorX = floor(x)
	val floorY = floor(y)
	val fractionX = x - floorX
	val fractionY = y - floorY
	val column0 = floorX.toInt().coerceIn(firstColumn, lastColumn)
	val row0 = floorY.toInt().coerceIn(firstRow, lastRow)
	if (fractionX == 0f && fractionY == 0f) {
		val sourceOffset = (row0 * sourceWidth + column0) * 4
		out[0] = source[sourceOffset].toInt() and 0xFF
		out[1] = source[sourceOffset + 1].toInt() and 0xFF
		out[2] = source[sourceOffset + 2].toInt() and 0xFF
		out[3] = source[sourceOffset + 3].toInt() and 0xFF
		return
	}
	val column1 = (floorX.toInt() + 1).coerceIn(firstColumn, lastColumn)
	val row1 = (floorY.toInt() + 1).coerceIn(firstRow, lastRow)
	val weight00 = (1f - fractionX) * (1f - fractionY)
	val weight10 = fractionX * (1f - fractionY)
	val weight01 = (1f - fractionX) * fractionY
	val weight11 = fractionX * fractionY
	var red = 0f
	var green = 0f
	var blue = 0f
	var alpha = 0f
	var straightRed = 0f
	var straightGreen = 0f
	var straightBlue = 0f
	for (tapIndex in 0 until 4) {
		val column = if (tapIndex and 1 == 0) column0 else column1
		val row = if (tapIndex and 2 == 0) row0 else row1
		val weight =
			when (tapIndex) {
				0 -> weight00
				1 -> weight10
				2 -> weight01
				else -> weight11
			}
		if (weight == 0f) {
			continue
		}
		val offset = (row * sourceWidth + column) * 4
		val tapRed = (source[offset].toInt() and 0xFF).toFloat()
		val tapGreen = (source[offset + 1].toInt() and 0xFF).toFloat()
		val tapBlue = (source[offset + 2].toInt() and 0xFF).toFloat()
		val tapAlpha = (source[offset + 3].toInt() and 0xFF).toFloat()
		red += weight * tapRed * tapAlpha
		green += weight * tapGreen * tapAlpha
		blue += weight * tapBlue * tapAlpha
		alpha += weight * tapAlpha
		straightRed += weight * tapRed
		straightGreen += weight * tapGreen
		straightBlue += weight * tapBlue
	}
	if (alpha > 0f) {
		out[0] = (red / alpha).roundToInt().coerceIn(0, 255)
		out[1] = (green / alpha).roundToInt().coerceIn(0, 255)
		out[2] = (blue / alpha).roundToInt().coerceIn(0, 255)
	} else {
		// Fully transparent: keep the matte color the source carries rather than inventing black.
		out[0] = straightRed.roundToInt().coerceIn(0, 255)
		out[1] = straightGreen.roundToInt().coerceIn(0, 255)
		out[2] = straightBlue.roundToInt().coerceIn(0, 255)
	}
	out[3] = alpha.roundToInt().coerceIn(0, 255)
}