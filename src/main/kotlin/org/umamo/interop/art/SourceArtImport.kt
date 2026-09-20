package org.umamo.interop.art

import org.umamo.format.art.ChannelMask
import org.umamo.format.art.DEFAULT_ALPHA_THRESHOLD
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.art.isFullyTransparent
import org.umamo.format.binary.contentHashOf
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.ArtworkAdditions
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.OrgInsertion
import org.umamo.runtime.model.OrgSlot
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.deriveRenderRoot

/*
 * The SourceArt -> PuppetModel bridge: the first point where a layered file (or a flat raster)
 * becomes an editable rig.  One drawable per raster layer over a quad birth mesh, one part per
 * folder, one atlas tile per layer bound to its source layer by the reader's key.
 *
 * The model comes out UNPACKED - every tile unplaced, every drawable's coordinates addressing its own
 * art - so this stays a pure conversion that the pack at open then moves onto pages through the same
 * repack primitive the Repack Atlas command uses.  Packing here would only duplicate that path.
 */

/**
 * What the importer records about the file the art came from.
 *
 * @property String  name        The file's display name (its file name).
 * @property String? path        The advisory external path, or null when the platform has none (a SAF uri).
 * @property String  format      The source format's file extension ("psd", "clip", "kra", "png", ...).
 * @property String? contentHash The whole-file content hash of the bytes read, or null when unknown.
 * @property Long?   lastModified The file's modification time (epoch milliseconds) when read, or null when unknown.
 */
class ArtSourceDescriptor(
	val name: String,
	val path: String?,
	val format: String,
	val contentHash: String? = null,
	val lastModified: Long? = null,
)

/**
 * How an import shapes the model it builds.
 *
 * The bridge seeds whatever parameters it is handed and knows nothing about templates: the choice of
 * set (and its default) is editor policy the caller resolves before the import runs, so the bridge's
 * own default is to seed nothing.
 *
 * @property List<Parameter> parameters      The parameters to seed, in panel order.
 * @property Int             alphaThreshold  Minimum alpha byte (1..255) for a pixel to count as art
 *   when a layer is trimmed for its birth mesh; the pack at open trims under the same threshold.
 * @property Int             birthMeshMargin How far, in source pixels, the birth quad extends past
 *   the layer's opaque bounds on every side.
 */
class SourceArtImportOptions(
	val parameters: List<Parameter> = emptyList(),
	val alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	val birthMeshMargin: Int = SourceArtImport.DEFAULT_BIRTH_MESH_MARGIN,
) {
	init {
		require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
		require(birthMeshMargin >= 0) { "birthMeshMargin must be non-negative: $birthMeshMargin" }
	}
}

/**
 * What an import produced.
 *
 * @property PuppetModel puppet       The unpacked model.
 * @property Map         rasterByTile Each tile's pixels, by tile - the document's pixel supplier takes
 *   them from here, since the model itself carries none.
 * @property List        notices      Everything the import could not carry as drawn, in document order.
 */
class SourceArtImportResult(
	val puppet: PuppetModel,
	val rasterByTile: Map<AtlasTileId, LayerRaster>,
	val notices: List<SourceArtImportNotice>,
)

/**
 * What one artwork file adds to a model, with its pixels and its notes: the delta a fresh import
 * assembles into a new model and an open document appends to itself.
 *
 * @property ArtworkAdditions additions    The source record, tiles, drawables, parts, and root order.
 * @property Map              rasterByTile Each added tile's pixels, by tile.
 * @property List             notices      Everything the import could not carry as drawn, in document order.
 */
class SourceArtAdditions(
	val additions: ArtworkAdditions,
	val rasterByTile: Map<AtlasTileId, LayerRaster>,
	val notices: List<SourceArtImportNotice>,
)

/** One raster layer that became a drawable, kept for the passes that run after the first. */
private class ImportedLayer(
	val layer: SourceLayer,
	val drawableId: DrawableId,
)

/**
 * The next ids to mint, continuing past whatever the receiving model already has so an added file's
 * drawables and parts read as the editor's own would (`ArtMesh<n>`, `Part<n>`).
 *
 * @property Int nextDrawable The suffix of the next drawable id.
 * @property Int nextPart     The suffix of the next part id.
 */
private class IdMinter(var nextDrawable: Int, var nextPart: Int)

/**
 * One folder of the source tree while the org tree is being built: its own attributes plus the
 * children gathered under it, each tagged with the order it sorts by.
 */
private class FolderNode(
	val path: String,
	val name: String,
	val visible: Boolean,
	val opacity: Float,
	val clipped: Boolean,
	val blend: LayerBlend,
	val passThrough: Boolean,
) {
	val children = ArrayList<FolderChild>()

	/** The top-most order under this folder, so it sorts among its siblings where its first layer is. */
	var firstOrder: Int = Int.MAX_VALUE
}

/** One direct child of a folder as the FILE orders it: a layer by key, or a sub-folder by path. */
private sealed interface FileSibling {
	val order: Int

