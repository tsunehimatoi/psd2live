package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.roundToInt

/*
 * The real-layer half of a fresh image chain: what an artwork-origin document (a PSD, CLIP, or KRA
 * the bridge opened) writes in place of the crop stand-in.  One CLayeredImage per artwork file with
 * the file's folders as CLayerGroups and one CLayer per tile holding the document's own raster for
 * it, one CModelImage per tile at the layer's canvas origin, an entry on the tile's page for every
 * placed tile, and a binding for every drawable over it - the shape the official editor writes when
 * it imports a PSD, so the file reopens in either editor with its real art and, through the Photoshop
 * layer ids, re-imports against the PSD by key.
 */

/**
 * The real-layer web writer.  [inputsOf] routes a puppet's tiles into per-file inputs, and [write]
 * mints one file's web into the texture manager the caller is populating.
 */
internal object Cmo3SourceLayerWeb {
	/**
	 * One tile with real art, as the conversion routes it here.
	 *
	 * @property String          tileId      The tile's id.
	 * @property String          name        The layer's name, which is the tile's.
	 * @property String          layerKey    The binding key; a "lyid:<n>" key writes Photoshop's layer id.
	 * @property String          groupPath   The folder path in the file, "" at the root.
	 * @property Boolean         visible     The layer's visibility as last read.
	 * @property Int             canvasLeft  The art frame's canvas x - the inventory row's origin.
	 * @property Int             canvasTop   The art frame's canvas y.
	 * @property RasterImage     raster      The tile's pixels, straight alpha.
	 * @property AtlasPlacement? placement   Where the tile sits on its page, or null when unpacked.
	 * @property List<String>    drawableIds The drawables sampling the tile, in document order.
	 * @property Map             artUvsByDrawableId Each drawable's texture coordinates in the tile's
	 *   own frame, for the patch its icon shows; a drawable absent here shows the whole layer.
	 */
	internal class SourceLayerInput(
		val tileId: String,
		val name: String,
		val layerKey: String,
		val groupPath: String,
		val visible: Boolean,
		val canvasLeft: Int,
		val canvasTop: Int,
		val raster: RasterImage,
		val placement: AtlasPlacement?,
		val drawableIds: List<String>,
		val artUvsByDrawableId: Map<String, FloatArray> = emptyMap(),
	)

	/**
	 * One artwork file with those of its tiles that have real art, in the file's layer order.
	 *
	 * @property String  name         The file's display name.
	 * @property String? path         The file's recorded path, or null when the record has none.
	 * @property Long?   lastModified The file's modification time as last read, or null.
	 * @property Int     width        The frame the layers' canvas coordinates live in.
	 * @property Int     height       Its height.
	 * @property List    layers       The tiles to write as layers.
	 */
	internal class SourceImageInput(
		val name: String,
		val path: String?,
		val lastModified: Long?,
		val width: Int,
		val height: Int,
		val layers: List<SourceLayerInput>,
	)

	/**
	 * What one file's web produced.
	 *
	 * @property LayeredImageWrapper wrapper             The layered image, for the texture manager's raw-image list.
	 * @property CModelImageGroup    group               The file's model-image group.
	 * @property Map                 bindingByDrawableId The texture web of every drawable over a placed tile.
	 */
	internal class Written(
		val wrapper: LayeredImageWrapper,
		val group: CModelImageGroup,
		val bindingByDrawableId: Map<String, Cmo3DrawableTextureBinding>,
	)

