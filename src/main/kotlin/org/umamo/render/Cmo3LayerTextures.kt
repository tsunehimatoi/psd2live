package org.umamo.render

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.GVector2

/**
 * Lifts a CMO3 document's source artwork into a [LayerTextures] store: the model images its drawables
 * were authored against, and each drawable's recovered link to one plus the packing that put it on an
 * atlas page.
 *
 * What a "source layer" is here is the MODEL IMAGE (`CModelImage._filteredImage`) - the baked
 * composite the official editor itself samples in its combined-layer display mode - rather than the
 * raw `CLayer` artwork nodes.  A model image may in principle composite several layers, in which case
 * no single artwork layer is "the" source; sampling the composite sidesteps that entirely and matches
 * what the editor shows.  The originating layer's NAME is still recorded when exactly one composites
 * in (which is every case across the corpus), for a future source binding.
 *
 * The join is the model-image guid, per docs/format/CMO3.md §4: a drawable's
 * `CTextureInput_ModelImage` names it, the pooled `CModelImage` carries it, and the atlas's
 * `ModelImageEntry` keys its placement by it.  There is no direct drawable-to-atlas-tile pointer.
 *
 * The pixel lookup is injected rather than taken from a `Cmo3Model`, which is what keeps this in
 * commonMain - the CMO3 graph node types are all commonMain and only the archive-owning container
 * wrapper is JVM-bound.  The caller passes `Cmo3Model::extractLayerPng`, and so does the corpus test.
 * Same seam as [cmo3PuppetTextures], and nothing here decodes: the store decodes on demand.
 *
 * @param CModelSource modelSource The CMO3's root model source.
 * @param Function     readPng     Yields an image resource's embedded PNG bytes, or null when it has none.
 * @return LayerTextures The source-layer inventory plus per-drawable bindings.
 */