	class Layer(override val order: Int, val key: String) : FileSibling

	class Folder(override val order: Int, val path: String) : FileSibling
}

/** One entry of a folder's children: a drawable, or a nested folder, with the order it sorts by. */
private sealed interface FolderChild {
	val order: Int

	class Layer(override val order: Int, val drawableId: DrawableId) : FolderChild

	class Folder(val node: FolderNode) : FolderChild {
		override val order: Int get() = node.firstOrder
	}
}

/**
 * Turns parsed source art into a puppet model - see the file comment for the shape it produces.
 */
object SourceArtImport {
	/** How far the birth quad extends past a layer's opaque bounds, in source pixels. */
	const val DEFAULT_BIRTH_MESH_MARGIN: Int = 2

	/** The id of the one source a first import records; later files continue the sequence. */
	const val FIRST_SOURCE_ID: String = "art-0"

	/**
	 * Builds an unpacked model from [art].
	 *
	 * Layers are visited top-most first (the source's own draw order), and every id the model mints -
	 * `ArtMesh<n>`, `Part<n>`, the tile ids - is sequential in that order, so the same file imports to
	 * the same ids every time and a CMO3 export reads the way the official editor's own import would.
	 * The additions are the same ones [additionsFor] appends to an open document; this assembles them
	 * into a new model with the seeded parameters and the file's canvas.
	 *
	 * @param SourceArt              art     The parsed source art.
	 * @param ArtSourceDescriptor    source  What to record about the file it came from.
	 * @param SourceArtImportOptions options The seed parameters, threshold, and margin.
	 * @return SourceArtImportResult The model, its tiles' pixels, and the import notices.
	 */
	fun fromSourceArt(
		art: SourceArt,
		source: ArtSourceDescriptor,
		options: SourceArtImportOptions = SourceArtImportOptions(),
	): SourceArtImportResult {
		val blank =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			)
		val added = additionsFor(art, source, options, blank)
		val additions = added.additions

