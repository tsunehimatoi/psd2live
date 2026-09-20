package org.umamo.runtime.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Where a document's source art sits on its atlas pages, and the affine algebra that maps between the
 * two frames.
 *
 * Model state rather than render state: a placement is authored (a repack moves it), so it has to
 * survive undo, diff, and export like any other document content.  It lives here, in the module with no
 * dependencies, because every consumer needs it - the renderer to sample, the UV editor to map, and the
 * CMO3 export to write - and the export path deliberately cannot see the renderer.
 */

/**
 * Where one tile's upright source art sits on an atlas page: the transform the packer applied to it, as
 * translation / scale / rotation.
 *
 * A Transform/Rotation/Scale(TRS) rather than a rect because that is what the source formats actually
 * carry and what the corpus actually uses.  Rotation and scale both occur, and a rect-plus-quarter-turn
 * model would silently discard them on import.  A packer that only ever emits axis-aligned unit-scale
 * placements writes the constrained subset (scale 1, rotation 0) and loses nothing.
 *
 * Rotation is DEGREES, counter-clockwise, about the layer's own origin, applied after scale; the frame
 * is page pixels with y running DOWN (v = 0 is the page's top row), matching how the decoder emits rows
 * and how the sampler addresses them.
 *
 * [pageIndex] indexes [PuppetAtlas.pages], which is the DOCUMENT's page order.  For an IMPORTED
 * document it is deliberately NOT the renderer's page numbering: the two are derived independently
 * (the renderer collects pages in drawable-encounter order and renumbers when one fails to decode),
 * so an index is only ever valid against the list it came from.  A page set Umamo generates itself
 * (a repack's) is built index-parallel to [PuppetAtlas.pages], so for generated sets the two
 * numberings coincide by construction.
 *
 * @property Int   pageIndex       The atlas page the art packs onto, indexing [PuppetAtlas.pages].
 * @property Float positionX       The packing origin's x on the page, in pixels (fractional in real files).
 * @property Float positionY       The packing origin's y on the page, in pixels.
 * @property Float scaleX          The horizontal scale the packer applied (negative mirrors).
 * @property Float scaleY          The vertical scale the packer applied (negative mirrors).
 * @property Float rotationDegrees The rotation the packer applied, counter-clockwise degrees.
 */
data class AtlasPlacement(
	val pageIndex: Int,
	val positionX: Float,
	val positionY: Float,
	val scaleX: Float,
	val scaleY: Float,
	val rotationDegrees: Float,
)

/**
 * One drawable's resolved link to its source art: which tile it samples, and how its stored atlas
 * texture coordinates map back into that tile's own pixel frame.
 *
 * A null [placement] means the drawable renders from the art directly rather than from a packed page -
 * its stored uvs already address the tile image, so the mapping is the identity.  Applying a placement
 * inverse to those would be a double transform.
 *
 * The page dimensions ride here rather than being looked up per call because a binding is resolved once
 * and then applied per vertex, and because a drawable sampling its art directly has no page at all.
 *
 * @property String          layerKey   The tile this drawable samples.
 * @property AtlasPlacement? placement  The packing transform, or null when the drawable samples the art directly.
 * @property Int             pageWidth  The atlas page's width in pixels.
 * @property Int             pageHeight The atlas page's height in pixels.
 */
data class DrawableLayerBinding(
	val layerKey: String,
	val placement: AtlasPlacement?,
	val pageWidth: Int,
	val pageHeight: Int,
)

/**
 * The placement's inverse as a 2x3 affine (m00, m01, m02, m10, m11, m12) mapping page pixels back to
 * layer pixels.
 *
 * The forward transform is `atlas = R(angle) . S . layer + position`, so the inverse is
 * `layer = S^-1 . R(-angle) . (atlas - position)`.  Resolved once per placement so a whole mesh
 * converts without recomputing the trigonometry per vertex.
 *
 * @param AtlasPlacement placement The packing transform to invert.
 * @return FloatArray? The inverse affine, or null when the placement is degenerate (a zero scale).
 */
