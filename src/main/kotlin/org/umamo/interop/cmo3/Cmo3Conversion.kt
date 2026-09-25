package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3FileFormatVersion
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.interop.moc3.export.Moc3VersionDowngrade
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.roundToInt

/**
 * Converts a puppet with NO retained CMO3 graph (a MOC3-origin or artwork-origin document) into a
 * fresh Cmo3Model: the blank skeleton plus the image chain are synthesized, serialized, and read
 * back through the normal codec, then the ordinary reconcile lowers the whole puppet onto the empty
 * baseline as created entities.  Stateless - each call builds a new graph; the caller writes the
 * result with Cmo3.write.
 *
 * The file this produces opens and renders in the official Cubism Editor and switches between its
 * layered-art and texture-atlas display modes.  An artwork-origin document writes its REAL source
 * art: every tile with a raster and an inventory row becomes a layer of its file's layered image
 * ([Cmo3SourceLayerWeb]), so the export is the shape the editor writes for its own PSD import and
 * reopens with the same layer identities.  A MOC3-origin document has none of that: a .moc3
 * carries only the packed pages, so [Cmo3ImageChainBuilder] fabricates a source document by slicing
 * the packed atlas back apart, every layer in that output is a slice of a baked page rather than the
 * artwork the rig was drawn from, and the report leads with MissingSourceArt for as long as any
 * drawable's art came out of a page.
 *
 * One open issue at scale: modelF (~850 MB, several hundred layers) OOMs the official editor when
 * switched back to texture-atlas mode.  Atlas mode recomposites the page from the materials, so
 * peak memory tracks the TOTAL crop area this builder emits rather than the page size - measure
 * that ratio before treating it as an editor limit.
 *
 * The CMO3-origin path ([Cmo3Export.apply] onto a retained graph) keeps the document's real
 * layered art, and its byte-identity gates hold.
 */
public object Cmo3Conversion {
	/** One atlas page: the original PNG bytes (model3 texture order) plus its pixel dimensions. */
	public class AtlasPage(
		public val pngBytes: ByteArray,
		public val width: Int,
		public val height: Int,
	)

	/**
	 * The conversion outcome: the fresh model, the reconcile's advisory report, and the puppet
	 * actually encoded - the input after the atlas un-dedup prepass, whose uvs differ from the
	 * caller's wherever a baked twin was routed to its own synthesized slot.  Round-trip checks
	 * must compare a re-import against THIS puppet, not the caller's.
	 */
	public class Result(
		public val model: Cmo3Model,
		public val report: ExportReport,
		public val puppet: PuppetModel,
	)

