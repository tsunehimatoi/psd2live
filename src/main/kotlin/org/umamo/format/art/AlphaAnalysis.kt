package org.umamo.format.art

/**
 * Default alpha threshold: a pixel counts as opaque when its alpha byte is at least this value.
 *
 * 1 means any nonzero alpha counts, which keeps the trimmed bounds lossless for the atlas
 * packer: no antialiased edge pixel (alpha 1..10) is ever cut away, so nothing visible can be
 * clipped at an atlas tile boundary.  Consumers wanting a stricter silhouette (the auto-mesh)
 * pass a higher threshold instead of changing this default.
 */
public const val DEFAULT_ALPHA_THRESHOLD: Int = 1

/**
 * Default Douglas-Peucker simplification tolerance for the alpha contour, in pixels.
 *
 * 1.0 collapses the unit staircase that a 45 degree antialiased edge traces (maximum deviation
 * from the true diagonal is sqrt(2)/2, about 0.707 px) into single segments, while the
 * Douglas-Peucker guarantee keeps the simplified polygon within one pixel of the exact mask
 * boundary.  0 keeps the exact lattice ring.
 */
public const val DEFAULT_CONTOUR_EPSILON: Float = 1.0f

/**
 * The opaque-region description of one raster: pixel-tight trimmed bounds, an occupancy
 * summary, and the boundary contour(s) of the thresholded alpha mask.  This is the shared
 * foundation the atlas packer (trimmed rects) and the auto-mesh (silhouette) both consume.
 *
 * A plain class, not a data class: it holds contour point arrays (via [contours]) for which
 * generated structural equality would deep-compare arrays.  Identity equality is the right
 * default, matching [LayerRaster].
 */
public class AlphaAnalysis(
	/**
	 * Pixel-tight bounds of the opaque region, RASTER-LOCAL: left/top are relative to the
	 * raster's own origin, not the source canvas.  Offset by the layer's canvas position via
	 * [opaqueBoundsOnCanvas] when canvas space is needed.
	 */
	public val opaqueBounds: LayerBounds,
	/** Number of pixels whose alpha met the threshold. */
	public val opaquePixelCount: Int,
	/**
	 * Boundary contours of the thresholded alpha mask, raster-local, one per opaque island
	 * plus one per enclosed hole ([AlphaContour.isHole]).  Never empty on a non-null analysis,
	 * and always contains at least one non-hole contour.
	 */
	public val contours: List<AlphaContour>,
) {
	/** Fraction of the trimmed bounds covered by opaque pixels, in 0.0..1.0. */
	public val boundsCoverage: Float
		get() = opaquePixelCount.toFloat() / (opaqueBounds.width.toFloat() * opaqueBounds.height.toFloat())
}

/**
 * One closed boundary loop of the thresholded alpha mask.
 *
 * Points are integer pixel-corner lattice coordinates (x in 0..width, y in 0..height, y down),
 * raster-local: a contour point is a corner BETWEEN pixels, not a pixel center, so pixel
 * (column, row) occupies the unit square [column, column+1] x [row, row+1] and the polygon is
 * watertight and pixel-exact.  Closure is implicit: the last point connects back to the first
 * (they are never equal).  Winding encodes the region side: walking the ring keeps opaque
 * pixels on the right (y-down), so outer contours have positive shoelace area and holes have
 * negative.  [isHole] is derived from the exact ring and is the authoritative flag: the
 * winding redundancy is guaranteed only for exact rings (contourEpsilon 0), because an
 * adversarially self-intersecting simplified ring's shoelace sign may disagree (the known
 * simplifier limitation) — do not re-derive hole-ness from a simplified ring's winding.
 *
 * A contour may touch itself at a saddle corner (a repeated non-consecutive vertex, "weakly
 * simple"); it never crosses itself.
 *
 * A plain class, not a data class: it wraps a point array for which generated structural
 * equality would deep-compare on every call.
 */
public class AlphaContour(
	/** Flattened corner coordinates x0, y0, x1, y1, ...; the final point connects to the first. */
	public val points: IntArray,
	/** True when this loop encloses a transparent hole inside an opaque island. */
	public val isHole: Boolean,
)

/**
 * Analyzes the opaque region of an RGBA8888 straight-alpha raster (top row first): pixel-tight
 * trimmed bounds, opaque pixel count, and the traced boundary contours, optionally simplified.
 *
 * Despite the file's "alpha analysis" theme this is NOT an Edelsbrunner alpha shape (no
 * Delaunay parameter); it is a threshold mask description: a scan for bounds and count, a
 * marching-squares style boundary trace on the pixel-corner lattice, and Douglas-Peucker
 * simplification.  Output is fully deterministic for a given input, which downstream repacking
 * relies on.
 *
 * Returns null if and only if no pixel meets [alphaThreshold] — including the 0x0 raster and
 * the 1x1 transparent placeholders the CLIP/KRA readers emit for empty layers.  Slivers with
 * real opaque pixels (1 px wide or tall) are reported with their true bounds; excluding them
 * is consumer policy, so a genuine hairline art layer is never dropped here.
 *
 * @param Int width Raster width in pixels; may be 0.
 * @param Int height Raster height in pixels; may be 0.
 * @param ByteArray rgba RGBA8888 pixels, straight alpha, row-major from the top row; exactly width * height * 4 bytes.
 * @param Int alphaThreshold Minimum alpha byte value (1..255) for a pixel to count as opaque.
 * @param Float contourEpsilon Douglas-Peucker tolerance in pixels; 0 keeps the exact lattice rings.
 * @return AlphaAnalysis The RASTER-LOCAL opaque-region description (bounds and contours are
 *         relative to the raster's own origin, not the source canvas), or null when nothing
 *         meets the threshold.
 */
