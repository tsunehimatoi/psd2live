package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.ACLayerGroup
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterEnv
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef

/**
 * A CMO3's layered-art web read as model state: the atlas itself, which tile each drawable samples,
 * where each tile's pixels live in the graph, and the artwork files the editor decomposed to build it.
 *
 * @property PuppetAtlas atlas               The document's pages and tiles, with each tile's placement.
 * @property Map         tileIdByDrawableId  Raw drawable id to the tile it samples; absent when it has none.
 * @property Map         imageResourceByTile The graph resource holding each tile's pixels, for the
 *   document's pixel supplier - metadata ingest reads no bytes, so decoding stays the caller's.
 * @property List        sources             The layered images the editor imported, as the model's
 *   source list: identity, name, the advisory PSD path, and the layer inventory walked from the
 *   editor's decomposed layer tree ([walkLayeredImage]) - the same walk that mints each tile's binding.
 */
public class Cmo3AtlasIngest(
	public val atlas: PuppetAtlas,
	public val tileIdByDrawableId: Map<String, AtlasTileId>,
	public val imageResourceByTile: Map<AtlasTileId, CImageResource>,
	public val sources: List<ArtSource> = emptyList(),
) {
	public companion object {
		/** What a document with no texture manager, or no model images, ingests to. */
		public val EMPTY: Cmo3AtlasIngest = Cmo3AtlasIngest(PuppetAtlas.Empty, emptyMap(), emptyMap())
	}
}

/**
 * Reads a CMO3's layered-art web into the model's atlas.
 *
 * The web is three joined structures and this walks all three: the texture manager's atlas pages carry
 * a `ModelImageEntry` per packed image (where the packer put it), the model-image groups carry the
 * images themselves (the art, and its size), and each art mesh's texture-input extension names the
 * image it samples.  The join key throughout is the model image's guid, which becomes the tile id.
 *
 * Nothing is decoded here: an image's dimensions come off its resource record, so a model with hundreds
 * of layers ingests without touching a pixel.  [Cmo3AtlasIngest.imageResourceByTile] is what lets the
 * caller decode later, on demand.
 *
 * A drawable's placement is deliberately NOT resolved here.  Whether a drawable reads a packed page or
 * its art directly follows from the document's display mode and whether its tile was packed at all -
 * both of which are model state - so the model derives it (`PuppetModel.atlasBindingFor`) rather than
 * this ingest freezing an answer that an edit would then have to keep in step.
 *
 * @param CModelSource modelSource The CMO3's root model source.
 * @return Cmo3AtlasIngest The atlas, the per-drawable tile binding, and the pixel resources.
 */
