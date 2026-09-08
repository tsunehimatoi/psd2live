package org.umamo.format.raster

import org.umamo.format.art.AlphaAnalysis
import org.umamo.format.art.DEFAULT_ALPHA_THRESHOLD
import org.umamo.format.art.DEFAULT_CONTOUR_EPSILON
import org.umamo.format.art.analyzeAlpha

/**
 * A whole decoded flat image: RGBA8888, straight (non-premultiplied) alpha, row-major from the top.
 *
 * The in-memory model of the flat-raster codec family (PNG, BMP, JPEG, WebP, TIFF), the neutral
 * pixel form fixed by the art-sourcing roadmap invariant #6.  It is field-compatible with the
 * layered readers' [org.umamo.format.art.LayerRaster] and the renderer's DecodedImage, so the
 * adapters between them are trivial copies (see [rasterToSourceArt]).
 *
 * A plain class, not a `data class`: it wraps a large [rgba] buffer for which a generated structural
 * equals/hashCode would be a footgun (a deep array compare on every call).  Identity equality is the
 * right default - the same rationale as [org.umamo.format.art.LayerRaster].
 */
public class RasterImage(
	public val width: Int,
	public val height: Int,
	public val rgba: ByteArray,
)

/**
 * Analyzes this flat image's opaque region.  See [org.umamo.format.art.analyzeAlpha] for the
 * full contract; this extension lives here rather than in the art package to preserve the
 * one-way raster -> art dependency direction.
 *
 * @param Int alphaThreshold Minimum alpha byte value (1..255) for a pixel to count as opaque.
 * @param Float contourEpsilon Douglas-Peucker tolerance in pixels; 0 keeps the exact lattice rings.
 * @return AlphaAnalysis The opaque-region description, or null when nothing meets the threshold.
 */
public fun RasterImage.analyzeAlpha(
	alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	contourEpsilon: Float = DEFAULT_CONTOUR_EPSILON,
): AlphaAnalysis? = analyzeAlpha(width, height, rgba, alphaThreshold, contourEpsilon)