public fun analyzeAlpha(
	width: Int,
	height: Int,
	rgba: ByteArray,
	alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	contourEpsilon: Float = DEFAULT_CONTOUR_EPSILON,
): AlphaAnalysis? {
	require(width >= 0 && height >= 0) { "raster dimensions must be non-negative: $width x $height" }
	require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) {
		"rgba size ${rgba.size} does not match $width x $height x 4"
	}
	require(alphaThreshold in 1..255) {
		"alphaThreshold must be in 1..255 (0 marks everything opaque, 256 nothing): $alphaThreshold"
	}
	require(contourEpsilon >= 0f) { "contourEpsilon must be non-negative: $contourEpsilon" }

	// Pass 1: one row-major sweep folds the trimmed bounds and the count, and builds the
	// bit-packed mask the tracer walks.  Packed LongArray, not BooleanArray: an 8192^2 layer
	// is 67M pixels — 8.4 MB packed versus 67 MB boolean, and the tracer needs a second
	// same-geometry bitset (Android heap decides this).
	val pixelCount = width * height
	val maskWords = LongArray((pixelCount + 63) ushr 6)
	var opaquePixelCount = 0
	var minRow = Int.MAX_VALUE
	var maxRow = -1
	var minColumn = Int.MAX_VALUE
	var maxColumn = -1
	var pixelIndex = 0
	for (rowIndex in 0 until height) {
		for (columnIndex in 0 until width) {
			// The `and 0xFF` is load-bearing: Kotlin Byte is signed, so alpha 128..255 reads
			// negative without it and a >= threshold compare silently drops those pixels.
			val alpha = rgba[pixelIndex * 4 + 3].toInt() and 0xFF
			if (alpha >= alphaThreshold) {
				opaquePixelCount++
				maskWords[pixelIndex ushr 6] = maskWords[pixelIndex ushr 6] or (1L shl (pixelIndex and 63))
				if (rowIndex < minRow) {
					minRow = rowIndex
				}
				if (rowIndex > maxRow) {
					maxRow = rowIndex
				}
				if (columnIndex < minColumn) {
					minColumn = columnIndex
				}
				if (columnIndex > maxColumn) {
					maxColumn = columnIndex
				}
			}
			pixelIndex++
		}
	}
	if (opaquePixelCount == 0) {
		return null
	}

	val opaqueBounds =
		LayerBounds(
			left = minColumn,
			top = minRow,
			width = maxColumn - minColumn + 1,
			height = maxRow - minRow + 1,
		)
	val mask = AlphaMask(width, height, maskWords)
	val exactContours = traceAlphaContours(mask, opaqueBounds)
	val contours =
		if (contourEpsilon > 0f) {
			exactContours.map { contour ->
				// simplifyClosedRing returns the input array itself when nothing can drop;
				// reuse the contour object then instead of re-wrapping the same ring.
				val simplifiedPoints = simplifyClosedRing(contour.points, contourEpsilon)
				if (simplifiedPoints === contour.points) {
					contour
				} else {
					AlphaContour(simplifiedPoints, contour.isHole)
				}
			}
		} else {
			exactContours
		}
	return AlphaAnalysis(opaqueBounds, opaquePixelCount, contours)
}

/**
 * Analyzes this layer raster's opaque region.  See the positional [analyzeAlpha] for the full
 * contract.
 *
 * @param Int alphaThreshold Minimum alpha byte value (1..255) for a pixel to count as opaque.
 * @param Float contourEpsilon Douglas-Peucker tolerance in pixels; 0 keeps the exact lattice rings.
 * @return AlphaAnalysis The opaque-region description, or null when nothing meets the threshold.
 */
public fun LayerRaster.analyzeAlpha(
	alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	contourEpsilon: Float = DEFAULT_CONTOUR_EPSILON,
): AlphaAnalysis? = analyzeAlpha(width, height, rgba, alphaThreshold, contourEpsilon)

/**
 * Analyzes this layer's opaque region from its stored raster pixels.
 *
 * Deliberately ignores the layer's compositing properties — opacity, visible, blend, and
 * channelMask describe how the pixels composite, not what is stored, and applying them is
 * consumer policy.  Results are raster-local; use [opaqueBoundsOnCanvas] with
 * [SourceLayer.bounds] for canvas space.
 *
 * @param Int alphaThreshold Minimum alpha byte value (1..255) for a pixel to count as opaque.
 * @param Float contourEpsilon Douglas-Peucker tolerance in pixels; 0 keeps the exact lattice rings.
 * @return AlphaAnalysis The opaque-region description, or null when nothing meets the threshold.
 */
public fun SourceLayer.analyzeAlpha(
	alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	contourEpsilon: Float = DEFAULT_CONTOUR_EPSILON,
): AlphaAnalysis? = raster.analyzeAlpha(alphaThreshold, contourEpsilon)

/**
 * Converts the raster-local trimmed bounds to source-canvas space by offsetting with the
 * layer's canvas position ([SourceLayer.bounds] left/top).
 *
 * @param LayerBounds layerBounds The layer's position on the source canvas.
 * @return LayerBounds The trimmed opaque bounds in source-canvas space.
 */
public fun AlphaAnalysis.opaqueBoundsOnCanvas(layerBounds: LayerBounds): LayerBounds =
	LayerBounds(
		left = layerBounds.left + opaqueBounds.left,
		top = layerBounds.top + opaqueBounds.top,
		width = opaqueBounds.width,
		height = opaqueBounds.height,
	)