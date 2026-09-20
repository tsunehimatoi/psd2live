package org.umamo.runtime.model

/*
 * The document's atlas: the pages it packs onto and the tiles packed onto them.
 *
 * This is the source-to-atlas indirection made explicit.  A drawable's uvs address a page, but what
 * they MEAN is a position in its source art, and the placement is the only thing that connects the
 * two - so a repack that moves pixels around a page must move uvs with them and leave the art mapping
 * untouched.  Keeping the placement as model state is what lets a repack be an undoable edit and lets
 * an export write where the art actually went, rather than both guessing from uvs.
 */

/**
 * One packed page's dimensions.
 *
 * Pixels are not model state - a page's bytes belong to the document, and hundreds of undo snapshots
 * holding them would be ruinous - but the SIZE is, because a placement in page pixels means nothing
 * without it and every uv derivation needs it.
 *
 * @property Int width  The page width in pixels.
 * @property Int height The page height in pixels.
 */
data class AtlasPage(
	val width: Int,
	val height: Int,
)

/**
 * One piece of source art the atlas packs: its identity, its size, and where it landed.
 *
 * A tile is per ARTWORK, not per drawable - several drawables can sample one piece of art, and they all
 * move together when it is repacked, which is why the placement lives here and not on the drawable.
 *
 * A null [placement] means the art is in the document but was never packed onto a page; drawables
 * bound to it sample it directly and their uvs already address it.
 *
 * A [pinned] tile is one a rigger placed by hand and wants kept: a repack packs the other tiles
 * around it and leaves its placement - page, position, rotation, and scale - exactly as it is.
 * Editor state, not a format field: no container format encodes it (the native format will), so the
 * model diff ignores it and an export never sees it.  A tile without a placement is never pinned -
 * there is nothing to keep.
 *
 * @property AtlasTileId     id        The tile's stable document-local identity.
 * @property String          name      The tile's display name.
 * @property Int             width     The art's width in pixels.
 * @property Int             height    The art's height in pixels.
 * @property AtlasPlacement? placement Where it sits on its page, or null when it was never packed.
 * @property SourceLayerRef? source    The source layer this tile's art came from - the artwork file
 *   and the layer key within it - or null when the document retains no binding (a MOC3-origin rig;
 *   a CMO3-origin one recovers a name-keyed ref from its image chain).  Editor and native-format
 *   data: no container format encodes it, the model diff reads it as tile metadata, and an export
 *   never sees it.
 * @property Boolean         pinned    Whether a repack keeps this tile's placement where it is.
 * @property AtlasTileId?    replaces  The tile this one superseded when its source layer was reloaded,
 *   or null for a tile born at import.  A tile's pixels are immutable: reloading a layer mints a new
 *   tile (id `<root>~<n>`, see [reloadTileId]) so the old tile and its pixels stay addressable for undo,
 *   and this is the lineage a consumer keyed on the ORIGINAL tile follows - a CMO3 export resolves the
 *   reloaded tile to the model image its root imported from.  Editor and native-format data, like [source].
 */
data class AtlasTile(
	val id: AtlasTileId,
	val name: String,
	val width: Int,
	val height: Int,
	val placement: AtlasPlacement? = null,
	val source: SourceLayerRef? = null,
	val pinned: Boolean = false,
	val replaces: AtlasTileId? = null,
)

/** The separator between a reloaded tile's root id and its revision number. */
private const val TILE_REVISION_SEPARATOR = '~'

/**
 * The id this tile's lineage started from: the id before any `~<n>` revision suffix, or the id itself
 * for a tile born at import.  Readable from the id alone, so a consumer holding only the id (the CMO3
 * export's model-image web) needs no tile lookup.
 */
val AtlasTileId.lineageRoot: AtlasTileId
	get() {
		val separator = raw.lastIndexOf(TILE_REVISION_SEPARATOR)
		if (separator <= 0 || raw.substring(separator + 1).toIntOrNull() == null) {
			return this
		}
		return AtlasTileId(raw.substring(0, separator))
	}

/**
 * The revision a tile id carries: 0 for a tile born at import, n for the n-th reload of its lineage.
 */
