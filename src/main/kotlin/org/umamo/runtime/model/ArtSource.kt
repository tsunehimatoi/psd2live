package org.umamo.runtime.model

/*
 * Where a document's art came from: the artwork files it was imported from, each with the layer
 * inventory it had at that import, and the per-tile link back to one of those layers.
 *
 * Model state because re-import is a model operation: the reconcile diffs a re-read file against
 * the inventory recorded here and rebinds tiles through the refs, so both have to survive undo, a
 * document swap, and the native format.  Pixels are deliberately absent - a tile's raster belongs to
 * the document, and the source file itself is linked, never embedded.
 */

/**
 * One artwork file the document was imported from, and the layers it had when it was last read.
 *
 * The inventory is the re-import baseline: a later read of the same file is diffed against it to
 * classify each layer as matched, added, removed, or renamed.  It records identity and layout only,
 * never pixels, so a document with hundreds of layers carries it for free.
 *
 * @property ArtSourceId          id     The document-local identity every [SourceLayerRef] points at.
 * @property String               name   The file's display name (its file name at import).
 * @property String?              path   The advisory external path the file was read from, or null when
 *   unknown or platform-volatile; a missing file only stalls re-import, never the rig.
 * @property String               format The source format's file extension ("psd", "clip", "kra", or a
 *   flat raster's), recorded so a listing can say what a file is without re-reading it.
 * @property List<ArtSourceLayer> layers The layer inventory as of the last import, in the file's own
 *   draw order (top-most first); empty for a source whose inventory was never walked.
 * @property String?              contentHash The whole-file content hash (SHA-256 hex) of the bytes the
 *   document last read for this file, or null when it never read bytes (a CMO3-origin source).  What
 *   the watcher compares a save against, so an unchanged file is never re-read.
 * @property Long?                lastModified The file's modification time (epoch milliseconds) when the
 *   document last read it - or, for a CMO3-origin source, when the official editor did - or null when
 *   neither is known.  The stale-at-open check falls back to it where no hash was recorded, since a
 *   CMO3 keeps the time but not a hash.
 */
data class ArtSource(
	val id: ArtSourceId,
	val name: String,
	val path: String?,
	val format: String,
	val layers: List<ArtSourceLayer> = emptyList(),
	val contentHash: String? = null,
	val lastModified: Long? = null,
)

/**
 * One layer of an [ArtSource]'s inventory: what the reader knew about it at import, without pixels.
 *
 * Skipped layers (non-raster, empty) are listed too, so a re-import can tell "this layer was there
 * and unusable" from "this layer is new".
 *
 * @property String  key       The reader's layer key - the same string a [SourceLayerRef] carries.
 * @property String  name      The layer's name at import.
 * @property String  groupPath The slash-joined enclosing-folder path at import ("" at the root).
 * @property Int     left      The layer's canvas position, top-left origin in source pixels.
 * @property Int     top       See [left].
 * @property Int     width     The layer's raster width in source pixels.
 * @property Int     height    The layer's raster height in source pixels.
 * @property Boolean visible   The layer's own eye toggle at import.
 * @property Boolean present   Whether the file still had this layer at the last read.  False keeps the row
 *   for a layer the file lost while a tile still binds it, so the Sources space can name and size it and
 *   the matcher can score candidates against it; such a row leaves the inventory once nothing binds it.
 * @property String? contentHash The content hash (SHA-256 hex) of the layer's pixels at the last read, or
 *   null where the art was never decoded (a CMO3's decomposed tree).  A renamed layer whose pixels did
 *   not change is recognized by it outright.
 * @property Boolean empty     Whether the layer had no pixel with any alpha at the last read - erased to
 *   nothing rather than deleted.  A tile bound to such a layer keeps its art and reads as needing review,
 *   since the artist may have meant either; false where the art was never decoded.
 * @property Boolean replaced  Meaningful only while [present] is false: the row was lost because Replace
 *   Artwork repointed the file at art that mints other keys, not because the layer left the file - so the
 *   row can say which.  A row lost before the replace keeps false.
 * @property Boolean ignored   Whether a reload leaves this layer out of the rig: a present layer no tile
 *   binds that the rigger keeps in the file but not in the model (a sketch, a reference), marked from its
 *   Sources row or by Delete Art under the import setting, and carried across every refresh until the row
 *   clears it.  Meaningless while a tile binds the key.
 */
data class ArtSourceLayer(
	val key: String,
	val name: String,
	val groupPath: String,
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
	val visible: Boolean,
	val present: Boolean = true,
	val contentHash: String? = null,
	val empty: Boolean = false,
	val replaced: Boolean = false,
	val ignored: Boolean = false,
)

/**
 * A tile's link to the source layer its art came from: which file, and which layer within it.
 *
 * The key is whatever the reader minted for the layer and is opaque here: a CLIP layer uuid, a Krita
 * layer uuid, a PSD lyid, or a PSD name-plus-order fallback.  [stableKey] says which of those it is
 * in the one way that matters - whether the key survives a rename or reorder in the art program - so
 * the reconcile knows when to trust it outright and a listing knows what to show.
 *
 * @property ArtSourceId sourceId  The [ArtSource] the layer belongs to.
 * @property String      layerKey  The reader's key for the layer within that source.
 * @property Boolean     stableKey Whether the key is a format-minted id (true) or a name-and-order
 *   fallback that only holds as long as the artist's layer organization does (false).
 */
data class SourceLayerRef(
	val sourceId: ArtSourceId,
	val layerKey: String,
	val stableKey: Boolean,
)

/**
 * Where a delta places one child among a container's existing children.
 */