	/**
	 * Builds a fresh CMO3 for [puppet].
	 *
	 * @param PuppetModel puppet    The model to convert (the session's current state).
	 * @param List        pages     The atlas pages, in model3 texture order.
	 * @param Map         pageIndexByDrawableId Each drawable id's atlas page index; a drawable
	 *                              missing here cannot bind a texture and surfaces as a notice.
	 * @param String      modelName The document display name the skeleton records.
	 * @param Long        nowMillis The wall-clock import timestamp the image chain records.
	 * @param Int         obfuscateKey The container XOR key; the editor mints one per save.
	 * @param Function    tileRasters The document's own pixels for a tile, or null when it holds
	 *                              none; a tile with a raster and an inventory row writes its real
	 *                              layer, every other drawable takes the crop stand-in.  A
	 *                              MOC3-origin document has no tiles and passes nothing.
	 * @param RasterImage modelThumbnail The model's rest-pose thumbnail the three model icons fit,
	 *                              or null for the blank icons the editor writes for a model it
	 *                              never rendered.
	 * @return Result The fresh model plus the reconcile report.
	 */
	public fun freshCmo3(
		puppet: PuppetModel,
		pages: List<AtlasPage>,
		pageIndexByDrawableId: Map<String, Int>,
		modelName: String,
		nowMillis: Long,
		obfuscateKey: Int,
		tileRasters: (AtlasTileId) -> RasterImage? = { null },
		modelThumbnail: RasterImage? = null,
	): Result {
		val downgrade = Moc3VersionDowngrade.stripForCmo3(puppet, puppet.runtimeTarget)
		val compatiblePuppet = Cmo3MeshSanitizer.sanitize(downgrade.puppet)
		// Real layers first: a drawable whose tile has a raster and an inventory row writes that
		// raster as its own layer, and leaves the crop path - the un-dedup included, since a real
		// tile's model image is the single-placement shape by construction.
		val sourceImages = Cmo3SourceLayerWeb.inputsOf(compatiblePuppet, tileRasters)
		val realDrawableIds = sourceImages.flatMapTo(HashSet()) { image -> image.layers.flatMap { layer -> layer.drawableIds } }
		val cropPageIndexByDrawableId = pageIndexByDrawableId.filterKeys { drawableId -> drawableId !in realDrawableIds }
		// Baked twins (one atlas slot, several canvas placements) are unrepresentable in the
		// model-image web; the prepass copies each additional placement's patch onto a synthesized
		// page and remaps those drawables' uvs there.  Everything below runs on ITS outputs.
		val undedup = Cmo3AtlasUndedup.undeduplicate(compatiblePuppet, pages, cropPageIndexByDrawableId)
		val effectivePuppet = undedup.puppet
		val effectivePages = undedup.pages
		val effectivePageIndexByDrawableId = undedup.pageIndexByDrawableId
		val skeleton =
			Cmo3SkeletonBuilder.buildBlank(
				modelName = modelName,
				canvasWidth = effectivePuppet.canvasWidth.roundToInt(),
				canvasHeight = effectivePuppet.canvasHeight.roundToInt(),
				targetVersionNo = effectivePuppet.runtimeTarget.cmo3TargetVersionNo(),
				modelThumbnail = modelThumbnail,
			)
		// Each page's drawable regions feed the per-drawable patch webs (crop + placement fit).
		// The puppet's mesh.positions MUST be canvas-frame here: the app's MOC3 document loader
		// normalizes parent-local rest meshes through :render's restMeshesToCanvasSpace before any
		// export, and callers converting a raw Moc3Import puppet must do the same (the official
		// source-level positions and the whole placement web are canvas geometry).
		val regionsByPage = List(effectivePages.size) { ArrayList<Cmo3ImageChainBuilder.DrawableRegion>() }
		for (drawable in effectivePuppet.drawables) {
			val pageIndex = effectivePageIndexByDrawableId[drawable.id.raw] ?: continue
			val mesh = drawable.mesh ?: continue
			regionsByPage.getOrNull(pageIndex)?.add(
				Cmo3ImageChainBuilder.DrawableRegion(drawable.id.raw, mesh.uvs, mesh.positions, mesh.indices),
			)
		}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				effectivePages,
				regionsByPage,
				nowMillis,
				fromSourceLayers = effectivePuppet.rendersFromSourceLayers,
				sourceImages = sourceImages,
			)
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon ->
						Cmo3FreshFile.PngEntry(
							icon.path,
							icon.pngBytes,
						)
					} + chain.pngEntries,
					obfuscateKey,
					fileFormatVersion = effectivePuppet.runtimeTarget.cmo3FileFormatVersion(),
				),
			)
		rebindPageResources(chain, model)
		val bindings = HashMap<String, Cmo3DrawableTextureBinding>()
		for (drawable in effectivePuppet.drawables) {
			// A real-layer drawable has its own binding; a crop-path drawable its own or its page's.
			val binding =
				chain.bindingByDrawableId[drawable.id.raw]
					?: effectivePageIndexByDrawableId[drawable.id.raw]?.let { pageIndex -> chain.pageFallbackBindings.getOrNull(pageIndex) }
			binding?.let { resolved -> bindings[drawable.id.raw] = resolved }
		}
		val report = Cmo3Export.apply(effectivePuppet, model, bindings)
		// An export whose art did not all come from real layers - a drawable sliced out of a page or
		// bound to no page, or a document with no real layer to write at all - is source-art-less, so
		// the notice leads the report rather than hiding behind the per-entity findings.  It is the
		// one finding that describes the WHOLE file rather than an entity in it, which no amount of
		// per-drawable detail would tell the user; a document whose every drawable wrote its real
		// layer carries no such finding.  A twin the prepass had to leave sharing its slot follows
		// it, for the same reason.
		val everyDrawableIsReal = sourceImages.isNotEmpty() && effectivePuppet.drawables.all { drawable -> drawable.id.raw in realDrawableIds }
		val leading = ArrayList<ExportNotice>()
		if (!everyDrawableIsReal) {
			leading.add(ExportNotice.MissingSourceArt(effectivePages.size))
		}
		if (undedup.sharedDrawableIds.isNotEmpty()) {
			val nameById = effectivePuppet.drawables.associate { drawable -> drawable.id.raw to drawable.name }
			leading.add(ExportNotice.SharedAtlasSlotKept(undedup.sharedDrawableIds.map { drawableId -> nameById[drawableId] ?: drawableId }))
		}
		return Result(model, report.copy(notices = downgrade.notices + leading + report.notices), effectivePuppet)
	}

	/**
	 * Re-points each page texture at the RE-READ atlas's own page resource.
	 *
	 * The image chain is built over the pre-serialization skeleton, so a binding's GTexture2D still
	 * carries that graph's CImageResource - while the reconcile hangs that texture off drawables in
	 * the graph read back out of the assembled bytes.  Left alone the document ends up with two
	 * equal-but-distinct resources per page (one under the atlas's cachedAtlasImage, one under the
	 * drawables' texture) where the editor's own files share a single one by reference.
	 *
	 * That split is not cosmetic: whether a drawable's uvs are page-frame is decided by whether the
	 * resource it samples IS one of the atlases' page resources (docs/format/CMO3.md section 6 - a
	 * document can carry an atlas while its drawables sample per-layer rasters instead), and the test
	 * is identity because a CImageResource has no value equality.  With the twin resource in place
	 * every re-imported drawable reads as never-packed, so its source-layer view drops the packing
	 * inverse and draws atlas-frame uvs straight onto the layer crop.
	 *
	 * @param Cmo3ImageChainBuilder.BuiltImageChain chain The image chain built over the skeleton.
	 * @param Cmo3Model                             model The model read back out of the assembled bytes.
	 */
	private fun rebindPageResources(chain: Cmo3ImageChainBuilder.BuiltImageChain, model: Cmo3Model) {
		// CMO3: CModelSource field textureManager -> CTextureManager field _textureAtlases ->
		// CTextureAtlas field cachedAtlasImage - the page's own pixels.
		val textureManager = (model.root as? CModelSource)?.textureManager as? CTextureManager ?: return
		val pageResourceByPath = HashMap<String, CImageResource>()
		for (atlas in Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()) {
			val pageResource = atlas.cachedAtlasImage as? CImageResource ?: continue
			pageResource.imageFileBuf?.archivePath?.let { path -> pageResourceByPath[path] = pageResource }
		}
		// One texture per page, shared by every binding on it, so the page fallbacks reach them all.
		for (binding in chain.pageFallbackBindings) {
			val path = (binding.texture.srcImageResource as? CImageResource)?.imageFileBuf?.archivePath ?: continue
			pageResourceByPath[path]?.let { pageResource -> binding.texture.srcImageResource = pageResource }
		}
	}
}