public fun cmo3AtlasIngest(modelSource: CModelSource): Cmo3AtlasIngest {
	// CMO3: CModelSource field textureManager - the root of the whole layered-art web.
	val textureManager = modelSource.textureManager as? CTextureManager ?: return Cmo3AtlasIngest.EMPTY

	// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas fields modelImages / width / height.
	// This page order is the document's, and becomes AtlasPlacement.pageIndex; it is independent of the
	// renderer's own page numbering, which is derived from drawable encounter order.
	val atlases = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
	val pages = atlases.map { atlas -> AtlasPage(atlas.width, atlas.height) }
	// CMO3: CTextureAtlas field cachedAtlasImage - the page's own pixels.  Which resource a drawable's
	// texture points AT is what says whether its stored coordinates are page-space, and it is a fact of
	// the file rather than of the display mode: flipping the mode retargets currentTextureInputData and
	// leaves this alone.  Identity membership is the test, CImageResource being a plain class.
	val atlasPageResources = atlases.mapNotNull { atlas -> atlas.cachedAtlasImage as? CImageResource }.toHashSet()
	val placementByModelImageGuid = HashMap<String, AtlasPlacement>()
	for ((pageIndex, atlas) in atlases.withIndex()) {
		for (entry in Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
			// CMO3: ModelImageEntry field modelImageGuid - the join back to the pooled model image.
			val modelImageGuid = Cmo3Import.uuidOf(entry.modelImageGuid) ?: continue
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
		}
	}

	// CMO3: CTextureManager field _rawImages -> LayeredImageWrapper field image -> CLayeredImage fields
	// guid / name / psdFile - the artwork files the editor decomposed at import, each keyed by the guid
	// the selector maps below reference.  psdFile is the external-reference <file> shape: its text is
	// the absolute path on the importing machine (docs/format/CMO3.md section 4), advisory here.  The
	// field's legacy name notwithstanding, a flat raster import lands in the same structure, so the
	// format is read off the name's extension rather than assumed.
	val layeredImages =
		Cmo3Import.elementsOf(textureManager._rawImages).mapNotNull { wrapper -> (wrapper as? LayeredImageWrapper)?.image as? CLayeredImage }
	val sources = ArrayList<ArtSource>(layeredImages.size)
	val refByLayerGuidBySource = HashMap<String, Map<String, SourceLayerRef>>()
	for (image in layeredImages) {
		val guid = Cmo3Import.uuidOf(image.guid) ?: continue
		if (guid in refByLayerGuidBySource) {
			continue
		}
		val name = image.name?.takeIf { candidate -> candidate.isNotEmpty() } ?: guid
		val inventory = layeredImageInventory(image, ArtSourceId(guid))
		refByLayerGuidBySource[guid] = inventory.refByLayerGuid
		sources.add(
			ArtSource(
				id = ArtSourceId(guid),
				name = name,
				path = (image.psdFile as? FileRef)?.textPath?.takeIf { path -> path.isNotEmpty() },
				format = name.substringAfterLast('.', missingDelimiterValue = "psd").lowercase(),
				layers = inventory.rows,
				// CMO3: CLayeredImage field psdFileLastModified - the source's modification time when the
				// editor imported it (epoch milliseconds), zero when it never recorded one.
				lastModified = image.psdFileLastModified.takeIf { millis -> millis > 0L },
			),
		)
	}

	// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages.
	val modelImages =
		Cmo3Import.elementsOf(textureManager._modelImageGroups)
			.filterIsInstance<CModelImageGroup>()
			.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
	val tiles = ArrayList<AtlasTile>(modelImages.size)
	val imageResourceByTile = HashMap<AtlasTileId, CImageResource>()
	val knownTileIds = HashSet<String>()
	for (modelImage in modelImages) {
		// CMO3: CModelImage field guid - the key every binding and placement references.
		val key = Cmo3Import.uuidOf(modelImage.guid) ?: continue
		// CMO3: CModelImage field _filteredImage - the baked composite raster, carrying its own
		// dimensions, so the inventory needs no decode.  An image with no resource has no art to show
		// and no size to place, so it is not a tile at all.
		val resource = modelImage._filteredImage as? CImageResource ?: continue
		val tileId = AtlasTileId(key)
		knownTileIds.add(key)
		imageResourceByTile[tileId] = resource
		tiles.add(
			AtlasTile(
				id = tileId,
				// CMO3: CModelImage field name - the editor writes the drawable's own name here.
				name = displayNameOf(modelImage.name) ?: key,
				width = resource.width,
				height = resource.height,
				placement = placementByModelImageGuid[key],
				source = soleSourceLayerRefOf(modelImage, refByLayerGuidBySource),
			),
		)
	}

	// CMO3: CDrawableSourceSet field _sources -> CArtMeshSource field _extensions.
	val artMeshes =
		Cmo3Import.elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
			.filterIsInstance<CArtMeshSource>()
	val tileIdByDrawableId = HashMap<String, AtlasTileId>()
	var anyDrawableSamplesAPage = false
	for (mesh in artMeshes) {
		val drawableId = Cmo3Import.idStrOf(mesh.id) ?: continue
		// CMO3: CTextureInputExtension field _textureInputs -> CTextureInput_ModelImage field
		// _modelImageGuid.  A drawable carrying only an atlas-region input (no model image) has no
		// source art and is simply absent from the map.
		val extension =
			Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
		val modelImageInput =
			Cmo3Import.elementsOf(extension._textureInputs).filterIsInstance<CTextureInput_ModelImage>().firstOrNull()
				?: continue
		val key = Cmo3Import.uuidOf(modelImageInput._modelImageGuid) ?: continue
		if (key !in knownTileIds) {
			continue
		}
		// CMO3: CArtMeshSource field texture -> GTexture2D field srcImageResource.
		val sampledResource = (mesh.texture as? GTexture2D)?.srcImageResource as? CImageResource
		if (sampledResource != null && sampledResource in atlasPageResources) {
			anyDrawableSamplesAPage = true
		}
		tileIdByDrawableId[drawableId] = AtlasTileId(key)
	}

	return Cmo3AtlasIngest(
		PuppetAtlas(pages, tiles, storedUvsAddressPages = anyDrawableSamplesAPage),
		tileIdByDrawableId,
		imageResourceByTile,
		sources,
	)
}