	/**
	 * Routes [puppet]'s tiles into per-file inputs: every tile bound to a listed file whose binding
	 * names an inventory row (present or lost - its tile still holds the art) and for which
	 * [tileRasters] has pixels, in the file's inventory order.  A tile nothing samples is still a
	 * layer of its file; a tile with no row or no raster is left to the crop path.
	 *
	 * The layered image's frame is the document canvas: an import sets the canvas from the art, and
	 * the inventory's canvas coordinates live in that frame.
	 *
	 * @param PuppetModel puppet      The model being converted.
	 * @param Function    tileRasters The document's pixels for a tile, or null.
	 * @return List<SourceImageInput> One input per file with at least one real tile, in the model's source order.
	 */
	internal fun inputsOf(puppet: PuppetModel, tileRasters: (AtlasTileId) -> RasterImage?): List<SourceImageInput> {
		val drawableIdsByTile = HashMap<AtlasTileId, MutableList<String>>()
		val artUvsByTile = HashMap<AtlasTileId, MutableMap<String, FloatArray>>()
		for (drawable in puppet.drawables) {
			val tileId = drawable.atlasTileId ?: continue
			drawableIdsByTile.getOrPut(tileId) { ArrayList() }.add(drawable.id.raw)
			Cmo3Icons.artUvsOf(puppet, drawable)?.let { artUvs -> artUvsByTile.getOrPut(tileId) { HashMap() }[drawable.id.raw] = artUvs }
		}
		val canvasWidth = puppet.canvasWidth.roundToInt()
		val canvasHeight = puppet.canvasHeight.roundToInt()
		val images = ArrayList<SourceImageInput>()
		for (source in puppet.sources) {
			val rowIndexByKey = HashMap<String, Int>()
			for ((rowIndex, row) in source.layers.withIndex()) {
				rowIndexByKey.putIfAbsent(row.key, rowIndex)
			}
			val ordered = ArrayList<Pair<Int, SourceLayerInput>>()
			for (tile in puppet.atlas.tiles) {
				val ref = tile.source ?: continue
				if (ref.sourceId != source.id) {
					continue
				}
				val rowIndex = rowIndexByKey[ref.layerKey] ?: continue
				val row = source.layers[rowIndex]
				val raster = tileRasters(tile.id) ?: continue
				ordered.add(
					rowIndex to
						SourceLayerInput(
							tileId = tile.id.raw,
							name = tile.name,
							layerKey = ref.layerKey,
							groupPath = row.groupPath,
							visible = row.visible,
							canvasLeft = row.left,
							canvasTop = row.top,
							raster = raster,
							placement = tile.placement,
							drawableIds = drawableIdsByTile[tile.id].orEmpty(),
							artUvsByDrawableId = artUvsByTile[tile.id].orEmpty(),
						),
				)
			}
			if (ordered.isEmpty()) {
				continue
			}
			// A stable sort: two tiles bound to one row (a double binding) keep their atlas order.
			images.add(SourceImageInput(source.name, source.path, source.lastModified, canvasWidth, canvasHeight, ordered.sortedBy { (rowIndex, _) -> rowIndex }.map { (_, layer) -> layer }))
		}
		return images
	}

