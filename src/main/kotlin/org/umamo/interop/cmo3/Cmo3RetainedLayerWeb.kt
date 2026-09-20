package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.ACLayerGroup
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterEnv
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.lineageRoot
import kotlin.math.roundToInt

/*
 * The real-layer web of a RETAINED graph: what a CMO3-origin export writes for the art that changed
 * since the import.  A reload supersedes a tile with a new one (`<root>~n`) whose pixels reach the
 * pages, while the layered image the editor decomposed at import still holds the layer as imported;
 * a reload's added layer and an Add Artwork file mint tiles the graph has no model image for at all.
 * This brings the layered-image tree, the model images, and their canvas placements to the edited
 * model, with the same builders the fresh web uses, so the official editor's layered view shows the
 * art the rigger sees and a reopened export re-imports against the file by key.
 *
 * Two jobs, both validated before anything is written:
 *   - a rewrite, for a reloaded tile whose lineage root has a model image: the layer that image
 *     composites takes the tile's pixels, rect, name, and visibility, and the image's canvas
 *     placement moves to the row's origin (a relink repoints the image at the row's layer first,
 *     minting the layer when the file never had it);
 *   - a mint, for a tile with no model image: a layer under its file's layered image (found by the
 *     source's guid, minted for a file the graph never held) in the folder its row names, and a
 *     model image over it.
 * The entry and the drawables' region inputs are the atlas-web reconcile's, which runs over the maps
 * this class extends.
 */

/**
 * The retained-graph layer writer.  Validation ([rewriteJobFor], [mintJobFor]) hands the reconcile a
 * job per tile or null to decline whole; [apply] runs the jobs it was handed.
 *
 * @param Cmo3Model       target         The retained model, whose archive receives the pixels.
 * @param CTextureManager textureManager The graph's texture manager.
 * @param Cmo3GraphEditor editor         The graph editor, for slots the retained document omitted.
 * @param PuppetModel     edited         The session's model.
 * @param Function        tileRasters    The document's pixels for a tile, or null.
 * @param Long            nowMillis      The timestamp minted env values and wrappers record.
 */
