package org.umamo.interop.cmo3

import org.umamo.format.art.analyzeAlpha
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.gen.Anisotropy
import org.umamo.format.cmo3.model.gen.AutoLayoutLock
import org.umamo.format.cmo3.model.gen.CBlend_Normal
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.CachedImageType
import org.umamo.format.cmo3.model.gen.EnvConnection
import org.umamo.format.cmo3.model.gen.EnvValueConnector
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterMode
import org.umamo.format.cmo3.model.gen.FilterOutputValueConnector
import org.umamo.format.cmo3.model.gen.FilterValue
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.MagFilter
import org.umamo.format.cmo3.model.gen.MinFilter
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.gen.ModelImageFilterEnv
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.format.cmo3.model.gen.WrapMode
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.cropped
import org.umamo.runtime.model.AtlasPlacement

/*
 * WHY THIS FILE EXISTS, AND WHY IT CUTS UP THE ATLAS
 *
 * A CMO3 is built around SOURCE ART: the editor decomposes an imported PSD/CLIP/KRA into a
 * CLayeredImage tree of per-layer images, and everything else (model images, atlas entries,
 * placements) hangs off that.  A .moc3 carries none of it - only the packed atlas pages and UVs.
 * So a MOC3-origin export has to FABRICATE a source document, and the only raw material available
 * is the packed page.  That is the whole reason this builder slices each drawable's uv bounding
 * box back out of the atlas and presents the crop as if it were an imported layer.  None of that
 * cutting is inherent to the format; it is reconstruction of information the bake threw away.
 *
 * An artwork-origin document needs none of it: its tiles hold the real rasters, so those write as
 * real layers of their file's layered image through Cmo3SourceLayerWeb, and the crop path here
 * serves only the drawables that have no such art.  The page half (one atlas and one shared texture
 * per page) and the model-image web around a layer (modelImageOver) are shared by both.
 *
 * The cutting is NOT what makes a file render.  A third-party converter's CMO3 renders in the
 * official editor with NO source document at all (zero CLayeredImage / CLayer / CModelImage /
 * ModelImageEntry, drawables carrying only a CTextureInput_TextureAtlasRegion) - and it renders
 * despite geometry that is offset by a constant from Cubism's own golden file for the same model.
 * The web here exists for the editor's texture-atlas and mesh-edit VIEWS, which derive
 * mesh-over-texture placement from the model images; a single whole-page image cannot place
 * hundreds of drawables and drew every mesh at its assembled canvas position instead.  Do not
 * "simplify" by deleting the web: that shape was tested and the editor errors with an empty atlas.
 *
 * The output renders in the official editor and switches between layered-art and texture-atlas
 * display modes.  The one known failure is scale: modelF (~850 MB, several hundred layers) OOMs
 * the editor on the switch BACK to atlas mode.  Atlas mode recomposites the page out of the
 * materials, so peak memory tracks the total area of the crops cut below, not the page size - a uv
 * bounding box overlaps its neighbours, so that total can run to a multiple of the page.  Measure
 * it before concluding the editor is simply out of room.
 *
 * WHAT IT BUILDS, mirroring the official ATLAS-MODE shape (EricaTamamo,
 * isTextureInputModelImageMode=false): one CTextureAtlas + ONE SHARED GTexture2D per page that
 * every packed drawable on the page samples with atlas-frame UVs, plus a PER-DRAWABLE model-image
 * web - each drawable's texture patch cropped out of the page as its own CLayer + CModelImage +
 * ModelImageEntry, the entry carrying the patch's packing origin (materialLocalToAtlasTransform)
 * and the drawable's fitted atlas-to-canvas placement (atlasLocalToCanvasTransform).
 *
 * Every CModelImage carries the editor's layer-filter web (a CLayerSelector feeding a
 * CLayerFilter, connected through per-document shared FilterValue definitions) plus a filtered
 * image, a cached-image manager, and icon thumbnails - the official reader's custom deserializers
 * dereference these, so a null is a hard failure, not a cosmetic gap.  The filter definition
 * guids are editor-static constants (identical uuids in every corpus file).
 *
 * The synthetic source doc is the ATLAS PAGE's own frame, mirroring how an official document's
 * CLayeredImage carries the source PSD's size rather than the canvas
 * (ModelWithOffscreenPartClipping: a 500x500 doc inside a 1000x2000 canvas - Erica's doc merely
 * happens to equal its canvas).  Each layer's boundsOnImageDoc carries the placement translation
 * as its origin (equal to the owning CModelImage's _materialLocalToCanvasTransform translation on
 * all 892 corpus layers) and the imageResource dims as its size (also 892 of 892); official docs
 * sit origin-aligned on the canvas, so their doc rects and canvas placements coincide, and our
 * patch layers keep that equality by writing the canvas placement.
 *
 * WHERE THE PACKING'S SCALE AND ROTATION LIVE: not on the ModelImageEntry.  Each crop is an
 * axis-aligned rect of page pixels lifted at scale 1, so position-only IS the honest
 * materialLocalToAtlasTransform for it; whatever the packer did to the art - rotate, mirror, scale
 * - is carried by the page fit, which both atlasLocalToCanvasTransform and the model image's
 * _materialLocalToCanvasTransform compose (see fitAtlasPageToCanvasTransform, and note the
 * mean-centering there is what lets a rotated packing survive the fit at all).
 *
 * A patch rect starts as a uv BOUNDING BOX, so unless the mesh is itself a page-aligned rectangle
 * the rect also spans page pixels the packer nested into the leftover corners - a neighbour's
 * artwork.  The crop is therefore masked to the mesh's own triangles (coverageMaskOf) rather than
 * copied verbatim, then TRIMMED to the masked pixels' opaque bounds, with the packing origin,
 * placement, and layer bounds all derived from the trimmed rect together.  Un-masked, the editor
 * takes the foreign pixels for this drawable's artwork - they follow the patch when the atlas is
 * rearranged, and a repack stamps them back into the page.
 *
 * Remaining deliberate simplification, validated by the official-editor gate: the cached images
 * are the raw resources themselves (SCALE_1, nothing prerendered; their
 * transformRawImageToCachedImage records the 64-aligned padding fraction like every corpus
 * cache entry).  The icons are real: each layer's, model image's, and drawable's is its art fitted
 * into the square the way the editor writes it (Cmo3Icons).
 */
internal object Cmo3ImageChainBuilder {
	/**
	 * One drawable's geometry on its page: id, interleaved atlas-frame uvs and base positions, plus
	 * the triangle indices that say which page pixels inside the uv bounding box are actually this
	 * drawable's (see [coverageMaskOf]).
	 */
	internal class DrawableRegion(
		val drawableIdStr: String,
		val uvs: FloatArray,
		val positions: FloatArray,
		val indices: IntArray,
	)

