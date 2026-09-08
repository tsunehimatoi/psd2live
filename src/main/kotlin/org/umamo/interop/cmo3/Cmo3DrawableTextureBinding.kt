package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine

/**
 * The texture web a synthesized drawable binds to when it has no existing source to clone - the
 * per-page objects the image-chain builder created (docs/format/CMO3.md §4 How a Drawable
 * References its Texture).  Instances are shared per atlas page: every drawable on the page
 * references the SAME GTexture2D and guid objects, so the writer hoists them exactly like the
 * editor's own files (one atlas GTexture2D for all packed drawables).
 */
public class Cmo3DrawableTextureBinding(
	/** The page's shared texture (srcImageResource = the page CImageResource). */
	val texture: GTexture2D,
	/** The page's CTextureAtlas guid - the atlas-region input's target. */
	val textureAtlasGuid: Guid,
	/** The drawable's own CModelImage guid (its patch web), or null when it has no patch. */
	val modelImageGuid: Guid?,
	/** The drawable's fitted atlas-page-to-canvas placement (the region input's transform). */
	val inputImageLocalToCanvasTransform: CAffine,
)

/**
 * Fits a drawable's atlas-page-to-canvas placement transform: the affine mapping page pixel
 * coordinates (uv times page size) onto the drawable's base mesh positions.
 *
 * CMO3: CTextureInput_TextureAtlasRegion field inputImageLocalToCanvasTransform - the editor
 * inverts this to place the mesh over its texture patch in the atlas and mesh-edit views, so an
 * identity here draws every mesh at its assembled canvas position instead.  Cubism-authored
 * mappings are exact per-drawable affines (the layer's canvas translation composed with the
 * packer's scale, rotation, and translate), so a least-squares fit recovers them to float
 * precision; a degenerate mesh falls back to a single-axis regression, then to a pure centroid
 * translation.
 *
 * The moments are accumulated about the point cloud's MEAN, not about the page origin, and that is
 * load-bearing rather than a tidiness preference.  A patch sits thousands of pixels out on a
 * 16384-square page while spanning only a few hundred, so origin-referenced moments are dominated
 * by the offset: the system's conditioning picks up the squared offset-to-spread ratio, the
 * informative pivot falls far below any offset-scaled singularity tolerance, and a perfectly
 * determined fit is rejected as degenerate.  What it degrades to - a per-axis regression - cannot
 * express an off-diagonal term at all, so a drawable the packer rotated comes back squashed onto
 * the wrong axis.  Centering drops the offset term outright: the solve sees only the cloud's own
 * shape, and the translation comes back from the means.
 *
 * A packing that rotates or mirrors the patch is therefore carried faithfully.  The crop the image
 * chain slices out is still the axis-aligned uv bounding box, which for a rotated patch encloses
 * the art plus its surrounding page pixels - that is a property of reconstructing source art from a
 * baked page, and real source art removes it along with the fit itself.
 *
 * This fit exists only because a MOC3-origin export has to RECONSTRUCT the placement a CMO3 would
 * have read straight off its source art - see [Cmo3ImageChainBuilder]'s header for why that whole
 * reconstruction is a stopgap.  A residual survives it by construction for any drawable under a
 * warp deformer: the exported base mesh is the rest pose pushed through the warp's lattice, whose
 * bilinear map is not affine, so no CAffine reproduces it exactly.  The fit is the closest affine,
 * which is what the atlas view needs.
 *
 * @param FloatArray uvs        Interleaved atlas-frame texture coordinates (u then v per vertex).
 * @param FloatArray positions  Interleaved base canvas positions (x then y per vertex).
 * @param Int        pageWidth  The atlas page's pixel width.
 * @param Int        pageHeight The atlas page's pixel height.
 * @return CAffine The fitted transform (identity when there are no vertices).
 */