	/**
	 * Writes one file's web: the layered image with its folder tree and layers, the model-image group
	 * with one image per layer, an entry on each placed tile's atlas, and the bindings.
	 *
	 * @param SourceImageInput    image      The file and its tiles.
	 * @param List<CTextureAtlas> atlases    The pages' atlas elements, in model page order.
	 * @param List<GTexture2D>    textures   The pages' shared textures, index-parallel to [atlases].
	 * @param Cmo3FreshChainNames names      The document's shared definitions and naming counters.
	 * @param MutableList         pngEntries The PNG entry collector.
	 * @param Long                nowMillis  The import timestamp the wrapper and env values record, standing in
	 *   for a time the record lacks.
	 * @return Written The wrapper, the group, and the bindings.
	 */
	internal fun write(
		image: SourceImageInput,
		atlases: List<CTextureAtlas>,
		textures: List<GTexture2D>,
		names: Cmo3ImageChainBuilder.Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		nowMillis: Long,
	): Written {
		val minted = mintLayeredImage(image.name, image.path, image.lastModified, image.width, image.height, names, nowMillis)
		val layeredImage = minted.image
		val rootChildren = minted.rootChildren
		val rootGroup = minted.rootGroup
		val layerEntryList = minted.layerEntryList
		val group = mintModelImageGroup(image.name, layeredImage)
		val groupModelImages = checkNotNull(mutableGraphListOf(group._modelImages)) { "mintModelImageGroup builds a list" }
		// Folders are minted on first encounter along each layer's path, so only the folders that
		// hold a written layer exist, nested the way the file nests them.
		val childrenByPath = HashMap<String, CArrayList<Any?>>()
		val groupByPath = HashMap<String, CLayerGroup>()
		childrenByPath[""] = rootChildren
		groupByPath[""] = rootGroup

		fun folderAt(path: String): CLayerGroup {
			groupByPath[path]?.let { existing -> return existing }
			val parentPath = path.substringBeforeLast('/', "")
			folderAt(parentPath)
			val children = CArrayList<Any?>()
			val folder = layerGroup(path.substringAfterLast('/'), layeredImage, children, names)
			childrenByPath.getValue(parentPath).add(folder)
			layerEntryList.add(folder)
			childrenByPath[path] = children
			groupByPath[path] = folder
			return folder
		}
		val bindings = HashMap<String, Cmo3DrawableTextureBinding>()
		for (layerInput in image.layers) {
			val raster = layerInput.raster
			val pngBytes = PngCodec.write(raster)
			val path = names.nextImageFileBufPath()
			pngEntries.add(Cmo3FreshFile.PngEntry(path, pngBytes))
			val resource = layerResource(path, raster, pngBytes.size)
			val folder = folderAt(layerInput.groupPath)
			val layer =
				layerOver(
					name = layerInput.name,
					layerKey = layerInput.layerKey,
					visible = layerInput.visible,
					canvasLeft = layerInput.canvasLeft,
					canvasTop = layerInput.canvasTop,
					resource = resource,
					raster = raster,
					layeredImage = layeredImage,
					folder = folder,
					names = names,
					pngEntries = pngEntries,
				)
			childrenByPath.getValue(layerInput.groupPath).add(layer)
			layerEntryList.add(layer)
			val modelImage = Cmo3ImageChainBuilder.modelImageOver(layerInput.name, layeredImage, layer, resource, raster, layerPlacement(layerInput.canvasLeft, layerInput.canvasTop), group, names, pngEntries, nowMillis)
			groupModelImages.add(modelImage)
			val tilePlacement = layerInput.placement ?: continue
			val atlas = atlases.getOrNull(tilePlacement.pageIndex) ?: continue
			val texture = textures[tilePlacement.pageIndex]
			// The entry pair: the packing as the placement records it, and the atlas-to-canvas half
			// composed so the two compose back to the layer's translation.  Every drawable over the
			// tile carries the entry's transform as its region input, the official relation - a
			// duplicate moved elsewhere keeps sampling the same tile, as a Cubism duplicate does.
			val canvasAffine = floatArrayOf(1f, 0f, layerInput.canvasLeft.toFloat(), 0f, 1f, layerInput.canvasTop.toFloat())
			val entryHalf = atlasLocalToCanvasFor(canvasAffine, tilePlacement) ?: continue
			checkNotNull(mutableGraphListOf(atlas.modelImages)) { "pageAtlas builds a list" }.add(
				Cmo3ImageChainBuilder.packedEntry(
					atlas = atlas,
					modelImageGuid = modelImage.guid,
					atlasLocalToCanvas = affineOf(entryHalf),
					packing = Cmo3ImageChainBuilder.writePacking(GTransform2(), tilePlacement),
				),
			)
			for (drawableId in layerInput.drawableIds) {
				// The drawable's icons show the patch its mesh covers on the layer, one icon per
				// drawable like the editor's files.
				val patch = Cmo3Icons.patchOf(raster, layerInput.artUvsByDrawableId[drawableId])
				bindings[drawableId] =
					Cmo3DrawableTextureBinding(
						texture,
						atlas.guid as Guid,
						modelImage.guid as Guid,
						affineOf(entryHalf),
						icon32 = Cmo3Icons.iconOf(patch, 32, names.nextIconPath(), pngEntries),
						icon16 = Cmo3Icons.iconOf(patch, 16, names.nextIconPath(), pngEntries),
					)
			}
		}
		return Written(minted.wrapper, group, bindings)
	}

	/**
	 * A freshly minted layered image with the lists a layer is added through.
	 *
	 * @property LayeredImageWrapper wrapper        The wrapper, for the texture manager's raw-image list.
	 * @property CLayeredImage       image          The layered image.
	 * @property CLayerGroup         rootGroup      Its root folder.
	 * @property CArrayList          rootChildren   The root folder's child list.
	 * @property CArrayList          layerEntryList The flat entry list every group and layer joins.
	 */
	internal class MintedLayeredImage(
		val wrapper: LayeredImageWrapper,
		val image: CLayeredImage,
		val rootGroup: CLayerGroup,
		val rootChildren: CArrayList<Any?>,
		val layerEntryList: CArrayList<Any?>,
	)