fun inversePlacementAffine(placement: AtlasPlacement): FloatArray? {
	if (placement.scaleX == 0f || placement.scaleY == 0f) {
		return null
	}
	val radians = placement.rotationDegrees.toDouble() * PI / 180.0
	val cosine = cos(radians)
	val sine = sin(radians)
	// R(-angle) then S^-1, i.e. rows scaled by the inverse scale after the transposed rotation.
	val m00 = (cosine / placement.scaleX).toFloat()
	val m01 = (sine / placement.scaleX).toFloat()
	val m10 = (-sine / placement.scaleY).toFloat()
	val m11 = (cosine / placement.scaleY).toFloat()
	return floatArrayOf(
		m00,
		m01,
		-(m00 * placement.positionX + m01 * placement.positionY),
		m10,
		m11,
		-(m10 * placement.positionX + m11 * placement.positionY),
	)
}

/**
 * The placement's forward transform as a 2x3 affine (m00, m01, m02, m10, m11, m12) mapping layer
 * pixels to page pixels - [atlasPixelOf]'s algebra as a matrix, for callers that compose it with
 * other affines rather than applying it pointwise.
 *
 * @param AtlasPlacement placement The packing transform.
 * @return FloatArray The forward affine.
 */
fun placementAffine(placement: AtlasPlacement): FloatArray {
	val radians = placement.rotationDegrees.toDouble() * PI / 180.0
	val cosine = cos(radians)
	val sine = sin(radians)
	return floatArrayOf(
		(cosine * placement.scaleX).toFloat(),
		(-sine * placement.scaleY).toFloat(),
		placement.positionX,
		(sine * placement.scaleX).toFloat(),
		(cosine * placement.scaleY).toFloat(),
		placement.positionY,
	)
}

/**
 * Composes two 2x3 affines: the result applies [inner] first, then [outer].
 *
 * @param FloatArray outer The affine applied second.
 * @param FloatArray inner The affine applied first.
 * @return FloatArray The composition, a fresh array.
 */
fun composeAffine(outer: FloatArray, inner: FloatArray): FloatArray =
	floatArrayOf(
		outer[0] * inner[0] + outer[1] * inner[3],
		outer[0] * inner[1] + outer[1] * inner[4],
		outer[0] * inner[2] + outer[1] * inner[5] + outer[2],
		outer[3] * inner[0] + outer[4] * inner[3],
		outer[3] * inner[1] + outer[4] * inner[4],
		outer[3] * inner[2] + outer[4] * inner[5] + outer[5],
	)

/**
 * The atlas-page pixel a layer pixel packs to (the forward transform).
 *
 * @param AtlasPlacement placement The packing transform.
 * @param Float layerX The layer-local x in pixels.
 * @param Float layerY The layer-local y in pixels.
 * @return FloatArray The (x, y) page pixel.
 */
fun atlasPixelOf(placement: AtlasPlacement, layerX: Float, layerY: Float): FloatArray {
	val radians = placement.rotationDegrees.toDouble() * PI / 180.0
	val cosine = cos(radians)
	val sine = sin(radians)
	val scaledX = layerX * placement.scaleX
	val scaledY = layerY * placement.scaleY
	return floatArrayOf(
		(cosine * scaledX - sine * scaledY).toFloat() + placement.positionX,
		(sine * scaledX + cosine * scaledY).toFloat() + placement.positionY,
	)
}

/**
 * The layer pixel an atlas-page pixel came from (the inverse transform).
 *
 * @param AtlasPlacement placement The packing transform.
 * @param Float atlasX The page x in pixels.
 * @param Float atlasY The page y in pixels.
 * @return FloatArray? The (x, y) layer pixel, or null when the placement is degenerate.
 */