/**
 * One decomposed artwork file's layer table: the inventory rows in the editor's stored order, and the
 * binding each layer's guid resolves to, so a tile's binding and the inventory row it names are minted
 * by the same pass and cannot disagree.
 *
 * @property List rows           The inventory rows.
 * @property Map  refByLayerGuid Each image layer's binding, keyed by the layer entry's guid.
 */
private class LayeredImageInventory(val rows: List<ArtSourceLayer>, val refByLayerGuid: Map<String, SourceLayerRef>)

/**
 * One image layer of a decomposed artwork file as the walk found it: the entry, the key the walk
 * minted for it, whether that key is stable, its folder path, and its place in the stored order.
 *
 * @property CLayer  layer     The layer entry.
 * @property String  key       The binding key ("lyid:<id>", or "name:<name>" with an order suffix on a duplicate).
 * @property Boolean stable    Whether the key survives a rename (an unsuffixed Photoshop layer id).
 * @property String  groupPath The slash-joined enclosing-folder path ("" at the root).
 * @property Int     order     The layer's index in the stored order, top-most first.
 */
internal class WalkedCmo3Layer(
	val layer: CLayer,
	val key: String,
	val stable: Boolean,
	val groupPath: String,
	val order: Int,
)

/**
 * Walks one decomposed artwork file's layer tree: every image layer under its root group, in the
 * editor's stored order, with its key.
 *
 * Keys are minted the way the PSD reader mints them, so a CMO3-origin document and a fresh read of
 * the same file agree on a layer's identity: "lyid:<id>" when the editor recorded Photoshop's layer
 * id (a stable key, the reader's own), else "name:<name>", which is unique within the image only by
 * construction - a repeated name takes an order suffix ("name:1#2") on every duplicate after the
 * first, and any suffixed key is unstable by definition, because the suffix depends on the tree order.
 *
 * @param CLayeredImage image The layered image to walk.
 * @return List<WalkedCmo3Layer> The image layers, empty when the image has no layer tree.
 */
internal fun walkLayeredImage(image: CLayeredImage): List<WalkedCmo3Layer> {
	// CMO3: CLayeredImage field _rootLayer -> CLayerGroup, whose ACLayerGroup field _children holds the
	// image layers (CLayer) and nested folders (CLayerGroup) of the decomposed file.
	val root = image._rootLayer as? ACLayerGroup ?: return emptyList()
	val walked = ArrayList<WalkedCmo3Layer>()
	val duplicateCountByKey = HashMap<String, Int>()

	fun walk(group: ACLayerGroup, path: String) {
		for (entry in Cmo3Import.elementsOf(group._children)) {
			when (entry) {
				is ACLayerGroup -> {
					// CMO3: ACLayerEntry field name - the folder's own name, one segment of the path.
					val name = entry.name.orEmpty()
					walk(entry, if (path.isEmpty()) name else "$path/$name")
				}
				is CLayer -> {
					// CMO3: ACLayerEntry field name.
					val name = entry.name.orEmpty()
					val photoshopLayerId = photoshopLayerIdOf(entry.layerIdentifier)
					val baseKey = if (photoshopLayerId != null) "lyid:$photoshopLayerId" else "name:$name"
					val duplicateOrdinal = (duplicateCountByKey[baseKey] ?: 0) + 1
					duplicateCountByKey[baseKey] = duplicateOrdinal
					val key = if (duplicateOrdinal == 1) baseKey else "$baseKey#$duplicateOrdinal"
					walked.add(WalkedCmo3Layer(entry, key, stable = photoshopLayerId != null && duplicateOrdinal == 1, groupPath = path, order = walked.size))
				}
			}
		}
	}
	walk(root, "")
	return walked
}