	/**
	 * Mints one artwork file's layered image: the document frame, the file reference, an empty root
	 * folder, and the flat entry list, wrapped for the texture manager's raw-image list.  Shared by the
	 * fresh web and the retained-graph mint, so a file added to a retained document takes the same
	 * shape as one in a fresh export.
	 *
	 * @param String              name         The file's display name.
	 * @param String?             path         The file's recorded path, or null when the record has none.
	 * @param Long?               lastModified The file's modification time as last read, or null.
	 * @param Int                 width        The frame the layers' canvas coordinates live in.
	 * @param Int                 height       Its height.
	 * @param Cmo3FreshChainNames names        The document's shared blend and options.
	 * @param Long                nowMillis    The import timestamp, standing in for a time the record lacks.
	 * @return MintedLayeredImage The image and its lists.
	 */
	internal fun mintLayeredImage(
		name: String,
		path: String?,
		lastModified: Long?,
		width: Int,
		height: Int,
		names: Cmo3ImageChainBuilder.Cmo3FreshChainNames,
		nowMillis: Long,
	): MintedLayeredImage {
		val layeredImage = CLayeredImage()
		val rootChildren = CArrayList<Any?>()
		val rootGroup = layerGroup("root", layeredImage, rootChildren, names)
		val layerEntryList = CArrayList<Any?>(mutableListOf<Any?>(rootGroup))
		layeredImage.apply {
			this.name = name
			memo = ""
			// CMO3: CLayeredImage fields width / height - the source document's own frame; the
			// inventory's canvas coordinates live in the document canvas, so that is the frame here.
			this.width = width
			this.height = height
			// CMO3: CLayeredImage field psdFile - the external-reference <file> shape whose text is the
			// source's path on the importing machine; the name stands in when the record has none.
			psdFile = FileRef().apply { textPath = path ?: name }
			description = ""
			guid = Cmo3SkeletonBuilder.freshGuid("CLayeredImageGuid")
			// CMO3: CLayeredImage field psdFileLastModified - the source's modification time as last read.
			psdFileLastModified = lastModified ?: nowMillis
			_rootLayer = rootGroup
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
				lastModifiedTimeMSec = lastModified ?: nowMillis
			}
		return MintedLayeredImage(wrapper, layeredImage, rootGroup, rootChildren, layerEntryList)
	}

	/**
	 * A file's model-image group, linked to its layered image and empty of images.
	 *
	 * @param String        name         The file's display name, which the group takes.
	 * @param CLayeredImage layeredImage The file's layered image.
	 * @return CModelImageGroup The group.
	 */
	internal fun mintModelImageGroup(name: String, layeredImage: CLayeredImage): CModelImageGroup =
		CModelImageGroup().apply {
			// CMO3: CModelImageGroup fields groupName / _linkedRawImageGuids / _modelImages.
			memo = ""
			groupName = name
			_linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
			_modelImages = CArrayList<Any?>()
		}

	/**
	 * A layer's own image resource: the raster's dimensions, type, and archive link.
	 *
	 * @param String      path    The archive path the layer PNG is stored under.
	 * @param RasterImage raster  The layer's pixels.
	 * @param Int         pngSize The encoded PNG's byte size.
	 * @return CImageResource The resource.
	 */
	internal fun layerResource(path: String, raster: RasterImage, pngSize: Int): CImageResource =
		CImageResource().apply {
			// CMO3: CImageResource - the layer's own pixels, the document's raster for the tile.
			width = raster.width
			height = raster.height
			type = "INT_ARGB"
			imageFileBuf = FileRef().apply { archivePath = path }
			imageFileBuf_size = pngSize
		}

	/**
	 * A tile's layer on its file's document: the name, visibility, resource, rect, identifier, and
	 * icons the official editor writes for a PSD layer.  Shared by the fresh web and the
	 * retained-graph mint, so a layer minted into a retained file is field-for-field the fresh shape.
	 *
	 * @param String              name         The layer's name.
	 * @param String              layerKey     The binding key; a "lyid:<n>" key writes Photoshop's layer id.
	 * @param Boolean             visible      The layer's visibility.
	 * @param Int                 canvasLeft   The art frame's canvas x.
	 * @param Int                 canvasTop    The art frame's canvas y.
	 * @param CImageResource      resource     The layer's own resource.
	 * @param RasterImage         raster       The resource's pixels, which the icons show fitted.
	 * @param CLayeredImage       layeredImage The owning layered image.
	 * @param CLayerGroup         folder       The folder the layer sits in.
	 * @param Cmo3FreshChainNames names        The document's shared blend, options, and icon paths.
	 * @param MutableList         pngEntries   The PNG entry collector, for the icons.
	 * @return CLayer The layer, not yet added to any list.
	 */
	internal fun layerOver(
		name: String,
		layerKey: String,
		visible: Boolean,
		canvasLeft: Int,
		canvasTop: Int,
		resource: CImageResource,
		raster: RasterImage,
		layeredImage: CLayeredImage,
		folder: CLayerGroup,
		names: Cmo3ImageChainBuilder.Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
	): CLayer =
		CLayer().apply {
			// CMO3: CLayer - the tile's layer on the file's document.
			this.name = name
			memo = ""
			isVisible = visible
			blend = names.sharedBlend
			guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
			opacity255 = 255
			_optionOfIOption = names.sharedOptions
			_layeredImage = layeredImage
			imageResource = resource
			// CMO3: CLayer field boundsOnImageDoc - the layer's rect on the document: the art frame's
			// canvas origin, the raster's size (the two invariants every corpus layer obeys: origin =
			// the model image's canvas placement, size = the resource dims).
			boundsOnImageDoc = layerBounds(canvasLeft, canvasTop, resource.width, resource.height)
			layerIdentifier = identifierOf(name, layerKey)
			// CMO3: CLayer fields icon16 / icon64 - thumbnails on every corpus layer, the whole
			// layer fitted into each square.
			icon16 = Cmo3Icons.iconOf(raster, 16, names.nextIconPath(), pngEntries)
			icon64 = Cmo3Icons.iconOf(raster, 64, names.nextIconPath(), pngEntries)
			layerInfo = LinkedHashMap<String, Any?>()
			this.group = folder
		}

