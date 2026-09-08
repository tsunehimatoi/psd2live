package org.umamo.render

import org.umamo.runtime.model.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where one drawable's upright source art sits on an atlas page: the transform the packer applied to
 * it, as translation / scale / rotation.
 *
 * A Transform/Rotation/Scale(TRS) rather than a rect because that is what the source formats
 * actually carry and what the corpus actually uses.  Rotation and scale both occur, and a
 * rect-plus-quarter-turn model would silently discard them on import.  A packer that only ever emits
 * axis-aligned unit-scale placements writes the constrained subset (scale 1, rotation 0) and loses
 * nothing.
 *
 * Rotation is DEGREES, counter-clockwise, about the layer's own origin, applied after scale; the
 * frame is page pixels with y running DOWN (v = 0 is the page's top row), matching how the decoder
 * emits rows and how the sampler addresses them.
 *
 * @property Int pageIndex The atlas page the art packs onto, in the source document's page order.
 * @property Float positionX The packing origin's x on the page, in pixels (fractional in real files).
 * @property Float positionY The packing origin's y on the page, in pixels.
 * @property Float scaleX The horizontal scale the packer applied (negative mirrors).
 * @property Float scaleY The vertical scale the packer applied (negative mirrors).
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
 * One source layer the editor can show: the artwork a drawable was authored against, before packing.
 *
 * Identity and size only - the pixels come from [LayerTextures.rasterFor] on demand, because a real
 * model carries hundreds of these and decoding them all at document load would stall the open.
 *
 * @property String key The layer's stable document-local identifier(the join key bindings reference).
 * @property String name The layer's display name.
 * @property Int width The layer image's width in pixels.
 * @property Int height The layer image's height in pixels.
 * @property List<String> boundDrawableIds Every drawable that samples this layer, in document order.
 * @property String? sourceLayerName The originating artwork layer's name when exactly one composites
 *   into this image, else null (recorded for a future source binding; unused today).
 */
data class SourceLayerEntry(
	val key: String,
	val name: String,
	val width: Int,
	val height: Int,
	val boundDrawableIds: List<String>,
	val sourceLayerName: String?,
)

/**
 * One drawable's recovered link to its source art: which layer it samples, and how its stored atlas
 * texture coordinates map back into that layer's own pixel frame.
 *
 * A null [placement] means the drawable was never packed - its stored uvs already address the layer
 * image directly, so recovery is the identity.  Applying a placement inverse to those would be a
 * double transform.
 *
 * The page dimensions ride here rather than being looked up from [PuppetTextures] because the two
 * page orders are independently derived (PuppetTextures collects pages in drawable-encounter order
 * and skips undecodable ones), so an index shared between them cannot be assumed to agree.  Recovery
 * is self-contained as a result.
 *
 * @property String layerKey The [SourceLayerEntry] this drawable samples.
 * @property AtlasPlacement? placement The packing transform, or null when the drawable was never packed.
 * @property Int pageWidth The atlas page's width in pixels, as recorded with the placement.
 * @property Int pageHeight The atlas page's height in pixels.
 */
data class DrawableLayerBinding(
	val layerKey: String,
	val placement: AtlasPlacement?,
	val pageWidth: Int,
	val pageHeight: Int,
)

/**
 * A document's source-layer store: the inventory of artwork layers plus each drawable's recovered
 * binding to one, with the pixels decoded lazily.
 *
 * Inventory is built eagerly (names and dimensions are cheap metadata), rasters are not: a CMO3
 * document loads synchronously on the UI thread, so decoding every layer up front would stall the
 * open by seconds on a real model.  A decoded layer is retained for the store's life, which is the
 * document's life - a replaced document drops the whole store.
 *
 * Format-agnostic by construction: the byte supplier is injected, so the CMO3 retained graph fills
 * it today and a layered-art reader can fill the same type later.
 *
 * @property List<SourceLayerEntry> layers Every source layer in the document, in document order.
 * @property Map<String, DrawableLayerBinding> bindingsByDrawableId Each drawable's recovered binding,
 *   keyed by the raw drawable id; a drawable with no recoverable layer is absent.
 * @property Function readBytes Yields a layer's PNG bytes by key, or null when it has none.
 */
class LayerTextures(
	val layers: List<SourceLayerEntry>,
	val bindingsByDrawableId: Map<String, DrawableLayerBinding>,
	private val readBytes: (String) -> ByteArray?,
) {
	private val entriesByKey: Map<String, SourceLayerEntry> = layers.associateBy { entry -> entry.key }

	// A null value is a remembered failure (no bytes, or bytes that will not decode), which is why this
	// is not a getOrPut - getOrPut treats a stored null as absent and would retry the decode every time.
	private val decodedByKey = HashMap<String, DecodedImage?>()

	/** True when the document surfaces no source layers at all (a MOC3 origin, or art-less CMO3). */
	val isEmpty: Boolean get() = layers.isEmpty()

	/**
	 * The inventory entry for a layer key.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return SourceLayerEntry? The entry, or null when the key is unknown.
	 */
	fun layerFor(layerKey: String): SourceLayerEntry? = entriesByKey[layerKey]

	/**
	 * The source layer a drawable samples.
	 *
	 * @param String drawableId The raw drawable id.
	 * @return SourceLayerEntry? The layer, or null when the drawable has no recoverable binding.
	 */
	fun layerForDrawable(drawableId: String): SourceLayerEntry? =
		bindingsByDrawableId[drawableId]?.let { binding -> entriesByKey[binding.layerKey] }

	/**
	 * A drawable's binding, resolved through the same texture-source indirection the atlas lookup uses.
	 *
	 * Bindings are keyed by the SOURCE format's drawable ids, so a session-created copy has no key of
	 * its own and finds its art through the drawable it was duplicated from - exactly as it finds its
	 * atlas page.  Looking a drawable up by its own raw id alone would give a duplicate a page view but
	 * no layer view.
	 *
	 * @param Drawable drawable The drawable to resolve.
	 * @return DrawableLayerBinding? The binding, or null when the drawable has no recoverable source art.
	 */
	fun bindingForDrawable(drawable: Drawable): DrawableLayerBinding? =
		bindingsByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]

	/**
	 * The source layer a drawable samples, through the texture-source indirection.
	 *
	 * @param Drawable drawable The drawable to resolve.
	 * @return SourceLayerEntry? The layer, or null when the drawable has no recoverable source art.
	 */
	fun layerForDrawable(drawable: Drawable): SourceLayerEntry? =
		bindingForDrawable(drawable)?.let { binding -> entriesByKey[binding.layerKey] }

	/**
	 * The placement any drawable over a layer shares.
	 *
	 * Every drawable bound to one layer resolves through the same model image, so they carry the same
	 * placement and the same page - which is what lets the editor describe a layer's frame without
	 * first deciding which of its users to ask, and keeps that frame stable as the selection narrows.
	 *
	 * @param String layerKey The layer in question.
	 * @return DrawableLayerBinding? A binding over that layer, or null when nothing is bound to it.
	 */
	fun bindingForLayer(layerKey: String): DrawableLayerBinding? =
		entriesByKey[layerKey]?.boundDrawableIds?.firstNotNullOfOrNull { drawableId -> bindingsByDrawableId[drawableId] }

	/**
	 * Whether a drawable draws over the given layer, through the texture-source indirection.
	 *
	 * @param Drawable drawable The drawable to test.
	 * @param String layerKey The layer in question.
	 * @return Boolean True when the drawable samples that layer.
	 */
	fun drawsOverLayer(drawable: Drawable, layerKey: String): Boolean =
		bindingForDrawable(drawable)?.layerKey == layerKey

	/**
	 * A layer's pixels, decoding them on first request and caching the result (failures included).
	 *
	 * CALLER-CONFINED: the cache is a plain map with no synchronization, so every call must come from
	 * the same thread (today, the UI thread).  A second caller would not merely race the map - it could
	 * hand out a SECOND decoded instance for one layer, and both the renderer's texture cache and the
	 * viewport's freshness test compare decoded images by identity.  Anything off that thread uses
	 * [decodeRaster] instead, which shares nothing.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return DecodedImage? The decoded raster, or null when the layer has no usable pixels.
	 */
	fun rasterFor(layerKey: String): DecodedImage? {
		if (layerKey !in entriesByKey) {
			return null
		}
		if (decodedByKey.containsKey(layerKey)) {
			return decodedByKey[layerKey]
		}
		// The cached twin decodes through the uncached one: the two must produce the same image for the
		// same bytes, because callers compare a cached result against a freshly decoded one by identity.
		val decoded = decodeRaster(layerKey)
		decodedByKey[layerKey] = decoded
		return decoded
	}

	/**
	 * A layer's pixels, decoded fresh and cached nowhere - safe to call from any thread, including
	 * several at once.
	 *
	 * The uncached twin of [rasterFor], for callers that own their own result: the bytes come from an
	 * immutable archive and the decoder is stateless, so nothing here is shared.  Costs a repeat decode
	 * when a layer is already cached, which is the price of not sharing a cache across threads.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return DecodedImage? The decoded raster, or null when the layer has no usable pixels.
	 */
	fun decodeRaster(layerKey: String): DecodedImage? =
		readBytes(layerKey)?.let { bytes ->
			try {
				val image = org.umamo.format.png.PngCodec.read(bytes)
				DecodedImage(image.rgba, image.width, image.height)
			} catch (_: Exception) {
				null
			}
		}

	companion object {
		/** The store a document with no source art surfaces. */
		val EMPTY: LayerTextures = LayerTextures(emptyList(), emptyMap()) { null }
	}
}

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
internal fun inversePlacementAffine(placement: AtlasPlacement): FloatArray? {
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
 * The per-vertex chain is uv times the page size, through the placement inverse, divided by the
 * layer's size - all affine, so it folds into a single matrix that any consumer can apply in either
 * direction (see [invertUvAffine]).  An unpacked drawable's stored uvs already address its layer
 * image, so its mapping is the identity rather than a placement inverse; applying a placement there
 * would be a double transform.
 *
 * @param DrawableLayerBinding binding The drawable's recovered binding.
 * @param Int layerWidth The source layer's width in pixels.
 * @param Int layerHeight The source layer's height in pixels.
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
 * Maps a drawable's stored texture coordinates into its source layer's own [0,1] frame - the mapping
 * a layer view draws.
 *
 * @param FloatArray atlasUvs The drawable's stored texture coordinates, interleaved (u, v).
 * @param DrawableLayerBinding binding The drawable's recovered binding.
 * @param Int layerWidth The source layer's width in pixels.
 * @param Int layerHeight The source layer's height in pixels.
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