/**
 * The layer inventory of one decomposed artwork file, over [walkLayeredImage].
 *
 * @param CLayeredImage image    The layered image to walk.
 * @param ArtSourceId   sourceId The source id the bindings carry.
 * @return LayeredImageInventory The rows and the per-layer bindings, both empty when the image has no
 *   layer tree.
 */
private fun layeredImageInventory(image: CLayeredImage, sourceId: ArtSourceId): LayeredImageInventory {
	val rows = ArrayList<ArtSourceLayer>()
	val refByLayerGuid = HashMap<String, SourceLayerRef>()
	for (walked in walkLayeredImage(image)) {
		val entry = walked.layer
		// CMO3: ACLayerEntry fields isVisible / guid; CLayer field boundsOnImageDoc, a CRect (x / y /
		// width / height) placing the layer on the source document.
		val bounds = entry.boundsOnImageDoc as? CRect
		Cmo3Import.uuidOf(entry.guid)?.let { layerGuid ->
			refByLayerGuid[layerGuid] = SourceLayerRef(sourceId, layerKey = walked.key, stableKey = walked.stable)
		}
		rows.add(
			ArtSourceLayer(
				key = walked.key,
				name = entry.name.orEmpty(),
				groupPath = walked.groupPath,
				left = bounds?.x ?: 0,
				top = bounds?.y ?: 0,
				width = bounds?.width ?: 0,
				height = bounds?.height ?: 0,
				visible = entry.isVisible,
			),
		)
	}
	return LayeredImageInventory(rows, refByLayerGuid)
}

/**
 * The Photoshop layer id the editor recorded for a decomposed layer, or null when it recorded none.
 *
 * @param Any? identifier The layer's CLayerIdentifier slot.
 * @return Int? The id, as the PSD reader's lyid integer.
 */
private fun photoshopLayerIdOf(identifier: Any?): Int? {
	// CMO3: CLayerIdentifier field layerId - Photoshop's lyid (the additional-layer-info block the PSD
	// reader keys on, docs/format/PSD.md) written as up to four dash-separated hex bytes, big-endian
	// ("00-00-06-28" is 1576); null when the import was not a PSD.  layerIdValue_testImpl mirrors it in
	// decimal (-1 when absent) and is not read: the byte string is the field the editor names as the id.
	val text = (identifier as? CLayerIdentifier)?.layerId?.takeIf { candidate -> candidate.isNotEmpty() } ?: return null
	val byteTexts = text.split('-')
	if (byteTexts.size > 4) {
		return null
	}
	var value = 0L
	for (byteText in byteTexts) {
		val byteValue = byteText.toIntOrNull(16) ?: return null
		if (byteValue !in 0..255) {
			return null
		}
		value = (value shl 8) or byteValue.toLong()
	}
	return value.toInt()
}

/**
 * The one layer input a model image composites, with the layered image it comes from.
 *
 * @property String          imageGuid The layered image's guid (uuid text).
 * @property CLayerInputData input     The layer input, whose `layer` is the composited entry.
 */
internal class SoleLayerInput(val imageGuid: String, val input: CLayerInputData)