	/**
	 * A layer's rect on its document.
	 *
	 * @param Int left   The origin x.
	 * @param Int top    The origin y.
	 * @param Int width  The width.
	 * @param Int height The height.
	 * @return CRect The rect.
	 */
	internal fun layerBounds(left: Int, top: Int, width: Int, height: Int): CRect =
		CRect().apply {
			x = left
			y = top
			this.width = width
			this.height = height
		}

	/**
	 * A model image's canvas placement for a layer: upright canvas-space art, a pure translation to
	 * the layer's origin - the invariant every corpus model image carries; the packer's work rides the
	 * entry.
	 *
	 * @param Int canvasLeft The layer's canvas x.
	 * @param Int canvasTop  The layer's canvas y.
	 * @return CAffine The placement, an independent instance.
	 */
	internal fun layerPlacement(canvasLeft: Int, canvasTop: Int): CAffine =
		CAffine().apply {
			m02 = canvasLeft.toFloat()
			m12 = canvasTop.toFloat()
		}

	/**
	 * A folder of the layered image: a group element over [children].
	 *
	 * @param String              name         The folder's name (one path segment, "root" for the root).
	 * @param CLayeredImage       layeredImage The owning layered image.
	 * @param CArrayList          children     The list the folder's layers and sub-folders go into.
	 * @param Cmo3FreshChainNames names        The document's shared blend and options.
	 * @return CLayerGroup The group.
	 */
	internal fun layerGroup(name: String, layeredImage: CLayeredImage, children: CArrayList<Any?>, names: Cmo3ImageChainBuilder.Cmo3FreshChainNames): CLayerGroup =
		CLayerGroup().apply {
			// CMO3: CLayerGroup - a folder of the decomposed file; ACLayerGroup field _children.
			this.name = name
			memo = ""
			isVisible = true
			blend = names.sharedBlend
			guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
			opacity255 = 255
			_optionOfIOption = names.sharedOptions
			_layeredImage = layeredImage
			_children = children
		}

	/**
	 * The layer's identifier: its name, and Photoshop's layer id when the binding key carries one.
	 *
	 * @param String name     The layer's name.
	 * @param String layerKey The binding key.
	 * @return CLayerIdentifier The identifier.
	 */
	internal fun identifierOf(name: String, layerKey: String): CLayerIdentifier =
		CLayerIdentifier().apply {
			layerName = name
			val photoshopId = layerKey.takeIf { key -> key.startsWith("lyid:") }?.removePrefix("lyid:")?.toIntOrNull()
			if (photoshopId == null) {
				// A CLIP or KRA uuid has no home in a CMO3, so a reopened export keys the layer by name.
				layerIdValue_testImpl = -1
			} else {
				// CMO3: CLayerIdentifier field layerId - Photoshop's lyid as four dash-separated hex
				// bytes, big-endian (the form the atlas ingest parses); layerIdValue_testImpl mirrors
				// it in decimal.
				layerId = (3 downTo 0).joinToString("-") { shift -> ((photoshopId ushr (shift * 8)) and 0xFF).toString(16).padStart(2, '0') }
				layerIdValue_testImpl = photoshopId
			}
		}
}