internal class Cmo3RetainedLayerWeb(
	private val target: Cmo3Model,
	private val textureManager: CTextureManager,
	private val editor: Cmo3GraphEditor,
	private val edited: PuppetModel,
	private val tileRasters: (AtlasTileId) -> RasterImage?,
	private val nowMillis: Long,
) {
	/**
	 * The art a job writes: the tile, its file and inventory row, and its pixels.
	 *
	 * @property String         tileId       The tile's lineage root - the key the atlas web tracks.
	 * @property AtlasTile      tile         The edited tile.
	 * @property ArtSource      source       The file the tile is bound to.
	 * @property ArtSourceLayer row          The inventory row the binding names.
	 * @property RasterImage    raster       The tile's pixels, straight alpha.
	 * @property FloatArray     canvasAffine The model image's canvas placement: a pure translation to
	 *                                       the row's origin, as a six-float affine.
	 */
	internal open class Job(
		val tileId: String,
		val tile: AtlasTile,
		val source: ArtSource,
		val row: ArtSourceLayer,
		val raster: RasterImage,
	) {
		val canvasAffine: FloatArray = floatArrayOf(1f, 0f, row.left.toFloat(), 0f, 1f, row.top.toFloat())
	}

	/**
	 * A reloaded tile whose lineage root's model image is rewritten to the tile's art.
	 *
	 * @property CModelImage    modelImage The root's model image.
	 * @property SoleLayerInput sole       The one layer input the image composites today.
	 * @property CImageResource filtered   The image's filtered resource, which holds an archive entry.
	 */
	internal class RewriteJob(
		tileId: String,
		tile: AtlasTile,
		source: ArtSource,
		row: ArtSourceLayer,
		raster: RasterImage,
		val modelImage: CModelImage,
		val sole: SoleLayerInput,
		val filtered: CImageResource,
	) : Job(tileId, tile, source, row, raster)

	/** A tile with no model image, whose whole layer web is minted. */
	internal class MintJob(
		tileId: String,
		tile: AtlasTile,
		source: ArtSource,
		row: ArtSourceLayer,
		raster: RasterImage,
	) : Job(tileId, tile, source, row, raster)

	/**
	 * What [apply] added to the web, for the reconcile's maps.
	 *
	 * @property Map modelImageByTileId The model image of every minted tile, by lineage root.
	 */
	internal class Applied(val modelImageByTileId: Map<String, CModelImage>)

	private val sourceById = edited.sources.associateBy { source -> source.id }

	/** Whether the graph carries the two lists a new file's web is added to. */
	private val canMintFiles: Boolean =
		mutableGraphListOf(textureManager._rawImages) != null && mutableGraphListOf(textureManager._modelImageGroups) != null

	/**
	 * The names object every mint threads, built on first use: the archive's own path sequences, and
	 * the filter definitions of a model image the graph already holds (fresh ones when it holds none,
	 * or one another writer shaped differently).
	 */
	private val names: Cmo3ImageChainBuilder.Cmo3FreshChainNames by lazy { namesFor() }

	/** The PNG entries the shared builders collected since the last [embedPending]. */
	private val pendingEntries = ArrayList<Cmo3FreshFile.PngEntry>()

	/**
	 * Embeds every collected entry in the archive.  Called after each builder call, so the local path
	 * sequences and the archive never disagree about what is taken.
	 */
	private fun embedPending() {
		for (entry in pendingEntries) {
			target.addPng(entry.path, entry.pngBytes)
		}
		pendingEntries.clear()
	}

	/**
	 * The art a tile carries, when its binding names a listed file's row and the document holds its
	 * pixels; null otherwise.
	 *
	 * @param AtlasTile tile The edited tile.
	 * @return Job? The art, as a plain job.
	 */
	private fun artOf(tile: AtlasTile): Job? {
		val ref = tile.source ?: return null
		val source = sourceById[ref.sourceId] ?: return null
		val row = source.layers.firstOrNull { candidate -> candidate.key == ref.layerKey } ?: return null
		val raster = tileRasters(tile.id) ?: return null
		return Job(tile.id.lineageRoot.raw, tile, source, row, raster)
	}

	/**
	 * The rewrite job for a reloaded tile, or null when the graph cannot take it: no pixels or no row
	 * for the tile, a model image that composites anything but exactly one layer, or a filtered
	 * resource with no archive entry to replace.
	 *
	 * @param AtlasTile   tile       The edited tile, a reloaded one.
	 * @param CModelImage modelImage Its lineage root's model image.
	 * @return RewriteJob? The job, or null.
	 */
	fun rewriteJobFor(tile: AtlasTile, modelImage: CModelImage): RewriteJob? {
		val art = artOf(tile) ?: return null
		val sole = soleLayerInputOf(modelImage) ?: return null
		if (sole.input.layer !is CLayer) {
			return null
		}
		// CMO3: CModelImage field _filteredImage - the composite the editor shows; for a single layer
		// at identity it is the layer's own pixels, so it takes the new raster too.
		val filtered = modelImage._filteredImage as? CImageResource ?: return null
		if (filtered.imageFileBuf?.archivePath == null) {
			return null
		}
		if (art.source.id.raw != sole.imageGuid && !canMintFiles) {
			// A relink into a file the graph never held needs the file's web minted.
			return null
		}
		return RewriteJob(art.tileId, tile, art.source, art.row, art.raster, modelImage, sole, filtered)
	}

	/**
	 * The mint job for a tile with no model image, or null when the tile has no pixels or no row, or
	 * the graph has no lists to add a file's web to.
	 *
	 * @param AtlasTile tile The edited tile.
	 * @return MintJob? The job, or null.
	 */
	fun mintJobFor(tile: AtlasTile): MintJob? {
		val art = artOf(tile) ?: return null
		if (!canMintFiles) {
			return null
		}
		return MintJob(art.tileId, tile, art.source, art.row, art.raster)
	}

	/**
	 * One retained or minted layered image with the lists a layer is added through.
	 *
	 * @property CLayeredImage    image       The layered image.
	 * @property CModelImageGroup group       Its model-image group.
	 * @param Function            entryListOf The flat entry list every group and layer joins, resolved
	 *                                        on first use so a rewrite that adds nothing never creates
	 *                                        a list the file omitted.
	 */
	private class ImageSite(
		val image: CLayeredImage,
		val group: CModelImageGroup,
		entryListOf: () -> MutableList<Any?>,
	) {
		/** The flat entry list every group and layer joins. */
		val layerEntryList: MutableList<Any?> by lazy(entryListOf)

		/** The folder at each slash-joined path found or minted so far. */
		val folderByPath = HashMap<String, CLayerGroup>()

		/** The layer minted for each binding key so far, so a second tile on one key shares it. */
		val mintedLayerByKey = HashMap<String, CLayer>()
	}

	/**
	 * Runs the jobs: every rewrite, then every mint, sharing one set of file sites so two jobs on one
	 * file mint its web once.  Every pixel entry is embedded as its job completes.
	 *
	 * @param List rewrites The rewrite jobs.
	 * @param List mints    The mint jobs.
	 * @return Applied The minted model images.
	 */
	fun apply(rewrites: List<RewriteJob>, mints: List<MintJob>): Applied {
		if (rewrites.isEmpty() && mints.isEmpty()) {
			return Applied(emptyMap())
		}
		val siteBySourceId = HashMap<String, ImageSite>()
		val modelImageByTileId = HashMap<String, CModelImage>()
		for (job in rewrites) {
			rewrite(job, siteBySourceId)
		}
		for (job in mints) {
			modelImageByTileId[job.tileId] = mint(job, siteBySourceId)
		}
		return Applied(modelImageByTileId)
	}

	/**
	 * A drawable's own icons over its texture patch, embedded now.
	 *
	 * @param RasterImage raster The art the drawable samples, in its own frame.
	 * @param FloatArray? artUvs The drawable's texture coordinates in that frame, or null for the whole art.
	 * @return Pair The 32px and 16px icons.
	 */
	fun drawableIcons(raster: RasterImage, artUvs: FloatArray?): Pair<CImageIcon, CImageIcon> {
		val patch = Cmo3Icons.patchOf(raster, artUvs)
		val icon32 = Cmo3Icons.iconOf(patch, 32, names.nextIconPath(), pendingEntries)
		val icon16 = Cmo3Icons.iconOf(patch, 16, names.nextIconPath(), pendingEntries)
		embedPending()
		return icon32 to icon16
	}

	/**
	 * Re-renders a file-side drawable's icons over its (changed) art, in place where the icons have
	 * entries and minted with their slots recorded where the file carried none.
	 *
	 * @param CArtMeshSource mesh   The drawable source.
	 * @param RasterImage    raster The art it samples, in its own frame.
	 * @param FloatArray?    artUvs Its texture coordinates in that frame, or null for the whole art.
	 */
	fun replaceDrawableIcons(mesh: CArtMeshSource, raster: RasterImage, artUvs: FloatArray?) {
		val patch = Cmo3Icons.patchOf(raster, artUvs)
		// CMO3: ACDrawableSource fields icon32 / icon16, in that order after invertClippingMask.
		refreshIcon(mesh.icon32, patch, 32) { icon ->
			mesh.icon32 = icon
			editor.ensureChildSlot(mesh, "ACDrawableSource", "icon32", "icon16")
		}
		refreshIcon(mesh.icon16, patch, 16) { icon ->
			mesh.icon16 = icon
			editor.ensureChildSlot(mesh, "ACDrawableSource", "icon16")
		}
	}

	/**
	 * Writes the model's three icons as fits of [thumbnail], in place where they have entries and
	 * minted with their slots recorded where the file carried none.
	 *
	 * @param CModelSource modelSource The model root.
	 * @param RasterImage  thumbnail   The model's rest-pose thumbnail.
	 */
	fun replaceModelIcons(modelSource: CModelSource, thumbnail: RasterImage) {
		// CMO3: CModelSource fields _icon64 / _icon32 / _icon16, in that order before gameMotionSet.
		refreshIcon(modelSource._icon64, thumbnail, 64) { icon ->
			modelSource._icon64 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon64", "_icon32")
		}
		refreshIcon(modelSource._icon32, thumbnail, 32) { icon ->
			modelSource._icon32 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon32", "_icon16")
		}
		refreshIcon(modelSource._icon16, thumbnail, 16) { icon ->
			modelSource._icon16 = icon
			editor.ensureChildSlot(modelSource, "CModelSource", "_icon16", "gameMotionSet")
		}
	}

	/**
	 * Re-renders one icon slot: the existing entry's bytes are replaced when the slot names one the
	 * archive holds, else a fresh icon is minted and handed to [assign] to set and record its slot.
	 *
	 * @param Any?        current The slot's icon today.
	 * @param RasterImage raster  The art to fit.
	 * @param Int         size    The square's edge.
	 * @param Function    assign  Sets a minted icon on its owner and records the slot.
	 */
	private fun refreshIcon(current: Any?, raster: RasterImage, size: Int, assign: (CImageIcon) -> Unit) {
		val path = Cmo3Icons.archivePathOf(current)
		if (path != null && target.archive.byPath(path) != null) {
			target.replacePng(path, Cmo3Icons.iconPngOf(raster, size))
			(current as CImageIcon).image.let { image ->
				if (image is CWritableImage) {
					image.width = size
					image.height = size
				}
			}
			return
		}
		assign(Cmo3Icons.iconOf(raster, size, names.nextIconPath(), pendingEntries))
		embedPending()
	}

	/**
	 * Re-renders a rewritten layer's icons over its new art.
	 *
	 * @param CLayer      layer  The layer.
	 * @param RasterImage raster Its new pixels.
	 */
	private fun replaceLayerIcons(layer: CLayer, raster: RasterImage) {
		// CMO3: CLayer fields icon16 / icon64, in that order before layerInfo.
		refreshIcon(layer.icon16, raster, 16) { icon ->
			layer.icon16 = icon
			editor.ensureChildSlot(layer, "CLayer", "icon16", "icon64")
		}
		refreshIcon(layer.icon64, raster, 64) { icon ->
			layer.icon64 = icon
			editor.ensureChildSlot(layer, "CLayer", "icon64", "layerInfo")
		}
	}

	/**
	 * Re-renders a rewritten model image's icon over its new art.
	 *
	 * @param CModelImage modelImage The model image.
	 * @param RasterImage raster     Its new pixels.
	 */
	private fun replaceModelImageIcon(modelImage: CModelImage, raster: RasterImage) {
		// CMO3: CModelImage field icon16, before _materialLocalToCanvasTransform.
		refreshIcon(modelImage.icon16, raster, 16) { icon ->
			modelImage.icon16 = icon
			editor.ensureChildSlot(modelImage, "CModelImage", "icon16", "_materialLocalToCanvasTransform")
		}
	}

	/**
	 * The names object a minted web threads: the archive's own path sequences, and the filter
	 * definitions of a model image the graph already holds (fresh ones when it holds none, or one
	 * another writer shaped differently).
	 *
	 * @return Cmo3FreshChainNames The names.
	 */
	private fun namesFor(): Cmo3ImageChainBuilder.Cmo3FreshChainNames {
		val retainedFilterSet =
			Cmo3Import.elementsOf(textureManager._modelImageGroups)
				.filterIsInstance<CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
				.firstNotNullOfOrNull { modelImage -> modelImage.inputFilter as? ModelImageFilterSet }
		val filters =
			retainedFilterSet?.let { filterSet -> Cmo3ImageChainBuilder.FilterCommons.fromFilterSet(filterSet) }
				?: Cmo3ImageChainBuilder.FilterCommons.fresh()
		val bufferPaths = Cmo3ImageChainBuilder.FreshPathSequence.continuingFrom("imageFileBuf", target.nextImageFileBufPath())
		val iconPaths = Cmo3ImageChainBuilder.FreshPathSequence.continuingFrom("image", target.nextImagePath())
		return Cmo3ImageChainBuilder.Cmo3FreshChainNames(filters, bufferPaths::next, iconPaths::next)
	}

	/**
	 * Rewrites a reloaded tile's model image to the tile's art: the row's layer (the composited one
	 * when the binding did not move, else found by key in the row's file or minted) takes the pixels,
	 * rect, name, and visibility; the image's placement, name, and cache follow.
	 *
	 * @param RewriteJob  job            The job.
	 * @param MutableMap  siteBySourceId The file sites found or minted so far.
	 */
	private fun rewrite(job: RewriteJob, siteBySourceId: MutableMap<String, ImageSite>) {
		val modelImage = job.modelImage
		val compositedLayer = job.sole.input.layer as CLayer
		val sourceGuid = job.source.id.raw
		val site = siteFor(job.source, siteBySourceId)
		// The layer the row names: the composited one when the tile was reloaded in place, which is
		// the common case and touches no selector; otherwise the relink's target, repointed to below.
		val sameFile = sourceGuid == job.sole.imageGuid
		val compositedKey = walkLayeredImage(site.image).firstOrNull { walked -> walked.layer === compositedLayer }?.key
		val layer =
			if (sameFile && compositedKey == job.row.key) {
				compositedLayer
			} else {
				layerFor(site, job.row, job.raster)
			}
		val pngBytes = PngCodec.write(job.raster)
		writeLayer(layer, job.row, job.raster, pngBytes)
		replaceLayerIcons(layer, job.raster)
		// CMO3: CModelImage field _filteredImage - replaced in place when it is a resource of its own
		// rather than the layer's (the fresh web shares one instance; an editor import may not).
		val filtered = job.filtered
		if (filtered !== layer.imageResource) {
			if (filtered.imageFileBuf?.archivePath != null) {
				target.replaceLayerPng(filtered, pngBytes)
			}
			filtered.width = job.raster.width
			filtered.height = job.raster.height
		}
		if (layer !== compositedLayer) {
			// CMO3: CLayerInputData field layer - the selector's target, repointed at the row's layer.
			job.sole.input.layer = layer
			if (!sameFile) {
				repointImage(modelImage, job.sole, site.image)
			}
		}
		// CMO3: CModelImage fields name / _materialLocalToCanvasTransform / cachedImageManager - the
		// tile's name, the row's origin as a pure translation, and a cache over the new dimensions.
		modelImage.name = job.tile.name
		val placement = modelImage._materialLocalToCanvasTransform as? CAffine
		if (placement == null) {
			modelImage._materialLocalToCanvasTransform = Cmo3SourceLayerWeb.layerPlacement(job.row.left, job.row.top)
			editor.ensureChildSlot(modelImage, "CModelImage", "_materialLocalToCanvasTransform", "_group")
		} else {
			placement.setFromAffineArray(job.canvasAffine)
		}
		modelImage.cachedImageManager = Cmo3ImageChainBuilder.paddedCacheManager(filtered, job.raster.width, job.raster.height)
		replaceModelImageIcon(modelImage, job.raster)
		embedPending()
	}

	/**
	 * Moves a model image to another file: the selector map's key, the current-image env value, and
	 * the linked-image list all name the new layered image.
	 *
	 * @param CModelImage    modelImage The model image.
	 * @param SoleLayerInput sole       Its one layer input, already repointed at the new file's layer.
	 * @param CLayeredImage  image      The new file's layered image.
	 */
	private fun repointImage(modelImage: CModelImage, sole: SoleLayerInput, image: CLayeredImage) {
		// CMO3: ModelImageFilterEnv field envValues -> EnvValueSet field value: the CLayerSelectorMap's
		// _imageToLayerInput keyed by image guid, and the current-image guid value beside it.
		val filterEnv = modelImage.inputFilterEnv as? FilterEnv ?: return
		for (valueSet in Cmo3Import.elementsOf(filterEnv.envValues).filterIsInstance<EnvValueSet>()) {
			val selectorMap = valueSet.value as? CLayerSelectorMap
			if (selectorMap != null) {
				selectorMap._imageToLayerInput = LinkedHashMap<Any?, Any?>().apply { put(image.guid, ArrayList<Any?>(mutableListOf(sole.input))) }
				valueSet.updateTimeMs = nowMillis
			} else if (Cmo3Import.uuidOf(valueSet.value) == sole.imageGuid) {
				valueSet.value = image.guid
				valueSet.updateTimeMs = nowMillis
			}
		}
		// CMO3: CModelImage field linkedRawImageGuids.
		modelImage.linkedRawImageGuids = CArrayList<Any?>(mutableListOf(image.guid))
	}

	/**
	 * Writes a tile's art into a layer: the archive entry, the resource dimensions, the rect at the
	 * row's origin, and the row's name and visibility.
	 *
	 * @param CLayer         layer    The layer.
	 * @param ArtSourceLayer row      The inventory row.
	 * @param RasterImage    raster   The pixels.
	 * @param ByteArray      pngBytes The pixels, encoded.
	 */
	private fun writeLayer(layer: CLayer, row: ArtSourceLayer, raster: RasterImage, pngBytes: ByteArray) {
		val resource = layer.imageResource as? CImageResource
		if (resource != null && resource.imageFileBuf?.archivePath != null) {
			target.replaceLayerPng(resource, pngBytes)
			// CMO3: CImageResource fields width / height - corpus-verified to equal the decoded PNG's.
			resource.width = raster.width
			resource.height = raster.height
		}
		// CMO3: CLayer field boundsOnImageDoc, written into the existing rect when there is one.
		val bounds = layer.boundsOnImageDoc as? CRect
		if (bounds == null) {
			layer.boundsOnImageDoc = Cmo3SourceLayerWeb.layerBounds(row.left, row.top, raster.width, raster.height)
			editor.ensureChildSlot(layer, "CLayer", "boundsOnImageDoc", "layerIdentifier")
		} else {
			bounds.x = row.left
			bounds.y = row.top
			bounds.width = raster.width
			bounds.height = raster.height
		}
		// CMO3: ACLayerEntry fields name / isVisible - as the file reads today.
		layer.name = row.name
		layer.isVisible = row.visible
	}

	/**
	 * Mints a tile's layer web: the layer under its file in the row's folder, and a model image over
	 * it at the row's origin.
	 *
	 * @param MintJob     job            The job.
	 * @param MutableMap  siteBySourceId The file sites found or minted so far.
	 * @return CModelImage The minted model image.
	 */
	private fun mint(job: MintJob, siteBySourceId: MutableMap<String, ImageSite>): CModelImage {
		val site = siteFor(job.source, siteBySourceId)
		val layer = layerFor(site, job.row, job.raster)
		// A second tile on one key (a double binding) shares the layer the first minted, pixels
		// included, since a model image composites its layer's own resource; only the model image is
		// its own.
		val resource = layer.imageResource as CImageResource
		val modelImage =
			Cmo3ImageChainBuilder.modelImageOver(
				job.tile.name,
				site.image,
				layer,
				resource,
				job.raster,
				Cmo3SourceLayerWeb.layerPlacement(job.row.left, job.row.top),
				site.group,
				names,
				pendingEntries,
				nowMillis,
			)
		checkNotNull(mutableGraphListOf(site.group._modelImages)) { "a site's group has a model-image list" }.add(modelImage)
		embedPending()
		return modelImage
	}

	/**
	 * The file's site: its retained layered image and group (the group minted when the image has
	 * none), or a whole minted web for a file the graph never held.
	 *
	 * @param ArtSource   source         The file.
	 * @param MutableMap  siteBySourceId The sites so far.
	 * @return ImageSite The site.
	 */
	private fun siteFor(source: ArtSource, siteBySourceId: MutableMap<String, ImageSite>): ImageSite {
		siteBySourceId[source.id.raw]?.let { existing -> return existing }
		val rawImages = checkNotNull(mutableGraphListOf(textureManager._rawImages)) { "validated: the raw-image list exists" }
		val groups = checkNotNull(mutableGraphListOf(textureManager._modelImageGroups)) { "validated: the group list exists" }
		// CMO3: CTextureManager field _rawImages -> LayeredImageWrapper field image -> CLayeredImage
		// field guid - the source id a CMO3-origin document carries.
		val retainedImage =
			rawImages.firstNotNullOfOrNull { wrapper ->
				((wrapper as? LayeredImageWrapper)?.image as? CLayeredImage)?.takeIf { image -> Cmo3Import.uuidOf(image.guid) == source.id.raw }
			}
		val site =
			if (retainedImage != null) {
				// CMO3: CModelImageGroup field _linkedRawImageGuids - the group a file's images live in.
				val retainedGroup =
					groups.filterIsInstance<CModelImageGroup>().firstOrNull { group ->
						Cmo3Import.elementsOf(group._linkedRawImageGuids).any { guid -> Cmo3Import.uuidOf(guid) == source.id.raw } &&
							mutableGraphListOf(group._modelImages) != null
					}
				val group =
					retainedGroup
						?: Cmo3SourceLayerWeb.mintModelImageGroup(source.name, retainedImage).also { minted -> groups.add(minted) }
				ImageSite(retainedImage, group) { layerEntryListOf(retainedImage) }
			} else {
				val canvasWidth = edited.canvasWidth.roundToInt()
				val canvasHeight = edited.canvasHeight.roundToInt()
				val minted = Cmo3SourceLayerWeb.mintLayeredImage(source.name, source.path, source.lastModified, canvasWidth, canvasHeight, names, nowMillis)
				rawImages.add(minted.wrapper)
				val group = Cmo3SourceLayerWeb.mintModelImageGroup(source.name, minted.image)
				groups.add(group)
				ImageSite(minted.image, group) { minted.layerEntryList }.also { site -> site.folderByPath[""] = minted.rootGroup }
			}
		siteBySourceId[source.id.raw] = site
		return site
	}

	/**
	 * A retained image's flat entry list, created (with its slot recorded) when the file omitted it.
	 *
	 * @param CLayeredImage image The layered image.
	 * @return MutableList The list.
	 */
	private fun layerEntryListOf(image: CLayeredImage): MutableList<Any?> {
		// CMO3: CLayeredImage field layerSet -> LayerSet field _layerEntryList.
		val layerSet =
			image.layerSet as? LayerSet
				?: LayerSet().also { created ->
					created._layeredImage = image
					image.layerSet = created
					editor.ensureChildSlot(image, "CLayeredImage", "layerSet")
				}
		return mutableGraphListOf(layerSet._layerEntryList)
			?: CArrayList<Any?>().also { created ->
				layerSet._layerEntryList = created
				editor.ensureChildSlot(layerSet, "LayerSet", "_layerEntryList")
			}
	}

	/**
	 * The row's layer in a site: one minted this pass for the same key, else the file's own layer
	 * under that key, else a fresh layer with the tile's pixels in the row's folder.
	 *
	 * @param ImageSite      site       The file's site.
	 * @param ArtSourceLayer row        The inventory row.
	 * @param RasterImage    raster     The pixels a minted layer takes.
	 * @return CLayer The layer.
	 */
	private fun layerFor(site: ImageSite, row: ArtSourceLayer, raster: RasterImage): CLayer {
		site.mintedLayerByKey[row.key]?.let { minted -> return minted }
		walkLayeredImage(site.image).firstOrNull { walked -> walked.key == row.key }?.let { walked -> return walked.layer }
		val folder = folderAt(site, row.groupPath)
		val pngBytes = PngCodec.write(raster)
		val path = names.nextImageFileBufPath()
		pendingEntries.add(Cmo3FreshFile.PngEntry(path, pngBytes))
		val resource = Cmo3SourceLayerWeb.layerResource(path, raster, pngBytes.size)
		val layer =
			Cmo3SourceLayerWeb.layerOver(
				name = row.name,
				layerKey = row.key,
				visible = row.visible,
				canvasLeft = row.left,
				canvasTop = row.top,
				resource = resource,
				raster = raster,
				layeredImage = site.image,
				folder = folder,
				names = names,
				pngEntries = pendingEntries,
			)
		childrenOf(folder).add(layer)
		site.layerEntryList.add(layer)
		site.mintedLayerByKey[row.key] = layer
		embedPending()
		return layer
	}

	/**
	 * The folder at a slash-joined path in a site's tree, walking the retained groups by name and
	 * minting each missing segment under its parent.
	 *
	 * @param ImageSite site  The file's site.
	 * @param String    path  The folder path, "" for the root.
	 * @return CLayerGroup The folder.
	 */
	private fun folderAt(site: ImageSite, path: String): CLayerGroup {
		site.folderByPath[path]?.let { known -> return known }
		if (path.isEmpty()) {
			// CMO3: CLayeredImage field _rootLayer - the tree's root group.
			val root =
				site.image._rootLayer as? CLayerGroup
					?: Cmo3SourceLayerWeb.layerGroup("root", site.image, CArrayList(), names).also { minted ->
						site.image._rootLayer = minted
						editor.ensureChildSlot(site.image, "CLayeredImage", "_rootLayer", "layerSet")
						site.layerEntryList.add(0, minted)
					}
			site.folderByPath[""] = root
			return root
		}
		val parent = folderAt(site, path.substringBeforeLast('/', ""))
		val name = path.substringAfterLast('/')
		val siblings = childrenOf(parent)
		// CMO3: ACLayerEntry field name - a folder is found by its own name under its parent.
		val folder =
			siblings.filterIsInstance<CLayerGroup>().firstOrNull { group -> group.name == name }
				?: Cmo3SourceLayerWeb.layerGroup(name, site.image, CArrayList(), names).also { minted ->
					siblings.add(minted)
					site.layerEntryList.add(minted)
				}
		site.folderByPath[path] = folder
		return folder
	}

	/**
	 * A group's child list, created (with its slot recorded) when the file omitted it.
	 *
	 * @param ACLayerGroup group The group.
	 * @return MutableList The list.
	 */
	private fun childrenOf(group: ACLayerGroup): MutableList<Any?> =
		// CMO3: ACLayerGroup field _children.
		mutableGraphListOf(group._children)
			?: CArrayList<Any?>().also { created ->
				group._children = created
				editor.ensureChildSlot(group, "ACLayerGroup", "_children")
			}
}