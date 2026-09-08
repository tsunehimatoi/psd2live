package org.umamo.render

import org.umamo.runtime.model.PuppetModel

/**
 * One drawable's source-artwork draw: which image it samples, and the affine carrying its stored
 * texture coordinates into that image's frame.
 *
 * @property String layerKey The artwork this drawable samples, keyed into [LayerRasterBatch.rastersByLayerKey].
 * @property FloatArray uvAffine The 2x3 row-major stored-to-artwork affine (see `layerUvAffineOf`).
 */
class DrawableLayerDraw(val layerKey: String, val uvAffine: FloatArray)

/**
 * Which artwork every drawable would display from, and what each image would cost to hold - the
 * mapping half of source-artwork display, carrying no pixels at all.
 *
 * ALWAYS COMPLETE: it covers every drawable whose art this document can recover, whether or not that
 * art is currently decoded or resident on the GPU.  That completeness is what lets the renderer bound
 * residency without losing information - a layer dropped for budget can be promoted again later
 * because its mapping never went away, and [unresolvedDrawableCount] keeps meaning "no artwork
 * exists" rather than drifting into "not resident right now".
 *
 * Cheap to build and cheap to hold: [layerByteCostByKey] comes from the store's inventory sizes, so
 * the cost of every image is known BEFORE any of them decode, which is what makes a budget
 * enforceable up front instead of after the memory is already spent.
 *
 * A drawable absent from [drawsByDrawableId] renders from its atlas page as usual, so a document
 * whose artwork is only partly recoverable still draws whole.
 *
 * @property Map<String, DrawableLayerDraw> drawsByDrawableId Each drawable's draw, by raw drawable id.
 * @property Map<String, Long> layerByteCostByKey What each distinct image costs decoded, in bytes.
 *   Keyed by LAYER, not by drawable: duplicated art is shared, so a layer is paid for once.
 * @property Int unresolvedDrawableCount How many meshed drawables have no recoverable artwork at all
 *   and so keep displaying from the atlas.  Reported rather than hidden: a puppet drawn mostly from
 *   its atlas should not look like one drawn from its artwork.
 */
class LayerDrawPlan(
	val drawsByDrawableId: Map<String, DrawableLayerDraw>,
	val layerByteCostByKey: Map<String, Long>,
	val unresolvedDrawableCount: Int = 0,
) {
	/** True when there is nothing to display from, so the puppet stays on the atlas. */
	val isEmpty: Boolean get() = drawsByDrawableId.isEmpty()

	/**
	 * Which drawables draw over any of the given layers.
	 *
	 * @param Set<String> layerKeys The layers in question.
	 * @return Set<String> The raw ids of the drawables mapped onto them.
	 */
	fun drawableIdsUsing(layerKeys: Set<String>): Set<String> =
		drawsByDrawableId.filterValues { draw -> draw.layerKey in layerKeys }.keys

	companion object {
		/** The empty plan: display from the atlas. */
		val EMPTY: LayerDrawPlan = LayerDrawPlan(emptyMap(), emptyMap())
	}
}

/**
 * A delivery of decoded artwork answering what the renderer asked for - the pixel half, transient by
 * design.
 *
 * Pixels arrive in batches rather than as one complete set, and THAT is what keeps this affordable:
 * the producer decodes a chunk, hands it over, and drops its reference before decoding the next, so
 * the heap holds one chunk rather than the document's whole artwork.  A rig whose layers would be a
 * gigabyte decoded costs a few tens of megabytes to stream.
 *
 * Batches are additive and order-independent - the renderer uploads whatever a batch carries that its
 * current plan still maps, and ignores the rest - so a late batch from a superseded plan is harmless
 * rather than something to detect.
 *
 * @property Map<String, DecodedImage> rastersByLayerKey The decoded images, by layer key.
 * @property Set<String> undecodableLayerKeys Layers whose pixels would not decode.  Reported so the
 *   drawables over them can be counted as artwork-less rather than merely absent from this batch.
 */
class LayerRasterBatch(
	val rastersByLayerKey: Map<String, DecodedImage>,
	val undecodableLayerKeys: Set<String> = emptySet(),
)

/**
 * Works out which source artwork each drawable would display from, and what each image would cost.
 *
 * Decodes NOTHING.  It reads only the store's inventory - sizes and bindings - so it is cheap enough
 * to build eagerly for the whole document and to rebuild whenever the drawable set changes.  Deciding
 * the mapping separately from paying for the pixels is what lets a budget be applied before any
 * memory is spent, and what keeps a budget-evicted layer promotable later.
 *
 * Safe on a background thread: it reads only immutable model and store data and touches neither the
 * store's decode cache nor the device.
 *
 * A drawable is left out (and counted in [LayerDrawPlan.unresolvedDrawableCount]) when the document
 * retains no art for it or when its placement will not invert.  A layer whose pixels turn out not to
 * decode is NOT known here - that surfaces later, from the batch that tried, so this count means
 * exactly "no artwork exists" and never "not decoded yet".
 *
 * @param PuppetModel model The puppet whose drawables need artwork.
 * @param LayerTextures layers The document's source-art store.
 * @return LayerDrawPlan Each drawable's mapping plus the per-layer byte cost.
 */
fun buildLayerDrawPlan(model: PuppetModel, layers: LayerTextures): LayerDrawPlan {
	if (layers.isEmpty) {
		return LayerDrawPlan.EMPTY
	}
	val drawsByDrawableId = HashMap<String, DrawableLayerDraw>()
	val layerByteCostByKey = HashMap<String, Long>()
	var unresolved = 0
	for (drawable in model.drawables) {
		if (drawable.mesh == null) {
			continue
		}
		val binding = layers.bindingForDrawable(drawable)
		val entry = binding?.let { layers.layerFor(it.layerKey) }
		val affine = if (binding != null && entry != null) layerUvAffineOf(binding, entry.width, entry.height) else null
		if (binding == null || entry == null || affine == null) {
			unresolved++
			continue
		}
		// Four bytes per pixel: what the image occupies once decoded to RGBA and again once uploaded,
		// which is the quantity the residency budget is denominated in.
		layerByteCostByKey[binding.layerKey] = entry.width.toLong() * entry.height.toLong() * 4L
		drawsByDrawableId[drawable.id.raw] = DrawableLayerDraw(binding.layerKey, affine)
	}
	return LayerDrawPlan(drawsByDrawableId, layerByteCostByKey, unresolved)
}