/**
 * The one layer input of a model image's selector map, when exactly one artwork layer composites
 * into it - which is every model image across the corpus - else null.  Shared by the ingest's binding
 * walk and the export's retained-layer rewrite, so both agree on which layer a tile IS.
 *
 * @param CModelImage modelImage The model image to resolve.
 * @return SoleLayerInput? The input, or null.
 */
internal fun soleLayerInputOf(modelImage: CModelImage): SoleLayerInput? {
	// CMO3: CModelImage field inputFilterEnv -> ModelImageFilterEnv (a FilterEnv) field envValues, a map
	// from filter-value ids to EnvValueSet; the set under mi_input_layerInputData holds the
	// CLayerSelectorMap whose _imageToLayerInput maps each layered image's guid to the list of layer
	// inputs composited from it.  Found by the value's type rather than the id: the id is an Id node
	// whose equality the deserializer does not promise, and only one set holds a selector map.
	val filterEnv = modelImage.inputFilterEnv as? FilterEnv ?: return null
	val selectorMap =
		Cmo3Import.elementsOf(filterEnv.envValues).filterIsInstance<EnvValueSet>()
			.firstNotNullOfOrNull { valueSet -> valueSet.value as? CLayerSelectorMap } ?: return null
	val imageToInputs = selectorMap._imageToLayerInput as? Map<*, *> ?: return null
	var soleImageGuid: String? = null
	var soleInput: CLayerInputData? = null
	var inputCount = 0
	for ((imageGuid, inputs) in imageToInputs) {
		for (input in Cmo3Import.elementsOf(inputs).filterIsInstance<CLayerInputData>()) {
			inputCount++
			soleImageGuid = Cmo3Import.uuidOf(imageGuid)
			soleInput = input
		}
	}
	if (inputCount != 1) {
		return null
	}
	val imageGuid = soleImageGuid ?: return null
	val input = soleInput ?: return null
	return SoleLayerInput(imageGuid, input)
}

/**
 * The source-layer binding of a model image, when exactly one artwork layer composites into it -
 * which is every model image across the corpus - else null.
 *
 * The binding is the one the inventory walk minted for that layer (looked up by the layer entry's
 * guid), so it names an inventory row by construction.  A layer the walk never listed - one outside
 * its image's root tree - falls back to an unstable name key rather than losing the binding.
 *
 * @param CModelImage modelImage             The model image to resolve.
 * @param Map         refByLayerGuidBySource Each listed layered image's per-layer bindings, keyed by
 *   the image guid; a map keyed by an unlisted image binds to nothing rather than to a source the
 *   document does not have.
 * @return SourceLayerRef? The binding, or null.
 */
private fun soleSourceLayerRefOf(modelImage: CModelImage, refByLayerGuidBySource: Map<String, Map<String, SourceLayerRef>>): SourceLayerRef? {
	val sole = soleLayerInputOf(modelImage) ?: return null
	val refByLayerGuid = refByLayerGuidBySource[sole.imageGuid] ?: return null
	// CMO3: CLayerInputData field layer -> ACLayerEntry fields guid / name.
	val layer = sole.input.layer as? ACLayerEntry ?: return null
	Cmo3Import.uuidOf(layer.guid)?.let { layerGuid -> refByLayerGuid[layerGuid] }?.let { ref -> return ref }
	val layerName = layer.name?.takeIf { name -> name.isNotEmpty() } ?: return null
	return SourceLayerRef(ArtSourceId(sole.imageGuid), layerKey = "name:$layerName", stableKey = false)
}

/**
 * Resolves a CMO3 name slot, which the serializer holds as `Any?` and fills with either a plain string
 * or an id-bearing node.
 *
 * @param Any? name The raw name field.
 * @return String? The display name, or null when the slot holds neither shape.
 */
private fun displayNameOf(name: Any?): String? =
	when (name) {
		is String -> name.takeIf { candidate -> candidate.isNotEmpty() }
		is Id -> name.idstr.takeIf { candidate -> candidate.isNotEmpty() }
		else -> null
	}