fun layerPixelOf(placement: AtlasPlacement, atlasX: Float, atlasY: Float): FloatArray? {
	val inverse = inversePlacementAffine(placement) ?: return null
	return floatArrayOf(
		inverse[0] * atlasX + inverse[1] * atlasY + inverse[2],
		inverse[3] * atlasX + inverse[4] * atlasY + inverse[5],
	)
}

/**
 * The whole atlas-uv to layer-uv mapping for a binding, as one 2x3 affine (m00, m01, m02, m10, m11,
 * m12) over NORMALIZED coordinates.
 *
 * The per-vertex chain is uv times the page size, through the placement inverse, divided by the layer's
 * size - all affine, so it folds into a single matrix that any consumer can apply in either direction
 * (see [invertUvAffine]).  A drawable sampling its art directly has stored uvs that already address the
 * tile image, so its mapping is the identity rather than a placement inverse; applying a placement
 * there would be a double transform.
 *
 * @param DrawableLayerBinding binding The drawable's binding.
 * @param Int layerWidth The source tile's width in pixels.
 * @param Int layerHeight The source tile's height in pixels.
 * @return FloatArray? The atlas-uv to layer-uv affine, or null when it cannot be formed.
 */
fun layerUvAffineOf(binding: DrawableLayerBinding, layerWidth: Int, layerHeight: Int): FloatArray? {
	val placement = binding.placement ?: return identityUvAffine()
	if (layerWidth <= 0 || layerHeight <= 0) {
		return null
	}
	val inverse = inversePlacementAffine(placement) ?: return null
	return floatArrayOf(
		inverse[0] * binding.pageWidth / layerWidth,
		inverse[1] * binding.pageHeight / layerWidth,
		inverse[2] / layerWidth,
		inverse[3] * binding.pageWidth / layerHeight,
		inverse[4] * binding.pageHeight / layerHeight,
		inverse[5] / layerHeight,
	)
}

/** The uv affine that changes nothing - the mapping of a drawable whose uvs already address its art. */
fun identityUvAffine(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

/**
 * Inverts a 2x3 uv affine, turning a mapping INTO a frame into the mapping back OUT of it.
 *
 * @param FloatArray affine The affine to invert (m00, m01, m02, m10, m11, m12).
 * @return FloatArray? The inverse, or null when the affine is degenerate (a zero determinant).
 */
fun invertUvAffine(affine: FloatArray): FloatArray? {
	if (affine.size < 6) {
		return null
	}
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
 * Applies a 2x3 uv affine to a whole interleaved (u, v) array.
 *
 * @param FloatArray uvs The coordinates to map.
 * @param FloatArray affine The affine to apply.
 * @return FloatArray The mapped coordinates, a fresh array.
 */
fun applyUvAffine(uvs: FloatArray, affine: FloatArray): FloatArray {
	val mapped = FloatArray(uvs.size)
	var componentIndex = 0
	while (componentIndex + 1 < uvs.size) {
		val u = uvs[componentIndex]
		val v = uvs[componentIndex + 1]
		mapped[componentIndex] = affine[0] * u + affine[1] * v + affine[2]
		mapped[componentIndex + 1] = affine[3] * u + affine[4] * v + affine[5]
		componentIndex += 2
	}
	return mapped
}

/**
 * Maps a drawable's stored texture coordinates into its source tile's own [0,1] frame - the mapping a
 * layer view draws.
 *
 * @param FloatArray atlasUvs The drawable's stored texture coordinates, interleaved (u, v).
 * @param DrawableLayerBinding binding The drawable's binding.
 * @param Int layerWidth The source tile's width in pixels.
 * @param Int layerHeight The source tile's height in pixels.
 * @return FloatArray? The layer-frame uvs, or null when the mapping cannot be formed.
 */
fun layerUvsFromAtlasUvs(
	atlasUvs: FloatArray,
	binding: DrawableLayerBinding,
	layerWidth: Int,
	layerHeight: Int,
): FloatArray? {
	val affine = layerUvAffineOf(binding, layerWidth, layerHeight) ?: return null
	return applyUvAffine(atlasUvs, affine)
}