		// Rest positions are canvas pixels with y down, the convention every import shares, and the
		// world origin is the canvas center, negated into world space like every vertex.
		val canvasWidth = art.widthPx.toFloat()
		val canvasHeight = art.heightPx.toFloat()
		// The tree is materialized flat (one leaf per parameter at the root) rather than left empty: the
		// CMO3 export places parameters in the editor's group hierarchy from the tree alone, and the
		// official editor logs a recovery for every parameter it finds outside it.  Same shape the
		// editor's own parameter-create materializes.
		val parameters = options.parameters
		val model =
			PuppetModel(
				parameters = parameters,
				parameterTree = parameters.map { parameter -> ParameterNode.Param(parameter.id) },
				parts = additions.parts,
				deformers = emptyList(),
				drawables = additions.drawables,
				rootChildren = additions.rootChildren,
				rootPartId = null,
				canvasWidth = canvasWidth,
				canvasHeight = canvasHeight,
				worldOriginX = canvasWidth / 2f,
				worldOriginY = -(canvasHeight / 2f),
				// The official editor's own fresh-import state: the rigger sees the layers as drawn, and
				// the pack that follows is what makes the document shippable.
				rendersFromSourceLayers = true,
				atlas = PuppetAtlas(pages = emptyList(), tiles = additions.tiles, storedUvsAddressPages = true),
				sources = listOf(additions.source),
			)
		return SourceArtImportResult(model.copy(renderRoot = model.deriveRenderRoot()), added.rasterByTile, added.notices)
	}

	/**
	 * The additions [art] makes to [existing]: the delta an open document appends to itself, with every
	 * id minted past the ones the document already has.
	 *
	 * Ids continue the existing sequences (`ArtMesh<n>` and `Part<n>` past their highest suffix, the
	 * source past `art-<k>`), and a tile key that collides with an existing tile is disambiguated the
	 * same way a duplicate layer key is.  The additions carry no placements: the caller packs them
	 * around the document's art.
	 *
	 * @param SourceArt              art         The parsed source art.
	 * @param ArtSourceDescriptor    source      What to record about the file it came from.
	 * @param SourceArtImportOptions options     The threshold and margin (the seed parameters are a fresh import's).
	 * @param PuppetModel            existing    The model the additions will join.
	 * @param ArtSourceId?           underSource The source the additions belong to when the file is one
	 *   the model already lists (a reload adding the layers a file gained): the tiles bind to it and the
	 *   returned record carries its id, so the caller merges rather than appends the record.  Only the
	 *   folders the added layers live in become parts; a folder the document already keeps as a part
	 *   (the part holding the most drawables of that folder, found through the source's bindings) takes
	 *   the new layers as children instead of being minted again; and every new child of the root or of
	 *   such a part is placed among the existing children where the file puts it, anchored to its
	 *   nearest sibling in the file.  Null mints a new source with every folder of the file, the shape a
	 *   fresh import and Add Artwork take.
	 * @param Set<String>?           layerKeys   The keys of the layers to mint, or null for every layer.  A
	 *   reload names the layers the file gained while handing over the WHOLE file, so the placement can
	 *   see the layers around them.
	 * @param List<ArtSourceLayer>?  inventory   The inventory of [art] when the caller already computed it
	 *   (it hashes every layer's pixels); computed here when null.
	 * @return SourceArtAdditions The delta, its tiles' pixels, and the import notices.
	 */
	fun additionsFor(
		art: SourceArt,
		source: ArtSourceDescriptor,
		options: SourceArtImportOptions,
		existing: PuppetModel,
		underSource: ArtSourceId? = null,
		layerKeys: Set<String>? = null,
		inventory: List<ArtSourceLayer>? = null,
	): SourceArtAdditions {
		val sourceId = underSource ?: ArtSourceId("art-${nextSuffix(existing.sources.map { candidate -> candidate.id.raw }, "art-", first = 0)}")
		val minter =
			IdMinter(
				nextDrawable = nextSuffix(existing.drawables.map { drawable -> drawable.id.raw }, "ArtMesh", first = 1),
				nextPart = nextSuffix(existing.parts.map { part -> part.id.raw }, "Part", first = 1),
			)
		val layersTopFirst = art.layers.sortedBy { layer -> layer.order }
		val notices = ArrayList<SourceArtImportNotice>()

		// Pass 1: one drawable and one tile per raster layer with art in it.
		val imported = ArrayList<ImportedLayer>()
		val drawables = ArrayList<Drawable>()
		val tiles = ArrayList<AtlasTile>()
		val rasterByTile = LinkedHashMap<AtlasTileId, LayerRaster>()
		val usedTileKeys = existing.atlas.tiles.mapTo(HashSet()) { tile -> tile.id.raw }
		for (layer in layersTopFirst) {
			if (layerKeys != null && layer.id.raw !in layerKeys) {
				continue
			}
			if (layer.kind != SourceLayerKind.Raster) {
				notices.add(SourceArtImportNotice.NonRasterLayer(layer.name, layer.kind))
				continue
			}
			val analysis = layer.analyzeAlpha(alphaThreshold = options.alphaThreshold)
			if (analysis == null) {
				notices.add(SourceArtImportNotice.EmptyLayer(layer.name))
				continue
			}
			// The reader's key is the tile's identity too, disambiguated by draw order when a weak source
			// key (the PSD name-and-order fallback) collides - the same rule the packer's adapter applies.
			var tileKey = "${sourceId.raw}/${layer.id.raw}"
			if (!usedTileKeys.add(tileKey)) {
				tileKey = "$tileKey#${layer.order}"
				usedTileKeys.add(tileKey)
			}
			val tileId = AtlasTileId(tileKey)
			tiles.add(
				AtlasTile(
					id = tileId,
					name = layer.name,
					width = layer.raster.width,
					height = layer.raster.height,
					source = SourceLayerRef(sourceId, layer.id.raw, stableKey = layer.idIsStable),
				),
			)
			rasterByTile[tileId] = layer.raster

			val blendMapping = mapLayerBlend(layer.blend)
			when (blendMapping) {
				is LayerBlendMapping.Exact -> Unit
				is LayerBlendMapping.Approximate -> notices.add(SourceArtImportNotice.BlendApproximated(layer.name, layer.blend, blendMapping.blendMode))
				LayerBlendMapping.Unsupported -> notices.add(SourceArtImportNotice.BlendUnsupported(layer.name, layer.blend))
			}
			if (layer.channelMask != ChannelMask.ALL) {
				notices.add(SourceArtImportNotice.ChannelMaskDropped(layer.name))
			}

			val drawableId = DrawableId("ArtMesh${minter.nextDrawable}")
			minter.nextDrawable++
			drawables.add(
				Drawable(
					id = drawableId,
					name = layer.name,
					parentDeformerId = null,
					blendMode = blendMapping.blendMode,
					maskedBy = emptyList(),
					mesh = birthQuad(layer, analysis.opaqueBounds.left, analysis.opaqueBounds.top, analysis.opaqueBounds.width, analysis.opaqueBounds.height, options.birthMeshMargin),
					geometryGrid = null,
					opacity = layer.opacity,
					isVisible = layer.visible,
					atlasTileId = tileId,
				),
			)
			imported.add(ImportedLayer(layer, drawableId))
		}

		// Pass 2: a clipping layer clips to the nearest non-clipped layer BELOW it in its own folder -
		// Photoshop's "clip to layer below", which CLIP and Krita share.  Below is a greater order.
		val drawableIndexById = drawables.withIndex().associate { (index, drawable) -> drawable.id to index }
		for ((position, entry) in imported.withIndex()) {
			if (!entry.layer.clipped) {
				continue
			}
			val base =
				imported.subList(position + 1, imported.size).firstOrNull { candidate ->
					candidate.layer.groupPath == entry.layer.groupPath && !candidate.layer.clipped
				}
			if (base == null) {
				notices.add(SourceArtImportNotice.ClipBaseMissing(entry.layer.name))
				continue
			}
			val index = drawableIndexById.getValue(entry.drawableId)
			drawables[index] = drawables[index].copy(maskedBy = listOf(base.drawableId))
		}

		// Pass 3: the org tree.  A fresh file mints every folder as a part, nested by path, and its whole
		// tree lands after the model's root children.  A file the document already lists mints only the
		// folders its added layers live in, a folder the document already keeps as a part takes the
		// layers as children instead, and every new child of the root or of such a part is placed among
		// the existing children where the file puts it.  Each folder sorts among its siblings where its
		// top-most layer sits, so the panel reads in the file's own order.
		val existingPartByPath = if (underSource == null) emptyMap() else existingPartsByFolder(existing, underSource)
		val folderByPath = LinkedHashMap<String, FolderNode>()
		if (underSource == null) {
			for (group in art.groups) {
				folderByPath[group.path] = folderNodeOf(group)
			}
		} else {
			val groupByPath = art.groups.associateBy { group -> group.path }
			for (entry in imported) {
				for (path in ancestorPathsOf(entry.layer.groupPath)) {
					val group = groupByPath[path]
					if (group != null && path !in folderByPath) {
						folderByPath[path] = folderNodeOf(group)
					}
				}
			}
		}
		for (entry in imported) {
			ensureFolders(entry.layer.groupPath, folderByPath)
		}
		// Where a new child gathers: the root's list, an anchored part's list (placed among that part's
		// existing children below), or a new folder's own node.
		val rootGathered = ArrayList<FolderChild>()
		val gatheredByAnchor = LinkedHashMap<String, MutableList<FolderChild>>()

		fun gatheredFor(parentPath: String): MutableList<FolderChild> =
			when {
				parentPath.isEmpty() -> rootGathered
				parentPath in existingPartByPath -> gatheredByAnchor.getOrPut(parentPath) { ArrayList() }
				else -> folderByPath.getValue(parentPath).children
			}
		for (folder in folderByPath.values) {
			if (folder.path in existingPartByPath) {
				// Anchored to a part the document has: its children lower into that part, and the folder
				// itself is never a child of anything new.
				continue
			}
			gatheredFor(folder.path.substringBeforeLast('/', missingDelimiterValue = "")).add(FolderChild.Folder(folder))
		}
		for (entry in imported) {
			gatheredFor(entry.layer.groupPath).add(FolderChild.Layer(entry.layer.order, entry.drawableId))
			var ancestorPath = entry.layer.groupPath
			while (ancestorPath.isNotEmpty()) {
				val ancestor = folderByPath.getValue(ancestorPath)
				ancestor.firstOrder = minOf(ancestor.firstOrder, entry.layer.order)
				ancestorPath = ancestorPath.substringBeforeLast('/', missingDelimiterValue = "")
			}
		}
		val parts = ArrayList<Part>()
		val orgRoot: List<OrgChild>
		val insertions: List<OrgInsertion>
		if (underSource == null) {
			orgRoot = orgChildrenOf(rootGathered, parts, minter, notices)
			insertions = emptyList()
		} else {
			orgRoot = emptyList()
			val placer = InsertionPlacer(art, existing, underSource, existingPartByPath, imported)
			val placed = ArrayList<OrgInsertion>()
			placer.place(container = null, containerPath = "", gathered = rootGathered, parts, minter, notices, placed)
			for ((containerPath, gathered) in gatheredByAnchor) {
				placer.place(existingPartByPath.getValue(containerPath), containerPath, gathered, parts, minter, notices, placed)
			}
			insertions = placed
		}

		return SourceArtAdditions(
			ArtworkAdditions(
				source = ArtSource(sourceId, source.name, source.path, source.format, inventory ?: inventoryOf(art), source.contentHash, source.lastModified),
				tiles = tiles,
				drawables = drawables,
				parts = parts,
				rootChildren = orgRoot,
				insertions = insertions,
			),
			rasterByTile,
			notices,
		)
	}

	/**
	 * Places a listed file's new children among a container's existing ones by the file's order.
	 *
	 * The file's direct children of a folder - its layers and its sub-folders, each with the order it
	 * sorts by (a folder's is its top-most layer's) - are the siblings; a new child goes directly after
	 * its nearest sibling above that the container can show (an existing drawable bound to that layer, the
	 * part the document keeps for that folder, or a child this delta placed before it), else directly
	 * before its nearest such sibling below, else at the end.  So a layer added at the top of the file
	 * lands first, and one added between two layers lands between their drawables.
	 *
	 * @property SourceArt   art               The whole file, for the siblings.
	 * @property PuppetModel existing          The model, for what each container already holds.
	 * @property ArtSourceId sourceId          The file's record, for the bindings that name existing drawables.
	 * @property Map         existingPartByPath The part the document keeps per folder path.
	 * @property List        imported          The layers this delta minted, with their drawables.
	 */
	private class InsertionPlacer(
		private val art: SourceArt,
		private val existing: PuppetModel,
		sourceId: ArtSourceId,
		private val existingPartByPath: Map<String, PartId>,
		imported: List<ImportedLayer>,
	) {
		/** The drawables each layer key has: the delta's new one, else every existing drawable over the bound tile. */
		private val drawablesByKey: Map<String, List<DrawableId>> = boundDrawablesByLayerKey(existing, sourceId) + imported.associate { entry -> entry.layer.id.raw to listOf(entry.drawableId) }

		/** Each folder's top-most layer order over the WHOLE file, so a sub-folder sorts among layers. */
		private val minOrderByPath: Map<String, Int> = minOrderByGroupPath(art)

		/** The parts this delta minted, by folder path, so a later child can anchor on a new folder. */
		private val newPartByPath = HashMap<String, PartId>()

		/**
		 * Places [gathered]'s children into [container], appending one insertion per child to [into].
		 *
		 * @param PartId?     container     The part, or null for the root.
		 * @param String      containerPath The folder path the container stands for ("" at the root).
		 * @param List        gathered      The new children, in any order.
		 * @param MutableList parts         Every part minted so far, appended to for a new folder.
		 * @param IdMinter    minter        The id sequence a new folder's part takes.
		 * @param MutableList notices       Appended with a new folder's composite notes.
		 * @param MutableList into          The insertions, appended in the file's order.
		 */
		fun place(
			container: PartId?,
			containerPath: String,
			gathered: List<FolderChild>,
			parts: MutableList<Part>,
			minter: IdMinter,
			notices: MutableList<SourceArtImportNotice>,
			into: MutableList<OrgInsertion>,
		) {
			val existingChildren = if (container == null) existing.rootChildren else existing.parts.first { part -> part.id == container }.children
			val present = HashSet<OrgChild>(existingChildren)
			val siblings = fileSiblingsOf(containerPath)
			for (child in gathered.sortedBy { candidate -> candidate.order }) {
				val orgChild =
					when (child) {
						is FolderChild.Layer -> OrgChild.Drawable(child.drawableId)
						is FolderChild.Folder -> {
							val minted = orgChildrenOf(listOf(child), parts, minter, notices).single()
							newPartByPath[child.node.path] = (minted as OrgChild.Part).id
							minted
						}
					}
				into.add(OrgInsertion(container, orgChild, slotFor(child.order, siblings, present, orgChild)))
				present.add(orgChild)
			}
		}

		/**
		 * The file's direct children of [containerPath], sorted by the order they sit at.
		 *
		 * @param String containerPath The folder ("" at the root).
		 * @return List<FileSibling> The layers and sub-folders, top-most first.
		 */
		private fun fileSiblingsOf(containerPath: String): List<FileSibling> {
			val siblings = ArrayList<FileSibling>()
			for (layer in art.layers) {
				if (layer.groupPath == containerPath) {
					siblings.add(FileSibling.Layer(layer.order, layer.id.raw))
				}
			}
			val folderPaths = LinkedHashSet<String>(minOrderByPath.keys)
			for (group in art.groups) {
				folderPaths.add(group.path)
			}
			for (path in folderPaths) {
				if (path.substringBeforeLast('/', missingDelimiterValue = "") == containerPath && path != containerPath) {
					siblings.add(FileSibling.Folder(minOrderByPath[path] ?: Int.MAX_VALUE, path))
				}
			}
			return siblings.sortedBy { sibling -> sibling.order }
		}

		/**
		 * Where a child at [order] goes among [siblings]: after the nearest one above the container can
		 * show, else before the nearest one below it can, else at the end.
		 *
		 * @param Int               order    The child's order in the file.
		 * @param List<FileSibling> siblings The container's children as the file orders them.
		 * @param Set<OrgChild>     present  What the container holds now, this delta's earlier placements included.
		 * @param OrgChild          self     The child being placed, never its own anchor.
		 * @return OrgSlot The slot.
		 */
		private fun slotFor(order: Int, siblings: List<FileSibling>, present: Set<OrgChild>, self: OrgChild): OrgSlot {
			for (sibling in siblings.filter { candidate -> candidate.order < order }.asReversed()) {
				val anchor = shownChildOf(sibling, present, self)
				if (anchor != null) {
					return OrgSlot.After(anchor)
				}
			}
			for (sibling in siblings.filter { candidate -> candidate.order > order }) {
				val anchor = shownChildOf(sibling, present, self)
				if (anchor != null) {
					return OrgSlot.Before(anchor)
				}
			}
			return OrgSlot.End
		}

		/**
		 * The child the container shows for [sibling], or null when it shows none: a skipped layer, a
		 * layer whose drawable the rigger moved elsewhere, a folder with no part.
		 *
		 * @param FileSibling   sibling The layer or folder.
		 * @param Set<OrgChild> present What the container holds.
		 * @param OrgChild      self    The child being placed.
		 * @return OrgChild? The shown child, or null.
		 */
		private fun shownChildOf(sibling: FileSibling, present: Set<OrgChild>, self: OrgChild): OrgChild? {
			val candidates =
				when (sibling) {
					is FileSibling.Layer -> drawablesByKey[sibling.key].orEmpty().map { drawableId -> OrgChild.Drawable(drawableId) }
					is FileSibling.Folder -> listOfNotNull((existingPartByPath[sibling.path] ?: newPartByPath[sibling.path])?.let { partId -> OrgChild.Part(partId) })
				}
			return candidates.firstOrNull { candidate -> candidate != self && candidate in present }
		}
	}

	/**
	 * Every existing drawable each layer of [sourceId]'s file has, by the layer's key: the tiles bound
	 * to the file and the drawables over them.
	 *
	 * @param PuppetModel existing The model.
	 * @param ArtSourceId sourceId The file.
	 * @return Map The drawable ids per bound layer key.
	 */
	private fun boundDrawablesByLayerKey(existing: PuppetModel, sourceId: ArtSourceId): Map<String, List<DrawableId>> {
		val drawablesByTile = existing.drawables.groupBy { drawable -> drawable.atlasTileId }
		val result = HashMap<String, List<DrawableId>>()
		for (tile in existing.atlas.tiles) {
			val ref = tile.source ?: continue
			if (ref.sourceId != sourceId) {
				continue
			}
			val drawables = drawablesByTile[tile.id].orEmpty().map { drawable -> drawable.id }
			if (drawables.isNotEmpty()) {
				result[ref.layerKey] = result[ref.layerKey].orEmpty() + drawables
			}
		}
		return result
	}

	/**
	 * The top-most layer order under every folder path of [art], the folder's own order among its siblings.
	 *
	 * @param SourceArt art The file.
	 * @return Map The minimum order per folder path; a folder with no layer under it is absent.
	 */
	private fun minOrderByGroupPath(art: SourceArt): Map<String, Int> {
		val minOrder = HashMap<String, Int>()
		for (layer in art.layers) {
			for (path in ancestorPathsOf(layer.groupPath)) {
				minOrder[path] = minOf(minOrder[path] ?: Int.MAX_VALUE, layer.order)
			}
		}
		return minOrder
	}

	/**
	 * Which of [existing]'s parts stands for each folder of [sourceId]'s file: for every drawable bound
	 * to the file, its layer's folder (from the recorded inventory) and the part holding the drawable
	 * directly; a folder maps to the part holding the most of its drawables, and a folder with no
	 * drawable of its own (one holding only sub-folders) to the part that holds a mapped sub-folder's
	 * part.  A CMO3-origin document has no folder binding on its parts, so this is what says "Front
	 * hair" already exists.
	 *
	 * @param PuppetModel existing The model the additions will join.
	 * @param ArtSourceId sourceId The file whose folders are being placed.
	 * @return Map<String, PartId> The part per folder path; a folder reached by neither rule is absent.
	 */
	private fun existingPartsByFolder(existing: PuppetModel, sourceId: ArtSourceId): Map<String, PartId> {
		val rowByKey = existing.sources.firstOrNull { source -> source.id == sourceId }?.layers?.associateBy { row -> row.key }.orEmpty()
		val folderByDrawable = HashMap<DrawableId, String>()
		for (drawable in existing.drawables) {
			val tile = drawable.atlasTileId?.let { tileId -> existing.atlas.tileById[tileId] } ?: continue
			val ref = tile.source ?: continue
			if (ref.sourceId != sourceId) {
				continue
			}
			val folder = rowByKey[ref.layerKey]?.groupPath ?: continue
			if (folder.isNotEmpty()) {
				folderByDrawable[drawable.id] = folder
			}
		}
		val countByFolderAndPart = LinkedHashMap<String, LinkedHashMap<PartId, Int>>()
		val parentByPart = HashMap<PartId, PartId>()
		for (part in existing.parts) {
			for (child in part.children) {
				when (child) {
					is OrgChild.Part -> parentByPart[child.id] = part.id
					is OrgChild.Drawable -> {
						val folder = folderByDrawable[child.id] ?: continue
						val counts = countByFolderAndPart.getOrPut(folder) { LinkedHashMap() }
						counts[part.id] = (counts[part.id] ?: 0) + 1
					}
				}
			}
		}
		val partByFolder = LinkedHashMap<String, PartId>()
		for ((folder, counts) in countByFolderAndPart) {
			partByFolder[folder] = counts.maxBy { (_, count) -> count }.key
		}
		// A folder no drawable sits in directly is reached through a mapped sub-folder: its part is the
		// one holding that sub-folder's part.  A direct mapping always wins over an inferred one.
		for ((folder, partId) in countByFolderAndPart.keys.associateWith { folder -> partByFolder.getValue(folder) }) {
			var ancestorFolder = folder.substringBeforeLast('/', missingDelimiterValue = "")
			var ancestorPart = parentByPart[partId]
			while (ancestorFolder.isNotEmpty() && ancestorPart != null && ancestorFolder !in partByFolder) {
				partByFolder[ancestorFolder] = ancestorPart
				ancestorFolder = ancestorFolder.substringBeforeLast('/', missingDelimiterValue = "")
				ancestorPart = parentByPart[ancestorPart]
			}
		}
		return partByFolder
	}

	/**
	 * Every folder path along [groupPath], outermost first ("Head", "Head/Hair", ...); empty at the root.
	 *
	 * @param String groupPath The slash-joined path.
	 * @return List<String> The ancestor paths, the path itself last.
	 */
	private fun ancestorPathsOf(groupPath: String): List<String> {
		if (groupPath.isEmpty()) {
			return emptyList()
		}
		val paths = ArrayList<String>()
		var path = ""
		for (segment in groupPath.split('/')) {
			path = if (path.isEmpty()) segment else "$path/$segment"
			paths.add(path)
		}
		return paths
	}

	/**
	 * The pixel-free layer inventory of [art], top-most first: EVERY layer, skipped ones included, so a
	 * re-import can tell a layer that was there and unusable from one that is new.  The record a fresh
	 * import stores and a reload replaces.  Each raster layer carries the content hash of its pixels,
	 * the one thing about the pixels the inventory keeps, so a later read can recognize a renamed layer
	 * whose art did not change.
	 *
	 * @param SourceArt art The parsed source art.
	 * @return List<ArtSourceLayer> The inventory rows, in the file's own draw order.
	 */
	fun inventoryOf(art: SourceArt): List<ArtSourceLayer> =
		art.layers.sortedBy { layer -> layer.order }.map { layer ->
			ArtSourceLayer(
				key = layer.id.raw,
				name = layer.name,
				groupPath = layer.groupPath,
				left = layer.bounds.left,
				top = layer.bounds.top,
				width = layer.bounds.width,
				height = layer.bounds.height,
				visible = layer.visible,
				contentHash = if (layer.kind == SourceLayerKind.Raster) contentHashOf(layer.raster.rgba) else null,
				empty = layer.kind == SourceLayerKind.Raster && layer.raster.isFullyTransparent(),
			)
		}

	/**
	 * The suffix the next id in a `<prefix><n>` sequence takes: one past the highest suffix among
	 * [ids] that carry the prefix and a numeric tail, or [first] when none does.
	 *
	 * @param Iterable<String> ids    The ids already in use.
	 * @param String           prefix The sequence's prefix.
	 * @param Int              first  The suffix a fresh sequence starts at.
	 * @return Int The next suffix.
	 */
	private fun nextSuffix(ids: Iterable<String>, prefix: String, first: Int): Int {
		var highest: Int? = null
		for (id in ids) {
			if (!id.startsWith(prefix)) {
				continue
			}
			val suffix = id.substring(prefix.length).toIntOrNull() ?: continue
			if (highest == null || suffix > highest) {
				highest = suffix
			}
		}
		return if (highest == null) first else highest + 1
	}

	/**
	 * The quad a layer is born with, over its opaque bounds as [analyzeAlpha] found them - the mesh an
	 * import gives every drawable, and the one a reload gives back to a drawable whose quad was never
	 * edited.  Null when the layer has no opaque pixel under [alphaThreshold], which is the layer the
	 * import skips.
	 *
	 * @param SourceLayer layer          The layer.
	 * @param Int         alphaThreshold The minimum alpha byte (1..255) for a pixel to count as art.
	 * @param Int         margin         How far the quad extends past the opaque bounds on every side.
	 * @return DrawableMesh? The two-triangle quad, or null for a layer with no art.
	 */
	fun birthMeshFor(layer: SourceLayer, alphaThreshold: Int, margin: Int): DrawableMesh? {
		val analysis = layer.analyzeAlpha(alphaThreshold = alphaThreshold) ?: return null
		val bounds = analysis.opaqueBounds
		return birthQuad(layer, bounds.left, bounds.top, bounds.width, bounds.height, margin)
	}

	/**
	 * The quad a layer is born with: the layer's opaque bounds grown by [margin] on every side, as
	 * canvas-pixel positions and art-frame coordinates that are two views of the same rectangle.
	 *
	 * The quad may overhang the layer's raster (the margin is not clamped): the mesh's reach is what
	 * the pack reserves around the tile, and a coordinate past the raster's edge samples the gutter
	 * the pack keeps clear there.
	 *
	 * @param SourceLayer layer      The layer.
	 * @param Int         trimLeft   The opaque bounds' left edge, raster-local.
	 * @param Int         trimTop    The opaque bounds' top edge, raster-local.
	 * @param Int         trimWidth  The opaque bounds' width.
	 * @param Int         trimHeight The opaque bounds' height.
	 * @param Int         margin     How far the quad extends past the bounds.
	 * @return DrawableMesh The two-triangle quad.
	 */
	private fun birthQuad(layer: SourceLayer, trimLeft: Int, trimTop: Int, trimWidth: Int, trimHeight: Int, margin: Int): DrawableMesh {
		val left = (trimLeft - margin).toFloat()
		val top = (trimTop - margin).toFloat()
		val right = (trimLeft + trimWidth + margin).toFloat()
		val bottom = (trimTop + trimHeight + margin).toFloat()
		val canvasLeft = layer.bounds.left.toFloat()
		val canvasTop = layer.bounds.top.toFloat()
		val tileWidth = layer.raster.width.toFloat()
		val tileHeight = layer.raster.height.toFloat()
		return DrawableMesh(
			positions =
				floatArrayOf(
					canvasLeft + left,
					canvasTop + top,
					canvasLeft + right,
					canvasTop + top,
					canvasLeft + right,
					canvasTop + bottom,
					canvasLeft + left,
					canvasTop + bottom,
				),
			uvs =
				floatArrayOf(
					left / tileWidth,
					top / tileHeight,
					right / tileWidth,
					top / tileHeight,
					right / tileWidth,
					bottom / tileHeight,
					left / tileWidth,
					bottom / tileHeight,
				),
			indices = intArrayOf(0, 1, 2, 0, 2, 3),
		)
	}

	/**
	 * A folder node carrying a source group's own attributes.
	 *
	 * @param SourceGroup group The parsed folder.
	 * @return FolderNode The node, with no children yet.
	 */
	private fun folderNodeOf(group: SourceGroup): FolderNode =
		FolderNode(
			path = group.path,
			name = group.name,
			visible = group.visible,
			opacity = group.opacity,
			clipped = group.clipped,
			blend = group.blend,
			passThrough = group.passThrough,
		)

	/**
	 * Makes sure every folder along [groupPath] has a node, synthesizing the ones the reader did not
	 * describe (a reader that flattens without folder metadata still names each layer's path).
	 *
	 * @param String groupPath    The slash-joined path to ensure.
	 * @param MutableMap folderByPath The nodes so far, added to.
	 */
	private fun ensureFolders(groupPath: String, folderByPath: MutableMap<String, FolderNode>) {
		if (groupPath.isEmpty()) {
			return
		}
		var path = ""
		for (segment in groupPath.split('/')) {
			path = if (path.isEmpty()) segment else "$path/$segment"
			if (path !in folderByPath) {
				folderByPath[path] = FolderNode(path, segment, visible = true, opacity = 1f, clipped = false, blend = LayerBlend.Normal, passThrough = true)
			}
		}
	}

	/**
	 * Lowers one level of gathered children onto org children, minting a part per folder in pre-order
	 * so part ids follow the panel order.
	 *
	 * A folder composites like the art when it must: opacity under 1 or a non-Normal blend makes the
	 * part Isolated with that composite, since pass-through would draw its layers as if the folder were
	 * not there.  A folder that isolates for no visible reason stays pass-through, the model's default.
	 *
	 * @param List    children The gathered children of one folder (or the root).
	 * @param MutableList parts Every part minted so far, appended to.
	 * @param IdMinter minter  The id sequences, advanced per part.
	 * @param MutableList notices The import notices, appended to.
	 * @return List<OrgChild> The org children, top-most first.
	 */
	private fun orgChildrenOf(
		children: List<FolderChild>,
		parts: MutableList<Part>,
		minter: IdMinter,
		notices: MutableList<SourceArtImportNotice>,
	): List<OrgChild> =
		children.sortedBy { child -> child.order }.map { child ->
			when (child) {
				is FolderChild.Layer -> OrgChild.Drawable(child.drawableId)
				is FolderChild.Folder -> {
					val node = child.node
					val partId = PartId("Part${minter.nextPart}")
					minter.nextPart++
					// Reserve the slot before descending so a parent's id precedes its children's.
					val slot = parts.size
					parts.add(Part(partId, node.name, emptyList()))
					val nestedChildren = orgChildrenOf(node.children, parts, minter, notices)
					val blendMapping = mapLayerBlend(node.blend)
					if (blendMapping == LayerBlendMapping.Unsupported) {
						notices.add(SourceArtImportNotice.FolderBlendUnsupported(node.path, node.blend))
					}
					if (node.clipped) {
						notices.add(SourceArtImportNotice.FolderClipDropped(node.path))
					}
					val composites = node.opacity < 1f || blendMapping.blendMode != BlendMode.Normal
					parts[slot] =
						Part(
							id = partId,
							name = node.name,
							children = nestedChildren,
							isVisible = node.visible,
							groupMode = if (composites) PartGroupMode.Isolated else PartGroupMode.PassThrough,
							composite = if (composites) PartComposite(blendMode = blendMapping.blendMode, opacity = node.opacity) else PartComposite(),
						)
					OrgChild.Part(partId)
				}
			}
		}
}