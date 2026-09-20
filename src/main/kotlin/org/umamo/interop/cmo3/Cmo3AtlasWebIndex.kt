package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine

/*
 * The retained graph's atlas web indexed by tile id (the model image guid), the one walk both the
 * web reconcile and the per-tile placement lowering read.  Two copies of the walk would have to
 * agree on guid filtering and on capturing the FULL canvas affine, or the reconcile and the lowering
 * that runs right after it could classify one tile differently inside a single export.
 */

/**
 * One tracked entry: the element, the atlas that currently holds it, and that atlas's index.
 *
 * @property ModelImageEntry entry      The packed entry.
 * @property CTextureAtlas   atlas      The atlas element holding it.
 * @property Int             atlasIndex That atlas's index in the texture manager's list.
 */
internal class AtlasEntrySite(
	val entry: ModelImageEntry,
	val atlas: CTextureAtlas,
	val atlasIndex: Int,
)

/**
 * A texture manager's atlas web, keyed by tile id.
 *
 * @property Map  siteByTileId           Each tracked entry by the tile it packs.
 * @property List strayEntryAtlasIndices The atlas index of every entry that cannot be tracked (no
 *                                       guid, or a guid already seen), one element per such entry.
 * @property Map  modelImageByTileId     Each pooled model image by its guid.
 * @property Map  canvasAffineByTileId   Each model image's canvas placement as a six-float affine.
 */
internal class Cmo3AtlasWebIndex(
	val siteByTileId: Map<String, AtlasEntrySite>,
	val strayEntryAtlasIndices: List<Int>,
	val modelImageByTileId: Map<String, CModelImage>,
	val canvasAffineByTileId: Map<String, FloatArray>,
)

/**
 * Indexes [textureManager]'s atlas web.
 *
 * Built fresh by every pass that reads it rather than shared between them: the web reconcile moves
 * entries between atlases, so an index taken before it is stale for the placement lowering that runs
 * after.
 *
 * @param CTextureManager textureManager The retained graph's texture manager.
 * @return Cmo3AtlasWebIndex The web as it stands now.
 */
internal fun indexAtlasWeb(textureManager: CTextureManager): Cmo3AtlasWebIndex {
	// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas field modelImages -> ModelImageEntry
	// field modelImageGuid.  Which atlas holds an entry IS the tile's page membership.  A guid-less or
	// guid-duplicated entry cannot be tracked against the model; it stays where it sits and only
	// constrains page deletion.  Indices are the RAW list positions, which is what the reconcile
	// addresses the list by.
	val siteByTileId = HashMap<String, AtlasEntrySite>()
	val strayEntryAtlasIndices = ArrayList<Int>()
	for ((atlasIndex, member) in Cmo3Import.elementsOf(textureManager._textureAtlases).withIndex()) {
		val atlas = member as? CTextureAtlas ?: continue
		for (entry in Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
			val uuid = Cmo3Import.uuidOf(entry.modelImageGuid)
			if (uuid == null || siteByTileId.containsKey(uuid)) {
				strayEntryAtlasIndices.add(atlasIndex)
			} else {
				siteByTileId[uuid] = AtlasEntrySite(entry, atlas, atlasIndex)
			}
		}
	}
	// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages -> CModelImage
	// fields guid / _materialLocalToCanvasTransform - where the art sits on the CANVAS.  A repack moves
	// art on the page and never on the canvas, so this is the fixed point every rewritten transform
	// pair has to keep composing to.  The FULL affine, not just its translation: an official file's
	// model images are pure translations, but a converted graph carries the original packing's linear
	// part here, and the composition must reproduce it.
	val modelImageByTileId = HashMap<String, CModelImage>()
	val canvasAffineByTileId = HashMap<String, FloatArray>()
	for (group in Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
		for (modelImage in Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>()) {
			val uuid = Cmo3Import.uuidOf(modelImage.guid) ?: continue
			modelImageByTileId[uuid] = modelImage
			val canvas = modelImage._materialLocalToCanvasTransform as? CAffine ?: continue
			canvasAffineByTileId[uuid] = canvas.toAffineArray()
		}
	}
	return Cmo3AtlasWebIndex(siteByTileId, strayEntryAtlasIndices, modelImageByTileId, canvasAffineByTileId)
}