sealed interface OrgSlot {
	/**
	 * Directly after [anchor], a child the container already has (or one the same delta placed before it).
	 *
	 * @property OrgChild anchor The child to follow.
	 */
	data class After(val anchor: OrgChild) : OrgSlot

	/**
	 * Directly before [anchor], a child the container already has.
	 *
	 * @property OrgChild anchor The child to precede.
	 */
	data class Before(val anchor: OrgChild) : OrgSlot

	/** After every child the container has. */
	data object End : OrgSlot
}

/**
 * One child a delta places inside the model's existing org tree: a reload found a layer in a folder
 * the document already keeps as a part (or at the root), and the file says where among that folder's
 * layers it sits, so the drawable lands there rather than at the bottom.  An anchor the container no
 * longer has places the child at the end.
 *
 * @property PartId?  container The part whose children it joins, or null for the root.
 * @property OrgChild child     The child: a drawable of the delta, or one of its new parts.
 * @property OrgSlot  slot      Where among the container's children it goes.
 */
data class OrgInsertion(
	val container: PartId?,
	val child: OrgChild,
	val slot: OrgSlot,
)

/**
 * What one artwork file adds to a model: the file's record, its tiles, the drawables and parts born
 * from its layers, and where they join the org tree - a fresh file's whole tree after the model's root
 * children, a listed file's new layers placed among the children the model already has.  A delta
 * rather than a model, so the same additions can be appended to a fresh model at open or to a document
 * already being rigged.
 *
 * Every id in here is already minted past the receiving model's (`ArtMesh<n>`, `Part<n>`, `art-<k>`),
 * and the new parts' children reference only ids in this delta.  Pixels are absent - they travel
 * beside it to the document's raster store.
 *
 * @property ArtSource       source       The file and its layer inventory.
 * @property List<AtlasTile> tiles        One unplaced tile per imported layer, bound to that layer.
 * @property List<Drawable>  drawables    One drawable per tile, over its birth mesh.
 * @property List<Part>      parts        One NEW part per folder the model has no part for, nested by the
 *   parts' own children.
 * @property List<OrgChild>  rootChildren A fresh file's top-level order, appended after the model's own;
 *   empty for a listed file, whose new children are [insertions].
 * @property List            insertions   A listed file's new children placed among the existing ones - at
 *   the root or inside a part the document already keeps for the folder - in the order the file gives
 *   them, each anchored to its nearest sibling in the file; applied in list order, so a child may anchor
 *   on one placed before it.  Empty for a fresh file, whose folders are all new.
 */
data class ArtworkAdditions(
	val source: ArtSource,
	val tiles: List<AtlasTile>,
	val drawables: List<Drawable>,
	val parts: List<Part>,
	val rootChildren: List<OrgChild>,
	val insertions: List<OrgInsertion> = emptyList(),
)

/**
 * One tile a reload supersedes: the id of the tile the art used to live in, and the tile that holds it
 * now - a fresh id (`<root>~<n>`), the new size, no placement yet, the old pin, and [AtlasTile.replaces]
 * naming the old one.
 *
 * @property AtlasTileId oldId The superseded tile's id; its pixels stay in the document's raster store.
 * @property AtlasTile   tile  The replacement, unplaced.
 */
data class ReplacedTile(
	val oldId: AtlasTileId,
	val tile: AtlasTile,
)

/**
 * What re-reading one artwork file changes in a model: the file's record with its NEW inventory, the
 * tiles whose art changed (each superseded by a fresh, unplaced tile), the meshes the drawables over
 * them take (an untouched birth quad re-born over the new art, an edited mesh carried with its
 * coordinates remapped so every vertex samples the same canvas pixel as before), the layers the file
 * gained, the drawables whose edited mesh the new opaque art now reaches past, and the drawables whose
 * visibility follows the file's eye toggle.
 *
 * A delta rather than a model, like [ArtworkAdditions]: the same plan applies to the live model and
 * to the operation strip's rerun over its base.  Pixels travel beside it to the raster store.  A layer
 * the file lost is deliberately absent - its tile keeps its art and its binding, and the new inventory
 * carries its row flagged not present ([ArtSourceLayer.present]), which is what the Sources space shows
 * as needing review.
 *
 * @property ArtSource         source          The file's record, carrying the inventory as just read.
 * @property List              replacedTiles   The tiles whose art changed, each with its replacement.
 * @property Map               drawableMeshes  The mesh each affected drawable takes, keyed by drawable;
 *   texture coordinates in the ART frame of the drawable's new tile, which the pack that follows
 *   converts exactly as it does an import's.
 * @property ArtworkAdditions? additions       The layers the file gained, minted under [source], or null.
 * @property List<DrawableId>  outgrown        Drawables whose kept mesh no longer covers the new opaque art.
 * @property List<AtlasTileId> retiredTiles    Tiles a rebinding made redundant: a lost layer's rig work took
 *   the layer a reload had minted a fresh, untouched drawable for, so that drawable and its tile go (the
 *   pixels stay in the raster store for undo).  Never a replaced tile; a part minted for the drawable stays.
 * @property Map               drawableVisibility The visibility each drawable takes, keyed by drawable, where
 *   the file's eye toggle changed since the last read and the drawable still showed the OLD state; a
 *   drawable the rigger toggled since is absent, since that toggle is rig work.
 */
data class ArtworkReload(
	val source: ArtSource,
	val replacedTiles: List<ReplacedTile>,
	val drawableMeshes: Map<DrawableId, DrawableMesh>,
	val additions: ArtworkAdditions?,
	val outgrown: List<DrawableId>,
	val retiredTiles: List<AtlasTileId> = emptyList(),
	val drawableVisibility: Map<DrawableId, Boolean> = emptyMap(),
)