	/**
	 * The populated chain: the PNG entries to embed and the texture bindings.
	 *
	 * @property List pngEntries           The PNG entries to embed.
	 * @property Map  bindingByDrawableId  The texture web per drawable that got one.
	 * @property List pageFallbackBindings The per-page bindings for a drawable with no web of its own.
	 * @property Int  cropDrawableCount    How many drawables took the crop path - the stand-in sliced
	 *   out of a page - which is what the missing-source-art notice reports; zero when every
	 *   drawable's art came from a real layer.
	 */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val bindingByDrawableId: Map<String, Cmo3DrawableTextureBinding>,
		val pageFallbackBindings: List<Cmo3DrawableTextureBinding>,
		val cropDrawableCount: Int = 0,
	)

	/**
	 * The per-document state an image chain threads through every page, crop, and real layer it
	 * writes: the archive-entry path minters (the editor's imageFileBuf / image_N de-dupe sequences,
	 * which must stay unique across the whole file), the filter web's shared definitions, and the
	 * blend and option instances every layer shares.
	 *
	 * A fresh graph takes the defaults: counters from the skeleton's own first indices and fresh
	 * filter definitions.  A retained graph hands in the archive's own minters and the definitions
	 * recovered from a model image it already holds, so a layer minted into it continues the file's
	 * sequences and shares its singletons rather than growing a second set.
	 *
	 * @param FilterCommons filters               The filter web's per-document singletons.
	 * @param Function      mintImageFileBufPath  The next unique archive path for an image buffer.
	 * @param Function      mintIconPath          The next unique archive path for an icon.
	 */
	internal class Cmo3FreshChainNames(
		val filters: FilterCommons = FilterCommons.fresh(),
		// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...); pages
		// claim the first indices, then crops and real layers continue the sequence.
		private val mintImageFileBufPath: () -> String = FreshPathSequence("imageFileBuf", firstSuffix = -1)::next,
		// The skeleton's three model icons take image.png / image_0.png / image_1.png, so icon entries
		// continue the editor's image_N naming from suffix 2.
		private val mintIconPath: () -> String = FreshPathSequence("image", firstSuffix = 2)::next,
		val includeAutoLayoutLock: Boolean = true,
	) {
		/** The one blend node every synthesized layer and group references. */
		val sharedBlend: CBlend_Normal = CBlend_Normal()

		/** The one option map every synthesized layer and group references. */
		val sharedOptions: CHashMap<String, Any?> = CHashMap()

		/**
		 * The next unique archive path for an image buffer.
		 *
		 * @return String The path.
		 */
		fun nextImageFileBufPath(): String = mintImageFileBufPath()

		/**
		 * The next unique archive path for an icon.
		 *
		 * @return String The path.
		 */
		fun nextIconPath(): String = mintIconPath()
	}

	/**
	 * One of the editor's archive de-dupe sequences: `<stem>.png` for suffix -1, then `<stem>_<n>.png`.
	 * A fresh archive starts at the skeleton's first free suffix; a retained archive continues from
	 * the first path its own minter reports ([continuingFrom]) and counts locally from there, so a
	 * batch of icons minted before their entries are embedded still takes distinct paths.
	 *
	 * @param String stem        The sequence's stem.
	 * @param Int    firstSuffix The suffix the first minted path takes.
	 */
	internal class FreshPathSequence(private val stem: String, firstSuffix: Int) {
		private var nextSuffix = firstSuffix

		/**
		 * Mints the next path.
		 *
		 * @return String The path.
		 */
		fun next(): String {
			val suffix = nextSuffix
			nextSuffix += 1
			return if (suffix < 0) "$stem.png" else "${stem}_$suffix.png"
		}

		companion object {
			/**
			 * The sequence whose first path is [firstPath], as a retained archive's minter reports it.
			 *
			 * @param String stem      The sequence's stem.
			 * @param String firstPath The next unused path in the archive, in the sequence's own form.
			 * @return FreshPathSequence The sequence.
			 */
			fun continuingFrom(stem: String, firstPath: String): FreshPathSequence {
				val firstSuffix =
					if (firstPath == "$stem.png") {
						-1
					} else {
						checkNotNull(firstPath.removePrefix("${stem}_").removeSuffix(".png").toIntOrNull()) { "'$firstPath' is not in the $stem sequence" }
					}
				return FreshPathSequence(stem, firstSuffix)
			}
		}
	}

	/**
	 * What makes two drawables share one CModelImage: the same crop rect and the same mesh.  The
	 * mesh is part of the identity because it masks the crop - two drawables over one rect with
	 * different topology no longer produce the same pixels.  The fitted PLACEMENT is deliberately
	 * NOT part of the key, and that is only safe because Cmo3AtlasUndedup has already routed every
	 * different-placement twin to its own slot: official multi-drawable materials are always
	 * CO-LOCATED (Erica: 45 of 45 shared groups have identical mesh and placement), the shared
	 * image carries a single canvas placement, and duplicating a material over one slot instead
	 * makes the editor's atlas view - a recomposite of the materials - stack the shared art's
	 * alpha (2a - a*a per extra copy), rendering the atlas more opaque than source-image mode.
	 */
	private class PatchWebKey(
		private val patchRect: IntArray,
		private val uvs: FloatArray,
		private val indices: IntArray,
	) {
		override fun equals(other: Any?): Boolean =
			other is PatchWebKey &&
				patchRect.contentEquals(other.patchRect) &&
				uvs.contentEquals(other.uvs) &&
				indices.contentEquals(other.indices)

		override fun hashCode(): Int {
			var result = patchRect.contentHashCode()
			result = 31 * result + uvs.contentHashCode()
			result = 31 * result + indices.contentHashCode()
			return result
		}
	}

	/** CMO3: FilterInstance filterDefGuid for "CLayerSelector" - fixed uuid in every corpus file. */
	private const val LAYER_SELECTOR_DEF_UUID = "5e9fe1ea-0ec3-4d68-a5fa-018fc7abe301"

	/** CMO3: FilterInstance filterDefGuid for "CLayerFilter" - fixed uuid in every corpus file. */
	private const val LAYER_FILTER_DEF_UUID = "4083cd1f-40ba-4eda-8400-379019d55ed8"

	/**
	 * The per-document singletons of the filter web, shared by every model image's ModelImageFilterSet:
	 * the value ids the connectors are keyed by, the FilterValue definitions they name, and the two
	 * filter-definition guids.  Minted fresh for a new graph, or recovered from a model image a
	 * retained graph already holds so a minted image shares the file's own objects.
	 *
	 * @param Map  idByIdStr          Each value id by its idstr.
	 * @param Map  valueByIdStr       Each FilterValue definition by its own id's idstr.
	 * @param Guid selectorDefGuid    The CLayerSelector definition guid.
	 * @param Guid layerFilterDefGuid The CLayerFilter definition guid.
	 */
	internal class FilterCommons private constructor(
		private val idByIdStr: Map<String, Id>,
		private val valueByIdStr: Map<String, FilterValue>,
		val selectorDefGuid: Guid,
		val layerFilterDefGuid: Guid,
	) {
		val inputLayerData: Id get() = idByIdStr.getValue(INPUT_LAYER_DATA)
		val currentImageGuid: Id get() = idByIdStr.getValue(CURRENT_IMAGE_GUID)
		val outputImage: Id get() = idByIdStr.getValue(OUTPUT_IMAGE)
		val outputTransform: Id get() = idByIdStr.getValue(OUTPUT_TRANSFORM)
		val selectorInputLayerData: Id get() = idByIdStr.getValue(SELECTOR_INPUT_LAYER_DATA)
		val selectorCurrentImageGuid: Id get() = idByIdStr.getValue(SELECTOR_CURRENT_IMAGE_GUID)
		val selectorOutputLayerData: Id get() = idByIdStr.getValue(SELECTOR_OUTPUT_LAYER_DATA)
		val filterInputLayer: Id get() = idByIdStr.getValue(FILTER_INPUT_LAYER)

		val selectLayer: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_LAYER_DATA)
		val importLayer: FilterValue get() = valueByIdStr.getValue(INPUT_LAYER_DATA)
		val importLayerSelection: FilterValue get() = valueByIdStr.getValue(SELECTOR_INPUT_LAYER_DATA)
		val currentGuid: FilterValue get() = valueByIdStr.getValue(CURRENT_IMAGE_GUID)
		val selectedSourceGuid: FilterValue get() = valueByIdStr.getValue(SELECTOR_CURRENT_IMAGE_GUID)
		val outputImageValue: FilterValue get() = valueByIdStr.getValue(OUTPUT_IMAGE)
		val outputImageResource: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_IMAGE_RESOURCE)
		val layerToCanvasEnv: FilterValue get() = valueByIdStr.getValue(OUTPUT_TRANSFORM)
		val layerToCanvasFilter: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_TRANSFORM)

		companion object {
			// CMO3: the FilterValueId idstrs of the model-image filter web, transcribed from the corpus
			// (MultiplyScreenColors.cmo3, identical across files).  "mi_" ids are the env side, "ilf_"
			// ids the filter side.
			private const val INPUT_LAYER_DATA = "mi_input_layerInputData"
			private const val CURRENT_IMAGE_GUID = "mi_currentImageGuid"
			private const val OUTPUT_IMAGE = "mi_output_image"
			private const val OUTPUT_TRANSFORM = "mi_output_transform"
			private const val SELECTOR_INPUT_LAYER_DATA = "ilf_inputLayerData"
			private const val SELECTOR_CURRENT_IMAGE_GUID = "ilf_currentImageGuid"
			private const val SELECTOR_OUTPUT_LAYER_DATA = "ilf_outputLayerData"
			private const val FILTER_INPUT_LAYER = "ilf_inputLayer"
			private const val SELECTOR_OUTPUT_IMAGE_RESOURCE = "ilf_outputImageRes"
			private const val SELECTOR_OUTPUT_TRANSFORM = "ilf_outputTransform"

			/** The ids the connectors are keyed by, every one of which a recovery must find. */
			private val KEY_ID_STRS =
				listOf(
					INPUT_LAYER_DATA,
					CURRENT_IMAGE_GUID,
					OUTPUT_IMAGE,
					OUTPUT_TRANSFORM,
					SELECTOR_INPUT_LAYER_DATA,
					SELECTOR_CURRENT_IMAGE_GUID,
					SELECTOR_OUTPUT_LAYER_DATA,
					FILTER_INPUT_LAYER,
				)

			/** The definitions' own ids, every one of which a recovery must find. */
			private val VALUE_ID_STRS =
				listOf(
					SELECTOR_OUTPUT_LAYER_DATA,
					INPUT_LAYER_DATA,
					SELECTOR_INPUT_LAYER_DATA,
					CURRENT_IMAGE_GUID,
					SELECTOR_CURRENT_IMAGE_GUID,
					OUTPUT_IMAGE,
					SELECTOR_OUTPUT_IMAGE_RESOURCE,
					OUTPUT_TRANSFORM,
					SELECTOR_OUTPUT_TRANSFORM,
				)

			/**
			 * Fresh singletons for a new graph.
			 *
			 * @return FilterCommons The definitions.
			 */
			fun fresh(): FilterCommons {
				val idByIdStr = HashMap<String, Id>()

				/**
				 * The one Id per idstr.
				 *
				 * @param String idStr The idstr.
				 * @return Id The id.
				 */
				fun valueId(idStr: String): Id = idByIdStr.getOrPut(idStr) { Id("FilterValueId").apply { idstr = idStr } }

				/**
				 * A FilterValue definition.
				 *
				 * @param String displayName The editor's display name for the value.
				 * @param String idStr       The value's own id.
				 * @return FilterValue The definition.
				 */
				fun value(displayName: String, idStr: String): FilterValue =
					FilterValue().apply {
						name = displayName
						id = valueId(idStr)
					}
				// CMO3: the FilterValue definitions - names and value-id wiring transcribed from the
				// corpus (MultiplyScreenColors.cmo3, identical across files).
				val values =
					listOf(
						value("Select Layer", SELECTOR_OUTPUT_LAYER_DATA),
						value("Import Layer", INPUT_LAYER_DATA),
						value("Import Layer selection", SELECTOR_INPUT_LAYER_DATA),
						value("Current GUID", CURRENT_IMAGE_GUID),
						value("GUID of Selected Source Image", SELECTOR_CURRENT_IMAGE_GUID),
						value("Output image", OUTPUT_IMAGE),
						value("Output Image (Resource Format)", SELECTOR_OUTPUT_IMAGE_RESOURCE),
						value("LayerToCanvas変換", OUTPUT_TRANSFORM),
						value("LayerToCanvas変換", SELECTOR_OUTPUT_TRANSFORM),
					)
				for (idStr in KEY_ID_STRS) {
					valueId(idStr)
				}
				return FilterCommons(
					idByIdStr,
					values.associateBy { value -> (value.id as Id).idstr },
					Guid("StaticFilterDefGuid").apply {
						uuid = LAYER_SELECTOR_DEF_UUID
						note = "(no debug info)"
					},
					Guid("StaticFilterDefGuid").apply {
						uuid = LAYER_FILTER_DEF_UUID
						note = "(no debug info)"
					},
				)
			}

			/**
			 * The singletons a retained graph's model image already wires, so an image minted beside it
			 * shares the same objects; null when the set does not carry the full web (a graph another
			 * writer produced), in which case the caller falls back to [fresh].
			 *
			 * @param ModelImageFilterSet filterSet A retained model image's inputFilter.
			 * @return FilterCommons? The recovered definitions, or null.
			 */
			fun fromFilterSet(filterSet: ModelImageFilterSet): FilterCommons? {
				val idByIdStr = HashMap<String, Id>()
				val valueByIdStr = HashMap<String, FilterValue>()
				var selectorDefGuid: Guid? = null
				var layerFilterDefGuid: Guid? = null

				/**
				 * Records an id the web references.
				 *
				 * @param Any? candidate A key or a connector's id slot.
				 */
				fun noteId(candidate: Any?) {
					val id = candidate as? Id ?: return
					idByIdStr.putIfAbsent(id.idstr, id)
				}

				/**
				 * Records a definition the web references, and its own id.
				 *
				 * @param Any? candidate A connector's definition slot.
				 */
				fun noteValue(candidate: Any?) {
					val value = candidate as? FilterValue ?: return
					val id = value.id as? Id ?: return
					noteId(id)
					valueByIdStr.putIfAbsent(id.idstr, value)
				}
				// CMO3: ModelImageFilterSet field filterMap -> FilterInstance fields filterName /
				// filterDefGuid / inputConnectors / outputConnectors, the connectors keyed by value id.
				for (instance in (filterSet.filterMap as? Map<*, *>)?.values.orEmpty().filterIsInstance<FilterInstance>()) {
					when (instance.filterName) {
						"CLayerSelector" -> selectorDefGuid = instance.filterDefGuid as? Guid
						"CLayerFilter" -> layerFilterDefGuid = instance.filterDefGuid as? Guid
					}
					for ((key, connector) in (instance.inputConnectors as? Map<*, *>).orEmpty()) {
						noteId(key)
						noteId((connector as? EnvValueConnector)?.envValueId)
					}
					for ((key, connector) in (instance.outputConnectors as? Map<*, *>).orEmpty()) {
						noteId(key)
						val output = connector as? FilterOutputValueConnector ?: continue
						noteId(output.id)
						noteValue(output.valueDef)
					}
				}
				// CMO3: ModelImageFilterSet fields _externalInputs / _externalOutputs -> EnvConnection
				// fields _envValueDef / filterValueDef.
				for (external in listOf(filterSet._externalInputs, filterSet._externalOutputs)) {
					for ((key, connection) in (external as? Map<*, *>).orEmpty()) {
						noteId(key)
						val envConnection = connection as? EnvConnection ?: continue
						noteValue(envConnection._envValueDef)
						noteValue(envConnection.filterValueDef)
					}
				}
				val selector = selectorDefGuid ?: return null
				val layerFilter = layerFilterDefGuid ?: return null
				if (KEY_ID_STRS.any { idStr -> idStr !in idByIdStr } || VALUE_ID_STRS.any { idStr -> idStr !in valueByIdStr }) {
					return null
				}
				return FilterCommons(idByIdStr, valueByIdStr, selector, layerFilter)
			}
		}
	}

	/**
	 * Builds one model image's ModelImageFilterSet: a CLayerSelector instance feeding a CLayerFilter,
	 * with the external input/output connections the editor's model-image pipeline expects.
	 *
	 * @param FilterCommons commons The per-document shared definitions.
	 * @return ModelImageFilterSet The fresh filter set.
	 */
	internal fun buildFilterSet(commons: FilterCommons): ModelImageFilterSet {
		val filterSet = ModelImageFilterSet()
		val selectorId = Id("FilterInstanceId").apply { idstr = "filter0" }
		val layerFilterId = Id("FilterInstanceId").apply { idstr = "filter1" }
		val selector =
			FilterInstance().apply {
				filterName = "CLayerSelector"
				filterDefGuid = commons.selectorDefGuid
				filterId = selectorId
				ownerFilterSet = filterSet
			}
		val selectorOutput =
			FilterOutputValueConnector().apply {
				instance = selector
				id = commons.selectorOutputLayerData
				valueDef = commons.selectLayer
			}
		selector.inputConnectors =
			CHashMap<Any?, Any?>().apply {
				put(commons.selectorInputLayerData, EnvValueConnector().apply { envValueId = commons.inputLayerData })
				put(commons.selectorCurrentImageGuid, EnvValueConnector().apply { envValueId = commons.currentImageGuid })
			}
		selector.outputConnectors = CHashMap<Any?, Any?>().apply { put(commons.selectorOutputLayerData, selectorOutput) }
		val layerFilter =
			FilterInstance().apply {
				filterName = "CLayerFilter"
				filterDefGuid = commons.layerFilterDefGuid
				filterId = layerFilterId
				inputConnectors = CHashMap<Any?, Any?>().apply { put(commons.filterInputLayer, selectorOutput) }
				outputConnectors = CHashMap<Any?, Any?>()
				ownerFilterSet = filterSet
			}
		filterSet.filterMap =
			LinkedHashMap<Any?, Any?>().apply {
				put(selectorId, selector)
				put(layerFilterId, layerFilter)
			}
		filterSet._externalInputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.inputLayerData,
					EnvConnection().apply {
						_envValueDef = commons.importLayer
						filter = selector
						filterValueDef = commons.importLayerSelection
					},
				)
				put(
					commons.currentImageGuid,
					EnvConnection().apply {
						_envValueDef = commons.currentGuid
						filter = selector
						filterValueDef = commons.selectedSourceGuid
					},
				)
			}
		filterSet._externalOutputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.outputImage,
					EnvConnection().apply {
						_envValueDef = commons.outputImageValue
						filter = layerFilter
						filterValueDef = commons.outputImageResource
					},
				)
				put(
					commons.outputTransform,
					EnvConnection().apply {
						_envValueDef = commons.layerToCanvasEnv
						filter = layerFilter
						filterValueDef = commons.layerToCanvasFilter
					},
				)
			}
		return filterSet
	}

	/**
	 * A page's image resource: the embedded page PNG's dimensions, type, and archive link.
	 *
	 * Shared by the fresh conversion and the export's page mint, so a page minted into a retained
	 * graph is field-for-field the shape the fresh path writes.
	 *
	 * @param String path    The archive path the page PNG is stored under.
	 * @param Int    width   The page width in pixels.
	 * @param Int    height  The page height in pixels.
	 * @param Int    pngSize The encoded PNG's byte size.
	 * @return CImageResource The page resource.
	 */
	internal fun pageImageResource(path: String, width: Int, height: Int, pngSize: Int): CImageResource =
		CImageResource().apply {
			// CMO3: CImageResource attrs width/height/type + the imageFileBuf file child.
			this.width = width
			this.height = height
			type = "INT_ARGB"
			imageFileBuf = FileRef().apply { archivePath = path }
			imageFileBuf_size = pngSize
		}

	/**
	 * A page's texture atlas element, empty of entries: the editor's naming, a fresh guid, and the
	 * cached-image manager whose padding diagonal the editor's source-image sampling depends on.
	 *
	 * @param Int            pageOrdinal The zero-based page index ("TextureAtlas1" is page 0).
	 * @param Int            width       The page width in pixels.
	 * @param Int            height      The page height in pixels.
	 * @param CImageResource resource    The page's image resource.
	 * @return CTextureAtlas The atlas element.
	 */
	internal fun pageAtlas(pageOrdinal: Int, width: Int, height: Int, resource: CImageResource): CTextureAtlas =
		CTextureAtlas().apply {
			// CMO3: CTextureAtlas fields name/width/height/cachedAtlasImage/guid/modelImages/
			// cachedImageManager.
			name = "TextureAtlas${pageOrdinal + 1}"
			this.width = width
			this.height = height
			cachedAtlasImage = resource
			guid = Cmo3SkeletonBuilder.freshGuid("CTextureAtlasGuid")
			modelImages = CArrayList<Any?>()
			cachedImageManager = paddedCacheManager(resource, width, height)
		}

	/**
	 * A page's ONE shared texture: every drawable on the page references this instance (the writer
	 * hoists it), like the editor's own atlas files.
	 *
	 * @param String?        atlasName The owning atlas's name, which the texture shares.
	 * @param CImageResource resource  The page's image resource the texture samples.
	 * @return GTexture2D The page texture.
	 */
	internal fun pageTexture(atlasName: String?, resource: CImageResource): GTexture2D {
		val texture =
			GTexture2D().apply {
				// CMO3: GTexture2D - the page's shared texture.
				name = atlasName
				// CMO3: GTexture fields wrapMode / filterMode / anisotropy - the editor's fixed
				// sampling setup on every corpus texture.
				wrapMode = WrapMode.CLAMP_TO_BORDER
				guid = Cmo3SkeletonBuilder.freshGuid("GTextureGuid")
				anisotropy = Anisotropy.ON
				srcImageResource = resource
				transformImageResource01toLogical01 = CAffine()
				mipmapLevel = 64
				// CMO3: GTexture2D field isPremultiplied - true on EVERY corpus texture (178 of
				// 178, every era), including the many whose embedded PNG bytes are straight
				// alpha.  The flag records the editor's texture-render/upload convention, not
				// the byte storage (see the Premultiplied section), so a MOC3 sidecar page -
				// straight alpha like the corpus ones - writes true as well.
				isPremultiplied = true
			}
		texture.filterMode =
			FilterMode().apply {
				minFilter = MinFilter.LINEAR_MIPMAP_LINEAR
				magFilter = MagFilter.LINEAR
				owner = texture
			}
		return texture
	}

	/**
	 * The cached-image manager the editor expects on every model image and texture atlas: the raw
	 * page cached as itself at SCALE_1 (nothing prerendered).
	 *
	 * @param CImageResource pageResource The page image resource.
	 * @param Int            width        The page width in pixels.
	 * @param Int            height       The page height in pixels.
	 * @return CCachedImageManager The fresh manager.
	 */
	internal fun paddedCacheManager(pageResource: CImageResource, width: Int, height: Int): CCachedImageManager =
		CCachedImageManager().apply {
			// CMO3: CCachedImageManager fields defaultCacheType / rawImage / cachedImages /
			// requiredMipmapLevel (corpus flat imports cache the raw resource itself).
			defaultCacheType = CachedImageType.SCALE_1
			rawImage = pageResource
			cachedImages =
				ArrayList<Any?>(
					mutableListOf(
						CCachedImage().apply {
							_cachedImageResource = pageResource
							isSharedImage = true
							rawImageSize =
								CSize().apply {
									this.width = width
									this.height = height
								}
							reductionRatio = 1
							mipmapLevel = 64
							hasMargin = false
							isCleaned = false
							// CMO3: CCachedImage field transformRawImageToCachedImage - the raw
							// dims over the cache raster's 64-aligned padding, per axis: every
							// corpus reductionRatio=1 cache writes exactly dim / ceil64(dim)
							// (1073 of 1073).  Identity here makes the editor's source-image
							// sampling stretch the art by the padding fraction.
							transformRawImageToCachedImage =
								CAffine().apply {
									m00 = width.toFloat() / ((width + 63) / 64 * 64)
									m11 = height.toFloat() / ((height + 63) / 64 * 64)
								}
						},
					),
				)
			requiredMipmapLevel = 64
		}

	/**
	 * A drawable's pixel-aligned texture-patch rect on its page, from its atlas-frame uv bounds.
	 *
	 * Mesh margins may reach slightly outside [0,1]; the rect is clamped to the page and forced to
	 * at least one pixel.
	 *
	 * @param FloatArray uvs        Interleaved atlas-frame uvs.
	 * @param Int        pageWidth  The page's pixel width.
	 * @param Int        pageHeight The page's pixel height.
	 * @return IntArray? [x0, y0, x1, y1] (exclusive max), or null when there are no uvs.
	 */
	internal fun patchRectOf(uvs: FloatArray, pageWidth: Int, pageHeight: Int): IntArray? {
		if (uvs.size < 2) {
			return null
		}
		var minU = Float.POSITIVE_INFINITY
		var minV = Float.POSITIVE_INFINITY
		var maxU = Float.NEGATIVE_INFINITY
		var maxV = Float.NEGATIVE_INFINITY
		var componentIndex = 0
		while (componentIndex + 1 < uvs.size) {
			minU = minOf(minU, uvs[componentIndex])
			maxU = maxOf(maxU, uvs[componentIndex])
			minV = minOf(minV, uvs[componentIndex + 1])
			maxV = maxOf(maxV, uvs[componentIndex + 1])
			componentIndex += 2
		}
		if (!minU.isFinite() || !minV.isFinite() || !maxU.isFinite() || !maxV.isFinite()) {
			return null
		}
		val x0 = kotlin.math.floor(minU * pageWidth).toInt().coerceIn(0, pageWidth - 1)
		val y0 = kotlin.math.floor(minV * pageHeight).toInt().coerceIn(0, pageHeight - 1)
		val x1 = kotlin.math.ceil(maxU * pageWidth).toInt().coerceIn(x0 + 1, pageWidth)
		val y1 = kotlin.math.ceil(maxV * pageHeight).toInt().coerceIn(y0 + 1, pageHeight)
		return intArrayOf(x0, y0, x1, y1)
	}

	/**
	 * The material-local point whose image under [pageFit] is the given canvas point.
	 *
	 * This back-solves the declared packing origin from the snapped integer placement so that
	 * atlasLocalToCanvasTransform composed with materialLocalToAtlasTransform reproduces
	 * _materialLocalToCanvasTransform exactly - the official files' structure (their packing
	 * origins are fractional, carrying the complement of the fit's fractional translation).
	 *
	 * @param CAffine pageFit The page-to-canvas fit.
	 * @param Float   canvasX The snapped placement x.
	 * @param Float   canvasY The snapped placement y.
	 * @return FloatArray The (x, y) material-local origin, or null when the fit is degenerate.
	 */
	private fun solvePageFitFor(pageFit: CAffine, canvasX: Float, canvasY: Float): FloatArray? {
		val determinant = pageFit.m00.toDouble() * pageFit.m11 - pageFit.m01.toDouble() * pageFit.m10
		if (determinant == 0.0) {
			return null
		}
		val deltaX = canvasX.toDouble() - pageFit.m02
		val deltaY = canvasY.toDouble() - pageFit.m12
		return floatArrayOf(
			((pageFit.m11 * deltaX - pageFit.m01 * deltaY) / determinant).toFloat(),
			((pageFit.m00 * deltaY - pageFit.m10 * deltaX) / determinant).toFloat(),
		)
	}

	/**
	 * Writes a packing origin into [transform]: the position in page pixels, the packer's resampling
	 * scale, and its rotation in degrees.
	 *
	 * CMO3: ModelImageEntry field materialLocalToAtlasTransform - a GTransform2 whose position is the
	 * packing origin in page pixels, scale the packer's resampling, and eulerAngle its rotation in
	 * DEGREES.  Shared by the fresh conversion, the export's pack-in mint, and the placement lowering,
	 * so the three write one convention.
	 *
	 * @param GTransform2 transform       The transform to write into; an entry's own, so it keeps its instance.
	 * @param Float       positionX       The packing origin's x on the page.
	 * @param Float       positionY       The packing origin's y on the page.
	 * @param Float       scaleX          The packer's horizontal scale.
	 * @param Float       scaleY          The packer's vertical scale.
	 * @param Float       rotationDegrees The packer's rotation, counter-clockwise degrees.
	 * @return GTransform2 The same [transform].
	 */
	internal fun writePacking(
		transform: GTransform2,
		positionX: Float,
		positionY: Float,
		scaleX: Float,
		scaleY: Float,
		rotationDegrees: Float,
	): GTransform2 {
		transform.position =
			GVector2().apply {
				x = positionX
				y = positionY
			}
		transform.scale =
			GVector2().apply {
				x = scaleX
				y = scaleY
			}
		transform.eulerAngle = rotationDegrees
		return transform
	}

	/**
	 * [writePacking] from a runtime placement.
	 *
	 * @param GTransform2    transform The transform to write into.
	 * @param AtlasPlacement placement Where the art sits on its page.
	 * @return GTransform2 The same [transform].
	 */
	internal fun writePacking(transform: GTransform2, placement: AtlasPlacement): GTransform2 =
		writePacking(
			transform,
			placement.positionX,
			placement.positionY,
			placement.scaleX,
			placement.scaleY,
			placement.rotationDegrees,
		)

	/**
	 * A page's packed entry for one model image: the membership back-reference, the shared guid, and
	 * the transform pair the editor's atlas and mesh-edit views derive mesh-over-texture placement
	 * from.
	 *
	 * CMO3: ModelImageEntry fields atlas / modelImageGuid / autoLayoutLock /
	 * atlasLocalToCanvasTransform / materialLocalToAtlasTransform.  The guid is the model image's OWN
	 * instance, so the writer shares it like the editor's files; autoLayoutLock is an AutoLayoutLock
	 * enum (v="NONE") - the editor's field is enum-typed and class-casts a boolean.  Shared by the
	 * fresh conversion and the export's pack-in mint, so an entry minted into a retained graph is
	 * field-for-field the shape the fresh path writes.
	 *
	 * @param CTextureAtlas atlas              The page the entry lives in.
	 * @param Any?          modelImageGuid     The model image's own guid instance.
	 * @param CAffine       atlasLocalToCanvas The fitted atlas-to-canvas placement, an independent instance.
	 * @param GTransform2   packing            The packing origin on the page.
	 * @return ModelImageEntry The entry.
	 */
	internal fun packedEntry(
		atlas: CTextureAtlas,
		modelImageGuid: Any?,
		atlasLocalToCanvas: CAffine,
		packing: GTransform2,
		includeAutoLayoutLock: Boolean = true,
	): ModelImageEntry =
		ModelImageEntry().apply {
			this.atlas = atlas
			this.modelImageGuid = modelImageGuid
			if (includeAutoLayoutLock) autoLayoutLock = AutoLayoutLock.NONE
			atlasLocalToCanvasTransform = atlasLocalToCanvas
			materialLocalToAtlasTransform = packing
		}

	/**
	 * The model image's material-local-to-canvas placement: the page fit composed with the patch
	 * origin, so patch pixel (0,0) maps to the canvas point the page fit sends (x0, y0) to.
	 *
	 * CMO3: CModelImage field _materialLocalToCanvasTransform (official layers carry their canvas
	 * origin here - translate(144, 222) in ModelWithOffscreenPartClipping).
	 *
	 * @param CAffine pageFit The atlas-page-to-canvas fit.
	 * @param Int     x0      The patch origin x on the page.
	 * @param Int     y0      The patch origin y on the page.
	 * @return CAffine The material-local placement.
	 */
	private fun materialLocalToCanvas(pageFit: CAffine, x0: Int, y0: Int): CAffine =
		pageFit.copyAffine().apply {
			m02 = pageFit.m00 * x0 + pageFit.m01 * y0 + pageFit.m02
			m12 = pageFit.m10 * x0 + pageFit.m11 * y0 + pageFit.m12
		}

	/**
	 * How far kept coverage grows past the mesh's own triangles, in page pixels.
	 *
	 * Cutting exactly on the outermost edge would fringe the silhouette: the drawable's UVs reach
	 * that edge, so a bilinear tap there blends against the transparent pixel immediately outside.
	 * Two pixels covers a bilinear tap plus a mip level while staying well inside the gutter a
	 * packer leaves between neighbouring patches.
	 */
	private const val COVERAGE_BLEED_MARGIN = 2

	/**
	 * Which pixels of a patch rect the drawable's own mesh covers, grown by [COVERAGE_BLEED_MARGIN].
	 *
	 * A patch rect is the mesh's axis-aligned uv bounding box, so for any mesh that is not itself a
	 * page-aligned rectangle the rect also spans page pixels belonging to whatever the packer nested
	 * into the leftover corners.  Copying the rect verbatim hands those foreign pixels to this
	 * drawable's CModelImage, which the editor treats as the drawable's own artwork - it travels
	 * with the patch when the atlas is rearranged and gets stamped back into the page on a repack.
	 * Masking to the triangles keeps the crop rect (so every placement transform is unchanged) while
	 * dropping pixels the mesh never samples.
	 *
	 * Rasterization is CONSERVATIVE - a pixel counts as covered when its square overlaps a triangle
	 * at all, not when its center happens to land inside one.  Cubism meshes carry long sliver
	 * triangles along a silhouette, and center sampling drops the ones thinner than a pixel.
	 *
	 * @param FloatArray uvs        Interleaved atlas-frame uvs.
	 * @param IntArray   indices    Triangle indices, three per triangle.
	 * @param Int        pageWidth  The page's pixel width.
	 * @param Int        pageHeight The page's pixel height.
	 * @param Int        patchX0    The patch rect's origin x on the page.
	 * @param Int        patchY0    The patch rect's origin y on the page.
	 * @param Int        cropWidth  The patch rect's width.
	 * @param Int        cropHeight The patch rect's height.
	 * @return BooleanArray One flag per crop pixel in row-major order, or null when the mesh carries
	 *         no triangles to mask with (the whole rect is then kept, as before).
	 */
	private fun coverageMaskOf(
		uvs: FloatArray,
		indices: IntArray,
		pageWidth: Int,
		pageHeight: Int,
		patchX0: Int,
		patchY0: Int,
		cropWidth: Int,
		cropHeight: Int,
	): BooleanArray? {
		if (indices.size < 3) {
			return null
		}
		val vertexCount = uvs.size / 2
		val covered = BooleanArray(cropWidth * cropHeight)
		var triangleStart = 0
		while (triangleStart + 2 < indices.size) {
			val indexA = indices[triangleStart]
			val indexB = indices[triangleStart + 1]
			val indexC = indices[triangleStart + 2]
			triangleStart += 3
			if (indexA !in 0 until vertexCount || indexB !in 0 until vertexCount || indexC !in 0 until vertexCount) {
				continue
			}
			val cornerAx = uvs[2 * indexA].toDouble() * pageWidth - patchX0
			val cornerAy = uvs[2 * indexA + 1].toDouble() * pageHeight - patchY0
			val cornerBx = uvs[2 * indexB].toDouble() * pageWidth - patchX0
			val cornerBy = uvs[2 * indexB + 1].toDouble() * pageHeight - patchY0
			val cornerCx = uvs[2 * indexC].toDouble() * pageWidth - patchX0
			val cornerCy = uvs[2 * indexC + 1].toDouble() * pageHeight - patchY0
			val doubledArea = (cornerBx - cornerAx) * (cornerCy - cornerAy) - (cornerCx - cornerAx) * (cornerBy - cornerAy)
			if (!doubledArea.isFinite() || doubledArea == 0.0) {
				continue
			}
			// Normalize the winding so "inside" is uniformly a non-negative edge value.
			val winding = if (doubledArea > 0.0) 1.0 else -1.0
			// One extra pixel each way: the conservative test below reaches half a pixel past the
			// triangle, and a bounding box rounded inward would clip that reach off.
			val firstColumn = kotlin.math.floor(minOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) - 1
			val lastColumn = kotlin.math.ceil(maxOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) + 1
			val firstRow = kotlin.math.floor(minOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) - 1
			val lastRow = kotlin.math.ceil(maxOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) + 1
			for (rowIndex in maxOf(0, firstRow)..minOf(cropHeight - 1, lastRow)) {
				val pointY = rowIndex + 0.5
				for (columnIndex in maxOf(0, firstColumn)..minOf(cropWidth - 1, lastColumn)) {
					val flatIndex = rowIndex * cropWidth + columnIndex
					if (covered[flatIndex]) {
						continue
					}
					val pointX = columnIndex + 0.5
					if (overlapsEdge(cornerAx, cornerAy, cornerBx, cornerBy, pointX, pointY, winding) &&
						overlapsEdge(cornerBx, cornerBy, cornerCx, cornerCy, pointX, pointY, winding) &&
						overlapsEdge(cornerCx, cornerCy, cornerAx, cornerAy, pointX, pointY, winding)
					) {
						covered[flatIndex] = true
					}
				}
			}
		}
		return dilate(covered, cropWidth, cropHeight, COVERAGE_BLEED_MARGIN)
	}

	/**
	 * Whether a pixel's unit square reaches the inside half-plane of one triangle edge.
	 *
	 * The edge value at the pixel center varies by at most half the sum of the edge normal's
	 * components across the square, so adding that slack turns a center test into a square-overlap
	 * test - which is what keeps sub-pixel slivers from vanishing.
	 *
	 * @param Double fromX   Edge start x, in crop-local pixels.
	 * @param Double fromY   Edge start y.
	 * @param Double toX     Edge end x.
	 * @param Double toY     Edge end y.
	 * @param Double pointX  The pixel center's x.
	 * @param Double pointY  The pixel center's y.
	 * @param Double winding +1 when the triangle winds counter-clockwise, -1 when clockwise.
	 * @return Boolean True when the pixel square is not fully outside this edge.
	 */
	private fun overlapsEdge(
		fromX: Double,
		fromY: Double,
		toX: Double,
		toY: Double,
		pointX: Double,
		pointY: Double,
		winding: Double,
	): Boolean {
		val edgeX = toX - fromX
		val edgeY = toY - fromY
		val edgeValue = winding * (edgeX * (pointY - fromY) - edgeY * (pointX - fromX))
		return edgeValue + 0.5 * (kotlin.math.abs(edgeX) + kotlin.math.abs(edgeY)) >= 0.0
	}

	/**
	 * Grows a coverage mask by [margin] pixels, as two linear-time passes over a sliding window.
	 *
	 * @param BooleanArray covered The raw per-pixel coverage, row-major.
	 * @param Int          width   The mask width.
	 * @param Int          height  The mask height.
	 * @param Int          margin  How many pixels to grow by.
	 * @return BooleanArray The grown mask ([covered] itself when the margin is zero).
	 */
	private fun dilate(covered: BooleanArray, width: Int, height: Int, margin: Int): BooleanArray {
		if (margin <= 0) {
			return covered
		}
		val grownAcross = BooleanArray(covered.size)
		for (rowIndex in 0 until height) {
			val rowStart = rowIndex * width
			var windowCount = 0
			for (columnIndex in 0..minOf(margin, width - 1)) {
				if (covered[rowStart + columnIndex]) {
					windowCount += 1
				}
			}
			for (columnIndex in 0 until width) {
				grownAcross[rowStart + columnIndex] = windowCount > 0
				val leavingColumn = columnIndex - margin
				val enteringColumn = columnIndex + margin + 1
				if (leavingColumn >= 0 && covered[rowStart + leavingColumn]) {
					windowCount -= 1
				}
				if (enteringColumn < width && covered[rowStart + enteringColumn]) {
					windowCount += 1
				}
			}
		}
		val grown = BooleanArray(covered.size)
		for (columnIndex in 0 until width) {
			var windowCount = 0
			for (rowIndex in 0..minOf(margin, height - 1)) {
				if (grownAcross[rowIndex * width + columnIndex]) {
					windowCount += 1
				}
			}
			for (rowIndex in 0 until height) {
				grown[rowIndex * width + columnIndex] = windowCount > 0
				val leavingRow = rowIndex - margin
				val enteringRow = rowIndex + margin + 1
				if (leavingRow >= 0 && grownAcross[leavingRow * width + columnIndex]) {
					windowCount -= 1
				}
				if (enteringRow < height && grownAcross[enteringRow * width + columnIndex]) {
					windowCount += 1
				}
			}
		}
		return grown
	}

	/**
	 * Copies the patch rect out of a decoded page, clearing pixels outside [coverage].
	 *
	 * @param RasterImage  decodedPage The decoded page pixels.
	 * @param Int          x0          Patch origin x.
	 * @param Int          y0          Patch origin y.
	 * @param Int          cropWidth   Patch width.
	 * @param Int          cropHeight  Patch height.
	 * @param BooleanArray coverage    The mesh coverage mask, or null to keep the whole rect.
	 * @return RasterImage The masked crop pixels.
	 */
	private fun maskedCropOf(
		decodedPage: RasterImage,
		x0: Int,
		y0: Int,
		cropWidth: Int,
		cropHeight: Int,
		coverage: BooleanArray?,
	): RasterImage {
		val cropRgba = ByteArray(cropWidth * cropHeight * 4)
		for (rowIndex in 0 until cropHeight) {
			val sourceOffset = ((y0 + rowIndex) * decodedPage.width + x0) * 4
			val targetOffset = rowIndex * cropWidth * 4
			decodedPage.rgba.copyInto(cropRgba, targetOffset, sourceOffset, sourceOffset + cropWidth * 4)
			if (coverage == null) {
				continue
			}
			for (columnIndex in 0 until cropWidth) {
				if (coverage[rowIndex * cropWidth + columnIndex]) {
					continue
				}
				val pixelOffset = targetOffset + columnIndex * 4
				cropRgba.fill(0, pixelOffset, pixelOffset + 4)
			}
		}
		return RasterImage(cropWidth, cropHeight, cropRgba)
	}

	/**
	 * The crop web's one model-image group and the two lists the pages append to, minted only once a
	 * drawable actually takes the crop path.
	 *
	 * @property CModelImageGroup group               The group.
	 * @property CArrayList       linkedRawImageGuids The stand-in documents' guids, one per cropped page.
	 * @property CArrayList       modelImages         The crop model images.
	 */
	private class CropGroup(
		val group: CModelImageGroup,
		val linkedRawImageGuids: CArrayList<Any?>,
		val modelImages: CArrayList<Any?>,
	)

	/**
	 * Populates [root]'s texture manager with one page chain per atlas page, the real-layer web for
	 * every artwork file in [sourceImages] (a layered image per file, a layer and model image per
	 * tile from the document's own raster, entries and bindings off the tiles' placements), and the
	 * crop web for the drawables [regionsByPage] lists - the MOC3-origin stand-in that slices each
	 * drawable's patch out of its page.  A page with no crop regions mints no stand-in document, so an
	 * artwork-origin file carries none.
	 *
	 * @param CModelSource root          The fresh skeleton root (its textureManager must exist).
	 * @param List         pages         The atlas pages, in model3 texture order.
	 * @param List         regionsByPage Each page's crop-path drawable regions (geometry for the patch
	 *                                   crop and the placement fit), indexed like [pages].
	 * @param Long         nowMillis     The import timestamp the wrappers record (caller-supplied
	 *                                   so tests stay deterministic).
	 * @param Boolean      fromSourceLayers The document's display mode: true writes the texture
	 *                                   manager's combined-layer mode, false the packed-atlas one.
	 * @param List         sourceImages  The artwork files whose tiles have real art, with their layers.
	 * @return BuiltImageChain The PNG entries plus the texture bindings.
	 */
	internal fun populate(
		root: CModelSource,
		pages: List<Cmo3Conversion.AtlasPage>,
		regionsByPage: List<List<DrawableRegion>>,
		nowMillis: Long,
		fromSourceLayers: Boolean = false,
		sourceImages: List<Cmo3SourceLayerWeb.SourceImageInput> = emptyList(),
	): BuiltImageChain {
		val textureManager = root.textureManager as? CTextureManager ?: error("skeleton has no texture manager")
		val names = Cmo3FreshChainNames(includeAutoLayoutLock = (root.targetVersionNo as? Int)?.supportsCmo3V54Classes == true)
		val rawImages =
			mutableGraphListOf(textureManager._rawImages) ?: error("skeleton texture manager has no raw-image list")
		val textureAtlases =
			mutableGraphListOf(textureManager._textureAtlases) ?: error("skeleton texture manager has no texture-atlas list")
		val modelImageGroups =
			mutableGraphListOf(textureManager._modelImageGroups) ?: error("skeleton texture manager has no model-image-group list")
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>()
		val bindingByDrawableId = HashMap<String, Cmo3DrawableTextureBinding>()
		val pageFallbackBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		val atlases = ArrayList<CTextureAtlas>(pages.size)
		val textures = ArrayList<GTexture2D>(pages.size)
		var cropGroup: CropGroup? = null
		var cropDrawableCount = 0
		for ((pageIndex, page) in pages.withIndex()) {
			val pagePath = names.nextImageFileBufPath()
			pngEntries.add(Cmo3FreshFile.PngEntry(pagePath, page.pngBytes))
			val pageResource = pageImageResource(pagePath, page.width, page.height, page.pngBytes.size)
			val atlas = pageAtlas(pageIndex, page.width, page.height, pageResource)
			val texture = pageTexture(atlas.name, pageResource)
			atlases.add(atlas)
			textures.add(texture)
			val regions = regionsByPage.getOrNull(pageIndex).orEmpty()
			if (regions.isNotEmpty()) {
				val group =
					cropGroup ?: run {
						val linkedRawImageGuids = CArrayList<Any?>()
						val modelImages = CArrayList<Any?>()
						val minted =
							CModelImageGroup().apply {
								memo = ""
								groupName = "Textures"
								_linkedRawImageGuids = linkedRawImageGuids
								_modelImages = modelImages
							}
						CropGroup(minted, linkedRawImageGuids, modelImages).also { cropGroup = it }
					}
				cropDrawableCount += regions.size
				rawImages.add(cropWeb(page, pageIndex, regions, atlas, texture, group, names, pngEntries, bindingByDrawableId, nowMillis))
			}
			pageFallbackBindings.add(Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, CAffine()))
			textureAtlases.add(atlas)
		}
		cropGroup?.let { group -> modelImageGroups.add(group.group) }
		for (image in sourceImages) {
			val written = Cmo3SourceLayerWeb.write(image, atlases, textures, names, pngEntries, nowMillis)
			rawImages.add(written.wrapper)
			modelImageGroups.add(written.group)
			bindingByDrawableId.putAll(written.bindingByDrawableId)
		}
		// CMO3: CTextureManager field isTextureInputModelImageMode - the document's own display mode.
		// The synthesized web carries BOTH inputs per drawable (a model image and an atlas region), so
		// either mode is representable; the diff-driven lowering retargets currentTextureInputData to
		// match.  This path is not covered by that lowering, so it reads the model directly rather than
		// hardcoding a mode the document may not be in.
		textureManager.isTextureInputModelImageMode = fromSourceLayers
		return BuiltImageChain(pngEntries, bindingByDrawableId, pageFallbackBindings, cropDrawableCount)
	}

	/**
	 * One model image over one layer: the editor's layer-filter web selecting [layer] at identity,
	 * the layer's own resource as the filtered image, the padded cache manager over it, the icon of
	 * its pixels, and the canvas placement.  Shared by the crop web and the real-layer web, so a crop
	 * and a real layer carry field-for-field the same shape around their pixels.
	 *
	 * @param String           name         The model image's name (the drawable's, as the editor writes it).
	 * @param CLayeredImage    layeredImage The layered image the layer belongs to.
	 * @param CLayer           layer        The layer the image composites.
	 * @param CImageResource   resource     The layer's own resource, which is also the filtered image.
	 * @param RasterImage      raster       The resource's pixels, which the icon shows fitted.
	 * @param CAffine          placement    The material-local-to-canvas placement, an independent instance.
	 * @param CModelImageGroup group        The group the image belongs to.
	 * @param Cmo3FreshChainNames names     The document's shared filter definitions and naming counters.
	 * @param MutableList      pngEntries   The PNG entry collector, for the icon.
	 * @param Long             nowMillis    The timestamp the env values record.
	 * @return CModelImage The model image.
	 */
	internal fun modelImageOver(
		name: String,
		layeredImage: CLayeredImage,
		layer: CLayer,
		resource: CImageResource,
		raster: RasterImage,
		placement: CAffine,
		group: CModelImageGroup,
		names: Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		nowMillis: Long,
	): CModelImage =
		CModelImage().apply {
			guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
			this.name = name
			// CMO3: CModelImage fields inputFilter / inputFilterEnv - the layer-filter web selecting
			// this layer; the deserializer dereferences both.
			inputFilter = buildFilterSet(names.filters)
			inputFilterEnv =
				ModelImageFilterEnv().apply {
					envValues =
						CHashMap<Any?, Any?>().apply {
							put(
								names.filters.currentImageGuid,
								EnvValueSet().apply {
									id = names.filters.currentImageGuid
									value = layeredImage.guid
									updateTimeMs = nowMillis
								},
							)
							put(
								names.filters.inputLayerData,
								EnvValueSet().apply {
									id = names.filters.inputLayerData
									value =
										CLayerSelectorMap().apply {
											_imageToLayerInput =
												LinkedHashMap<Any?, Any?>().apply {
													put(
														layeredImage.guid,
														ArrayList<Any?>(
															mutableListOf(
																CLayerInputData().apply {
																	this.layer = layer
																	affine = CAffine()
																},
															),
														),
													)
												}
										}
									updateTimeMs = nowMillis
								},
							)
						}
				}
			_filteredImage = resource
			// CMO3: CModelImage fields icon16 / cachedImageManager - present on every corpus model
			// image; the icon is the filtered image fitted into its square.
			icon16 = Cmo3Icons.iconOf(raster, 16, names.nextIconPath(), pngEntries)
			// CMO3: CModelImage field _materialLocalToCanvasTransform - the layer's canvas placement
			// (official layers carry their canvas origin here), the same numbers the layer's
			// boundsOnImageDoc origin carries.
			_materialLocalToCanvasTransform = placement
			_group = group
			linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
			cachedImageManager = paddedCacheManager(resource, resource.width, resource.height)
			memo = ""
		}

	/**
	 * The crop web for one page: a stand-in layered image whose frame is the page itself, one layer
	 * and model image per distinct (patch, mesh) crop, an entry per crop on the page's atlas, and a
	 * binding per drawable.  The MOC3-origin path: the only raw material a bake leaves is the page,
	 * so each drawable's patch is sliced back out of it (see the file comment).
	 *
	 * @param Cmo3Conversion.AtlasPage page       The page.
	 * @param Int                      pageIndex  The page's index.
	 * @param List                     regions    The crop-path drawables on the page, at least one.
	 * @param CTextureAtlas            atlas      The page's atlas element.
	 * @param GTexture2D               texture    The page's shared texture.
	 * @param CropGroup                group      The crop web's model-image group.
	 * @param Cmo3FreshChainNames      names      The document's shared definitions and naming counters.
	 * @param MutableList              pngEntries The PNG entry collector.
	 * @param MutableMap               bindingByDrawableId The binding collector.
	 * @param Long                     nowMillis  The import timestamp the wrapper records.
	 * @return LayeredImageWrapper The stand-in document's wrapper, for the texture manager's raw-image list.
	 */
	private fun cropWeb(
		page: Cmo3Conversion.AtlasPage,
		pageIndex: Int,
		regions: List<DrawableRegion>,
		atlas: CTextureAtlas,
		texture: GTexture2D,
		group: CropGroup,
		names: Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		bindingByDrawableId: MutableMap<String, Cmo3DrawableTextureBinding>,
		nowMillis: Long,
	): LayeredImageWrapper {
		val pageName = "Texture_$pageIndex.png"
		val layeredImage = CLayeredImage()
		val patchLayers = CArrayList<Any?>()
		val rootLayerGroup =
			CLayerGroup().apply {
				name = "root"
				memo = ""
				isVisible = true
				blend = names.sharedBlend
				guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
				opacity255 = 255
				_optionOfIOption = names.sharedOptions
				_layeredImage = layeredImage
				_children = patchLayers
			}
		val layerEntryList = CArrayList<Any?>(mutableListOf<Any?>(rootLayerGroup))
		layeredImage.apply {
			name = pageName
			memo = ""
			// CMO3: CLayeredImage width/height - the SOURCE document's own frame, unrelated to
			// the canvas (ModelWithOffscreenPartClipping: a 500x500 doc in a 1000x2000 canvas).
			// Our synthetic source document IS the atlas page, so the page dims are its frame.
			width = page.width
			height = page.height
			// A rendered page has no source file on anyone's disk; the bare name is the honest
			// breadcrumb (the editor stores the importing machine's absolute path here).
			psdFile = FileRef().apply { textPath = pageName }
			description = ""
			guid = Cmo3SkeletonBuilder.freshGuid("CLayeredImageGuid")
			psdFileLastModified = nowMillis
			_rootLayer = rootLayerGroup
			layerSet =
				LayerSet().apply {
					_layeredImage = layeredImage
					_layerEntryList = layerEntryList
				}
		}
		val wrapper =
			LayeredImageWrapper().apply {
				image = layeredImage
				importedTimeMSec = nowMillis
				lastModifiedTimeMSec = nowMillis
			}
		val atlasEntries = checkNotNull(mutableGraphListOf(atlas.modelImages)) { "pageAtlas builds a list" }
		// Patch webs, shared across drawables sampling the same crop with the same mesh (mirror
		// twins get ONE material like official files; each twin's placement rides its own
		// region input, and the shared image keeps the first drawable's placement).
		val decodedPage = PngCodec.read(page.pngBytes)
		val patchWebByKey = HashMap<PatchWebKey, PatchWeb>()

		/**
		 * An icon entry of its own for one drawable, over PNG bytes twins share.
		 *
		 * @param ByteArray png  The encoded icon.
		 * @param Int       size The square's edge.
		 * @return CImageIcon The icon.
		 */
		fun iconEntry(png: ByteArray, size: Int): CImageIcon {
			val path = names.nextIconPath()
			pngEntries.add(Cmo3FreshFile.PngEntry(path, png))
			return Cmo3Icons.iconReferencing(size, path)
		}
		for (region in regions) {
			val pageFit = fitAtlasPageToCanvasTransform(region.uvs, region.positions, page.width, page.height)
			val patch = patchRectOf(region.uvs, page.width, page.height)
			if (patch == null) {
				bindingByDrawableId[region.drawableIdStr] =
					Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, pageFit)
				continue
			}
			val patchX0 = patch[0]
			val patchY0 = patch[1]
			val cropWidth = patch[2] - patch[0]
			val cropHeight = patch[3] - patch[1]
			val webKey = PatchWebKey(patch, region.uvs, region.indices)
			val patchWeb =
				patchWebByKey.getOrPut(webKey) {
					val coverage =
						coverageMaskOf(region.uvs, region.indices, page.width, page.height, patchX0, patchY0, cropWidth, cropHeight)
					val maskedCrop = maskedCropOf(decodedPage, patchX0, patchY0, cropWidth, cropHeight, coverage)
					// Trim the masked crop to its opaque pixel bounds: an official layer rect
					// records the ART's own bounds, not the mesh's reach (the auto-mesh margin
					// the uv bbox includes), and trimming lands within ~1px median of the
					// editor's own rects on the EricaTamamo differential.  A fully transparent
					// crop (a mesh over empty page pixels) keeps the untrimmed rect.
					val opaqueBounds =
						analyzeAlpha(cropWidth, cropHeight, maskedCrop.rgba, contourEpsilon = 0f)?.opaqueBounds
					val trimmedCrop = if (opaqueBounds == null) maskedCrop else maskedCrop.cropped(opaqueBounds)
					val trimmedX0 = patchX0 + (opaqueBounds?.left ?: 0)
					val trimmedY0 = patchY0 + (opaqueBounds?.top ?: 0)
					// Anchor the web on an INTEGER canvas placement, official-style: every
					// official _materialLocalToCanvasTransform translation is a whole number
					// (176 of 176 on EricaTamamo) while the packing origin and the page fit
					// carry complementary FRACTIONS whose composition reproduces it exactly.
					// So snap the placement to the nearest canvas pixel and back-solve the
					// declared packing origin through the fit - the whole chain then composes
					// without a rounding step, and boundsOnImageDoc equals the placement
					// bit-for-bit instead of disagreeing by the rounding residue (the editor's
					// source-image view shows that residue as a subtle per-layer misplacement).
					val patchPlacement = materialLocalToCanvas(pageFit, trimmedX0, trimmedY0)
					patchPlacement.m02 = kotlin.math.round(patchPlacement.m02)
					patchPlacement.m12 = kotlin.math.round(patchPlacement.m12)
					val packingOrigin = solvePageFitFor(pageFit, patchPlacement.m02, patchPlacement.m12)
					val cropBytes = PngCodec.write(trimmedCrop)
					val cropPath = names.nextImageFileBufPath()
					pngEntries.add(Cmo3FreshFile.PngEntry(cropPath, cropBytes))
					val cropResource =
						CImageResource().apply {
							// CMO3: CImageResource - the drawable's patch cropped out of the page.
							width = trimmedCrop.width
							height = trimmedCrop.height
							type = "INT_ARGB"
							imageFileBuf = FileRef().apply { archivePath = cropPath }
							imageFileBuf_size = cropBytes.size
						}
					val patchLayer =
						CLayer().apply {
							// CMO3: CLayer - the patch as its own layer on the canvas-frame doc,
							// layerId null (no PSD layer identity exists for a rendered atlas).
							name = region.drawableIdStr
							memo = ""
							isVisible = true
							blend = names.sharedBlend
							guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
							opacity255 = 255
							_optionOfIOption = names.sharedOptions
							_layeredImage = layeredImage
							imageResource = cropResource
							// CMO3: CLayer field boundsOnImageDoc - the layer's pixel rect on the
							// layered-image doc: origin = the placement translation (equals the
							// CModelImage's _materialLocalToCanvasTransform translation on all 892
							// corpus layers), size = the imageResource dims (also 892 of 892).
							boundsOnImageDoc =
								CRect().apply {
									// The placement translation is already snapped integral, so
									// this equals the transform without a second rounding.
									x = patchPlacement.m02.toInt()
									y = patchPlacement.m12.toInt()
									width = trimmedCrop.width
									height = trimmedCrop.height
								}
							layerIdentifier =
								CLayerIdentifier().apply {
									layerName = region.drawableIdStr
									layerIdValue_testImpl = -1
								}
							// CMO3: CLayer fields icon16 / icon64 - thumbnails on every corpus layer,
							// the crop fitted into each square.
							icon16 = Cmo3Icons.iconOf(trimmedCrop, 16, names.nextIconPath(), pngEntries)
							icon64 = Cmo3Icons.iconOf(trimmedCrop, 64, names.nextIconPath(), pngEntries)
							layerInfo = LinkedHashMap<String, Any?>()
							this.group = rootLayerGroup
						}
					patchLayers.add(patchLayer)
					layerEntryList.add(patchLayer)
					val patchImage =
						modelImageOver(region.drawableIdStr, layeredImage, patchLayer, cropResource, trimmedCrop, patchPlacement.copyAffine(), group.group, names, pngEntries, nowMillis)
					group.modelImages.add(patchImage)
					atlasEntries.add(
						// The declared packing origin is the fit-inverse of the snapped placement
						// (fractional, like every official entry), NOT the raw crop rect origin, so
						// the web composes to the integer placement exactly.
						packedEntry(
							atlas = atlas,
							modelImageGuid = patchImage.guid,
							atlasLocalToCanvas = pageFit.copyAffine(),
							includeAutoLayoutLock = names.includeAutoLayoutLock,
							packing =
								writePacking(
									GTransform2(),
									positionX = packingOrigin?.get(0) ?: trimmedX0.toFloat(),
									positionY = packingOrigin?.get(1) ?: trimmedY0.toFloat(),
									scaleX = 1f,
									scaleY = 1f,
									rotationDegrees = 0f,
								),
						),
					)
					// The drawable icons show the crop, which IS the mesh's uv box; twins share the
					// bytes and each takes an entry of its own, like the editor's one icon per drawable.
					PatchWeb(patchImage.guid as Guid, Cmo3Icons.iconPngOf(trimmedCrop, 32), Cmo3Icons.iconPngOf(trimmedCrop, 16))
				}
			bindingByDrawableId[region.drawableIdStr] =
				Cmo3DrawableTextureBinding(
					texture,
					atlas.guid as Guid,
					patchWeb.imageGuid,
					pageFit.copyAffine(),
					icon32 = iconEntry(patchWeb.icon32Png, 32),
					icon16 = iconEntry(patchWeb.icon16Png, 16),
				)
		}
		group.linkedRawImageGuids.add(layeredImage.guid)
		return wrapper
	}

	/**
	 * One minted patch web, shared by every drawable sampling the same crop with the same mesh.
	 *
	 * @property Guid      imageGuid The crop's model image guid.
	 * @property ByteArray icon32Png The 32px drawable icon's bytes.
	 * @property ByteArray icon16Png The 16px drawable icon's bytes.
	 */
	private class PatchWeb(val imageGuid: Guid, val icon32Png: ByteArray, val icon16Png: ByteArray)
}