internal fun fitAtlasPageToCanvasTransform(uvs: FloatArray, positions: FloatArray, pageWidth: Int, pageHeight: Int): CAffine {
	val vertexCount = minOf(uvs.size, positions.size) / 2
	if (vertexCount == 0) {
		return CAffine()
	}
	val pointCount = vertexCount.toDouble()
	var meanPageX = 0.0
	var meanPageY = 0.0
	val meanCanvas = DoubleArray(2)
	for (vertexIndex in 0 until vertexCount) {
		meanPageX += uvs[2 * vertexIndex].toDouble() * pageWidth
		meanPageY += uvs[2 * vertexIndex + 1].toDouble() * pageHeight
		for (axis in 0 until 2) {
			meanCanvas[axis] += positions[2 * vertexIndex + axis].toDouble()
		}
	}
	meanPageX /= pointCount
	meanPageY /= pointCount
	meanCanvas[0] /= pointCount
	meanCanvas[1] /= pointCount
	// Centered second moments: the page cloud's own covariance plus its cross-covariance with each
	// canvas axis.  These are the normal equations of `canvas = a*pageX + b*pageY + t` with t
	// eliminated, which is exactly what centering buys.
	var pageXX = 0.0
	var pageXY = 0.0
	var pageYY = 0.0
	val canvasPageX = DoubleArray(2)
	val canvasPageY = DoubleArray(2)
	for (vertexIndex in 0 until vertexCount) {
		val pageX = uvs[2 * vertexIndex].toDouble() * pageWidth - meanPageX
		val pageY = uvs[2 * vertexIndex + 1].toDouble() * pageHeight - meanPageY
		pageXX += pageX * pageX
		pageXY += pageX * pageY
		pageYY += pageY * pageY
		for (axis in 0 until 2) {
			val canvas = positions[2 * vertexIndex + axis].toDouble() - meanCanvas[axis]
			canvasPageX[axis] += pageX * canvas
			canvasPageY[axis] += pageY * canvas
		}
	}
	val determinant = pageXX * pageYY - pageXY * pageXY
	// Relative test: the page cloud spans two independent directions only when its covariance
	// determinant is a real fraction of the product of its variances.  A patch collapsed to a line
	// (a degenerate mesh) fails here and takes the single-axis path below.
	val hasTwoAxisSpread = determinant > 1e-12 * pageXX * pageYY
	val linear = Array(2) { DoubleArray(2) }
	for (axis in 0 until 2) {
		if (hasTwoAxisSpread) {
			linear[axis][0] = (canvasPageX[axis] * pageYY - canvasPageY[axis] * pageXY) / determinant
			linear[axis][1] = (canvasPageY[axis] * pageXX - canvasPageX[axis] * pageXY) / determinant
			continue
		}
		// Collinear page cloud: regress against whichever page axis actually carries the spread, so
		// a rotated strip still recovers its coefficient instead of dividing by nothing.  The other
		// column is unconstrained by the data and takes its identity value rather than zero - a zero
		// column would make the affine singular, and the editor INVERTS this transform to draw the
		// mesh over its patch.
		if (pageXX >= pageYY && pageXX > 0.0) {
			linear[axis][0] = canvasPageX[axis] / pageXX
			linear[axis][1] = if (axis == 1) 1.0 else 0.0
		} else if (pageYY > 0.0) {
			linear[axis][0] = if (axis == 0) 1.0 else 0.0
			linear[axis][1] = canvasPageY[axis] / pageYY
		} else {
			// Every vertex shares one page pixel: nothing to scale, so place by the centroid alone.
			linear[axis][0] = if (axis == 0) 1.0 else 0.0
			linear[axis][1] = if (axis == 0) 0.0 else 1.0
		}
	}
	// A near-identity linear part is least-squares noise on a pure translation (the editor's own
	// unscaled packings): snap it so the written transform matches the editor's exact-translation
	// shape instead of 0.9999997-style drift.
	if (kotlin.math.abs(linear[0][0] - 1.0) < 1e-3 &&
		kotlin.math.abs(linear[0][1]) < 1e-3 &&
		kotlin.math.abs(linear[1][0]) < 1e-3 &&
		kotlin.math.abs(linear[1][1] - 1.0) < 1e-3
	) {
		linear[0][0] = 1.0
		linear[0][1] = 0.0
		linear[1][0] = 0.0
		linear[1][1] = 1.0
	}
	val transform = CAffine()
	// The translation is whatever carries the page mean onto the canvas mean under the linear part.
	transform.m00 = linear[0][0].toFloat()
	transform.m01 = linear[0][1].toFloat()
	transform.m02 = (meanCanvas[0] - linear[0][0] * meanPageX - linear[0][1] * meanPageY).toFloat()
	transform.m10 = linear[1][0].toFloat()
	transform.m11 = linear[1][1].toFloat()
	transform.m12 = (meanCanvas[1] - linear[1][0] * meanPageX - linear[1][1] * meanPageY).toFloat()
	return transform
}