fun cmo3LayerTextures(modelSource: CModelSource, readPng: (CImageResource) -> ByteArray?): LayerTextures {
	// CMO3: CModelSource field textureManager - the root of the whole layered-art web.
	val textureManager = modelSource.textureManager as? CTextureManager ?: return LayerTextures.EMPTY

	// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas fields modelImages / width / height.
	// The page dimensions travel with each placement (see DrawableLayerBinding) because this page order
	// is the texture manager's, which is derived independently of PuppetTextures' encounter order.
	val placementByModelImageGuid = HashMap<String, AtlasPlacement>()
	val pageSizeByModelImageGuid = HashMap<String, Pair<Int, Int>>()
	val atlases = elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
	// CMO3: CTextureAtlas field cachedAtlasImage - the page's own pixels.  A placement is expressed in
	// PAGE coordinates, so it applies only to a drawable that actually samples one of these; identity
	// membership is the test (CImageResource is a plain class, so equality is identity).
	val atlasPageResources = atlases.mapNotNull { atlas -> atlas.cachedAtlasImage as? CImageResource }.toHashSet()
	for ((pageIndex, atlas) in atlases.withIndex()) {
		for (entry in elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
			// CMO3: ModelImageEntry field modelImageGuid - the join back to the pooled model image.
			val modelImageGuid = (entry.modelImageGuid as? Guid)?.uuid?.takeIf { it.isNotEmpty() } ?: continue
			// CMO3: ModelImageEntry field materialLocalToAtlasTransform - the packer's work, as a
			// GTransform2 (position, scale, eulerAngle in DEGREES).  Absent scale defaults to 1: a
			// GTransform2 with no scale child is the unscaled case, not a collapse to zero.
			val transform = entry.materialLocalToAtlasTransform as? GTransform2 ?: continue
			val position = transform.position as? GVector2
			val scale = transform.scale as? GVector2
			placementByModelImageGuid[modelImageGuid] =
				AtlasPlacement(
					pageIndex = pageIndex,
					positionX = position?.x ?: 0f,
					positionY = position?.y ?: 0f,
					scaleX = scale?.x ?: 1f,
					scaleY = scale?.y ?: 1f,
					rotationDegrees = transform.eulerAngle,
				)
			pageSizeByModelImageGuid[modelImageGuid] = atlas.width to atlas.height
		}
	}

	// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages.
	val modelImages =
		elementsOf(textureManager._modelImageGroups)
			.filterIsInstance<CModelImageGroup>()
			.flatMap { group -> elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
	val resourceByKey = HashMap<String, CImageResource>()
	val entryByKey = LinkedHashMap<String, SourceLayerEntry>()
	val boundDrawableIdsByKey = HashMap<String, MutableList<String>>()
	for (modelImage in modelImages) {
		// CMO3: CModelImage field guid - the key every binding and placement references.
		val key = (modelImage.guid as? Guid)?.uuid?.takeIf { it.isNotEmpty() } ?: continue
		// CMO3: CModelImage field _filteredImage - the baked composite raster, carrying its own
		// dimensions, so the inventory needs no decode.
		val resource = modelImage._filteredImage as? CImageResource ?: continue
		resourceByKey[key] = resource
		val boundDrawableIds = mutableListOf<String>()
		boundDrawableIdsByKey[key] = boundDrawableIds
		entryByKey[key] =
			SourceLayerEntry(
				key = key,
				// CMO3: CModelImage field name - the editor writes the drawable's own name here.
				name = displayNameOf(modelImage.name) ?: key,
				width = resource.width,
				height = resource.height,
				boundDrawableIds = boundDrawableIds,
				sourceLayerName = soleSourceLayerNameOf(modelImage),
			)
	}

	// CMO3: CDrawableSourceSet field _sources -> CArtMeshSource field _extensions.
	val artMeshes =
		elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
	val bindingsByDrawableId = HashMap<String, DrawableLayerBinding>()
	for (mesh in artMeshes) {
		val drawableId = (mesh.id as? Id)?.idstr?.takeIf { it.isNotEmpty() } ?: continue
		// CMO3: CTextureInputExtension field _textureInputs -> CTextureInput_ModelImage field
		// _modelImageGuid.  A drawable carrying only an atlas-region input (no model image) has no
		// recoverable source art and is simply absent from the bindings.
		val extension = elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
		val modelImageInput =
			elementsOf(extension._textureInputs).filterIsInstance<CTextureInput_ModelImage>().firstOrNull() ?: continue
		val key = (modelImageInput._modelImageGuid as? Guid)?.uuid?.takeIf { it.isNotEmpty() } ?: continue
		if (!entryByKey.containsKey(key)) {
			continue
		}
		boundDrawableIdsByKey[key]?.add(drawableId)

		// Which frame the stored uvs are in is decided by WHICH RESOURCE the drawable actually samples,
		// not by whether the document happens to carry an atlas.  A document saved in combined-layer
		// display mode points its drawables at per-layer rasters even while a packed atlas sits beside
		// them (modelA is the corpus case, its drawables sampling reduced per-layer images), and those
		// uvs already address the art - inverting a page-space placement over them would land thousands
		// of pixels away.  Only a drawable sampling an actual atlas page carries page-frame uvs.
		//
		// A per-layer raster at a different resolution than the model image's own baked one needs no
		// correction: normalized uvs span the same art either way.
		// CMO3: CArtMeshSource field texture -> GTexture2D field srcImageResource
		val sampledResource = (mesh.texture as? GTexture2D)?.srcImageResource as? CImageResource
		val samplesAtlasPage = sampledResource != null && sampledResource in atlasPageResources
		val atlasPageSize = pageSizeByModelImageGuid[key]
		bindingsByDrawableId[drawableId] =
			DrawableLayerBinding(
				layerKey = key,
				// Null whenever the uvs already address the art directly: no atlas ever packed this model
				// image, or the document samples layers rather than pages.  Recovery is then the identity.
				placement = if (samplesAtlasPage) placementByModelImageGuid[key] else null,
				pageWidth = if (samplesAtlasPage) sampledResource.width else atlasPageSize?.first ?: 0,
				pageHeight = if (samplesAtlasPage) sampledResource.height else atlasPageSize?.second ?: 0,
			)
	}

	return LayerTextures(entryByKey.values.toList(), bindingsByDrawableId) { layerKey ->
		resourceByKey[layerKey]?.let { resource -> readPng(resource) }
	}
}

/**
 * The originating artwork layer's name when a model image composites exactly one.
 *
 * More than one input means no single layer is the source (the composite is), so this reports none
 * rather than picking arbitrarily.  Recorded for a future source binding; nothing reads it yet.
 *
 * @param CModelImage modelImage The pooled model image.
 * @return String? The sole input layer's name, or null when there is not exactly one named input.
 */
private fun soleSourceLayerNameOf(modelImage: CModelImage): String? {
	// CMO3: CModelImage field inputFilterEnv -> CLayerSelectorMap field _imageToLayerInput, a map from
	// the layered image to the list of layer inputs composited from it.
	val selectorMap = modelImage.inputFilterEnv as? CLayerSelectorMap ?: return null
	val inputs =
		elementsOf(selectorMap._imageToLayerInput).flatMap { perImage ->
			elementsOf(perImage).filterIsInstance<CLayerInputData>()
		}
	val soleInput = inputs.singleOrNull() ?: return null
	// CMO3: CLayerInputData field layer -> ACLayerEntry field name.
	return (soleInput.layer as? ACLayerEntry)?.name?.takeIf { it.isNotEmpty() }
}

/**
 * Resolves a CMO3 name slot, which the serializer holds as `Any?` and fills with either a plain
 * string or an id-bearing node.
 *
 * @param Any? name The raw name field.
 * @return String? The display name, or null when the slot holds neither shape.
 */
private fun displayNameOf(name: Any?): String? =
	when (name) {
		is String -> name.takeIf { it.isNotEmpty() }
		is Id -> name.idstr.takeIf { it.isNotEmpty() }
		else -> null
	}

/**
 * Flattens a CMO3 collection field (CArrayList/CHashMap, held as `Any?`) to a plain list.
 *
 * @param Any? collection The raw collection field.
 * @return List<Any?> The elements (empty when null/unrecognised).
 */
private fun elementsOf(collection: Any?): List<Any?> =
	when (collection) {
		is Map<*, *> -> collection.values.toList()
		is Iterable<*> -> collection.toList()
		is Array<*> -> collection.toList()
		else -> emptyList()
	}