val AtlasTileId.revision: Int
	get() {
		val separator = raw.lastIndexOf(TILE_REVISION_SEPARATOR)
		if (separator <= 0) {
			return 0
		}
		return raw.substring(separator + 1).toIntOrNull() ?: 0
	}

/**
 * The id the next reload of [previous]'s lineage mints: the lineage root with the revision after
 * [previous]'s, skipping any id [taken] already holds so a lineage can never collide with a tile the
 * document happens to name that way.
 *
 * @param AtlasTileId previous The tile being superseded.
 * @param Set         taken    Every tile id the document has.
 * @return AtlasTileId The new tile's id.
 */
fun reloadTileId(previous: AtlasTileId, taken: Set<AtlasTileId>): AtlasTileId {
	val root = previous.lineageRoot
	var revision = previous.revision + 1
	while (true) {
		val candidate = AtlasTileId("${root.raw}$TILE_REVISION_SEPARATOR$revision")
		if (candidate !in taken) {
			return candidate
		}
		revision++
	}
}

/**
 * The policy the atlas's pages are composed under: which alpha counts as opaque when a tile is
 * trimmed to its content, and how far each tile's edge color is replicated into the gutter around it.
 *
 * Model state because the pages are DERIVED state.  A placement records only where a tile's art
 * sits, not which opaque sub-rectangle the packer trimmed it to or how wide a band it extruded, so a
 * derivation re-runs both on the same pixels and must do so under the policy the pack used - held
 * here, beside the placements, so an undo across a repack that changed the threshold re-derives the
 * pages that repack composed rather than the current default's.  Options that only decide WHERE
 * tiles land (gutter, rotation, page shape) leave no trace here: the placements are their record.
 *
 * The defaults match the packer's own defaults; this module cannot see the packer, so the two are
 * kept equal by the derivation gate that composes a pack's pages back from a lowered model.
 *
 * @property Int alphaThreshold Minimum alpha byte (1..255) for a pixel to count as opaque when trimming.
 * @property Int extrude        How many pixels of each tile's edge color are replicated outward.
 */
data class AtlasComposition(
	val alphaThreshold: Int = 1,
	val extrude: Int = 2,
) {
	init {
		require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
		require(extrude >= 0) { "extrude must be non-negative: $extrude" }
	}

	companion object {
		/** The policy of an atlas nothing has repacked: the packer's defaults. */
		val Default: AtlasComposition = AtlasComposition()
	}
}

/**
 * A document's whole atlas: its pages and the tiles packed onto them.
 *
 * Empty for a document with no source art at all - a MOC3-origin rig, whose pages are the packed
 * endpoint of someone else's pipeline and whose tiles were never separable.
 *
 * @property List<AtlasPage> pages The packed pages, in the document's own page order.
 * @property List<AtlasTile> tiles Every piece of source art, in document order.
 * @property Boolean storedUvsAddressPages Whether the drawables' stored texture coordinates address the
 *   packed PAGES rather than the art itself.
 *
 *   A fact about the document, fixed when it was read, and deliberately NOT the display mode: a rigger
 *   toggling between the artwork and the atlas changes which texture is sampled, never what the stored
 *   coordinates mean.  False for a document saved sampling its per-layer rasters - a packed atlas can
 *   sit beside it untouched - where the coordinates already address the art and inverting a placement
 *   over them would throw every mesh clear of its artwork.
 * @property AtlasComposition composition The trim and extrusion policy the pages derive under - the one
 *   the last repack packed with, or the default for an atlas nothing has repacked.
 */
data class PuppetAtlas(
	val pages: List<AtlasPage> = emptyList(),
	val tiles: List<AtlasTile> = emptyList(),
	val storedUvsAddressPages: Boolean = true,
	val composition: AtlasComposition = AtlasComposition.Default,
) {
	/** True when the document carries no source art at all. */
	val isEmpty: Boolean
		get() = pages.isEmpty() && tiles.isEmpty()

	/** Tiles by id, for the per-drawable resolution every uv derivation starts from. */
	val tileById: Map<AtlasTileId, AtlasTile> by lazy { tiles.associateBy { tile -> tile.id } }

	companion object {
		/** The atlas of a document with no source art. */
		val Empty: PuppetAtlas = PuppetAtlas()
	}
}