package org.umamo.format.clip

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind

/**
 * Document geometry plus the id of the root folder the layer walk descends from.
 *
 * Decoupled from the SQLDelight-generated row types so the tree logic is pure and testable; the
 * reader maps the generated Canvas row into this.
 */
internal data class ClipCanvas(
	val widthPx: Int,
	val heightPx: Int,
	val rootFolderId: Long,
)

/**
 * One CLIP Layer row, reduced to the fields the neutral model needs and with nulls already
 * defaulted.  The reader maps each SQLDelight-generated row into this shape.
 */
internal data class ClipLayerRow(
	val mainId: Long,
	val name: String,
	val folder: Long,
	val type: Long,
	val visibility: Long,
	val opacity: Long,
	val composite: Long,
	val clip: Long,
	val offsetX: Long,
	val offsetY: Long,
	val firstChildIndex: Long,
	val nextIndex: Long,
	val uuid: String?,
	// Tile-grid anchor = (offsetX + renderOffscrX, offsetY + renderOffscrY); canvas-aligned (0 in corpus).
	val renderOffscrX: Long,
	val renderOffscrY: Long,
)

// CLIP: Layer.LayerFolder bit 0x10 marks a folder/group node.
private const val LAYER_FOLDER_BIT = 0x10L

// CLIP: Layer.LayerOpacity is a 0..256 fixed-point scale (256 == fully opaque), not 0..255.
private const val OPACITY_FULL = 256f

// CLIP: Layer.LayerVisibility is a bitfield; bit 0 is "shown" (values 1 and 3 are visible, 2 hidden -
// verified by several samples).  The other bit is an unrelated sub-flag.
private const val VISIBILITY_SHOWN_BIT = 1L

// CLIP: Layer.LayerComposite == 30 is the folder "Through" (pass-through, non-isolating) mode - the
// default for a new folder.  Only meaningful on folder rows; observed in several samples.
private const val COMPOSITE_THROUGH = 30L

// CLIP: Layer.LayerType - verified across the corpus (see docs/CLIP.md §5).  1 and 3 are raster
// layers (pixels persist); 0 is an object layer (vector/text, distinguished by TextLayerType); 1584
// is the Paper/fill layer; 4098 is a procedural adjustment/correction layer.  None of 0/1584/4098
// carry a persisted raster.
private const val LAYER_TYPE_RASTER_PLAIN = 1L
private const val LAYER_TYPE_RASTER_VARIANT = 3L
private const val LAYER_TYPE_OBJECT = 0L
private const val LAYER_TYPE_FILL = 1584L
private const val LAYER_TYPE_ADJUSTMENT = 4098L

/**
 * Classifies a leaf layer row into a neutral [SourceLayerKind].
 *
 * @param ClipLayerRow row        The leaf layer row.
 * @param Set textLayerIds        MainIds of text object layers (empty on schemas without TextLayerType).
 * @return SourceLayerKind The layer kind (Raster unless it is an object/fill/adjustment layer).
 */
private fun layerKindOf(row: ClipLayerRow, textLayerIds: Set<Long>): SourceLayerKind =
	when (row.type) {
		LAYER_TYPE_RASTER_PLAIN, LAYER_TYPE_RASTER_VARIANT -> SourceLayerKind.Raster
		// CLIP: a text layer is in textLayerIds (TextLayerType set); other object layers are vector.
		LAYER_TYPE_OBJECT -> if (row.mainId in textLayerIds) SourceLayerKind.Text else SourceLayerKind.Vector
		LAYER_TYPE_FILL -> SourceLayerKind.Fill
		LAYER_TYPE_ADJUSTMENT -> SourceLayerKind.Adjustment
		else -> SourceLayerKind.Unknown
	}

/**
 * Builds the neutral, ordered [SourceLayer] list from the CLIP layer table.
 *
 * EN: The layer tree is encoded as MainId links: a node's LayerFirstChildIndex points at its first
 *     child and LayerNextIndex at its next sibling (0 terminates each chain).  We descend from
 *     Canvas.CanvasRootFolder (a synthetic root that is never emitted) and flatten depth-first.  The
 *     firstChild/nextSibling walk visits the bottom-most layer first (verified: the root's first
 *     child in the corpus is "Paper", Clip Studio's default background), so the produced list is
 *     bottom-to-top - the painter's-algorithm order the compositor expects, matching the KRA reader.
 *     Folders contribute only their name to the slash-joined groupPath; only leaf layers are emitted.
 * JA: MainId のリンク（FirstChild/NextSibling）でツリーを深さ優先に平坦化し, 下から上の順で葉のみ出力する.
 *
 * @param ClipCanvas canvas        The document geometry and root-folder id.
 * @param List rows                All layer rows (in any order; indexed internally by MainId).
 * @param Map rastersByMainId      Decoded rasters keyed by layer MainId; absent => placeholder.
 * @param Set textLayerIds         MainIds of text object layers (for kind classification).
 * @return ClipTree The leaf layers (bottom-to-top, order = top-most-first) and the folder groups.
 */
internal fun buildSourceTree(
	canvas: ClipCanvas,
	rows: List<ClipLayerRow>,
	rastersByMainId: Map<Long, ClipDecodedRaster>,
	textLayerIds: Set<Long>,
): ClipTree {
	val rowsByMainId = rows.associateBy { row -> row.mainId }
	val leavesBottomToTop = ArrayList<ClipLeaf>()
	val groups = ArrayList<SourceGroup>()

	val rootRow = rowsByMainId[canvas.rootFolderId]
	if (rootRow != null) {
		collectTree(rootRow.firstChildIndex, rowsByMainId, parentPath = "", visited = HashSet(), outLeaves = leavesBottomToTop, outGroups = groups)
	}

	// SourceLayer.order is top-most-first (0 == topmost); the list itself stays bottom-to-top, so the
	// top-most leaf is the last collected.  Convert the bottom-up index into a top-down order value.
	val lastIndex = leavesBottomToTop.size - 1
	val layers =
		leavesBottomToTop.mapIndexed { indexFromBottom, leaf ->
			toSourceLayer(
				row = leaf.row,
				groupPath = leaf.groupPath,
				order = lastIndex - indexFromBottom,
				raster = rastersByMainId[leaf.row.mainId],
				textLayerIds = textLayerIds,
			)
		}
	return ClipTree(layers = layers, groups = groups)
}

/** The flattened result of a layer-tree walk: the leaf layers and the folders that enclose them. */
internal class ClipTree(val layers: List<SourceLayer>, val groups: List<SourceGroup>)

/** A leaf layer paired with the slash-joined path of the folders enclosing it. */
private class ClipLeaf(val row: ClipLayerRow, val groupPath: String)

/**
 * Walks a sibling chain starting at [startMainId], descending into folders.  Leaf layers are
 * appended to [outLeaves] in document (bottom-to-top) order; each folder is recorded in [outGroups].
 *
 * @param Long startMainId            MainId of the first sibling (0 terminates immediately).
 * @param Map rowsByMainId            All rows indexed by MainId.
 * @param String parentPath           Slash-joined names of the enclosing folders (empty at root).
 * @param MutableSet visited          Guards against a malformed cyclic link list.
 * @param MutableList outLeaves       Accumulates leaves in walk order.
 * @param MutableList outGroups       Accumulates folder groups.
 */
private fun collectTree(
	startMainId: Long,
	rowsByMainId: Map<Long, ClipLayerRow>,
	parentPath: String,
	visited: MutableSet<Long>,
	outLeaves: MutableList<ClipLeaf>,
	outGroups: MutableList<SourceGroup>,
) {
	var currentMainId = startMainId
	while (currentMainId != 0L) {
		if (!visited.add(currentMainId)) {
			// A cyclic LayerNextIndex would otherwise loop forever; stop defensively.
			break
		}
		val row = rowsByMainId[currentMainId] ?: break
		// CLIP: a node is a group if the folder bit is set; treat "has children" as a group too, so a
		// folder whose bit we misread still recurses rather than being emitted as a bogus drawable.
		val isGroup = (row.folder and LAYER_FOLDER_BIT) != 0L || row.firstChildIndex != 0L
		if (isGroup) {
			val childPath = if (parentPath.isEmpty()) row.name else "$parentPath/${row.name}"
			outGroups.add(toSourceGroup(row, childPath))
			collectTree(row.firstChildIndex, rowsByMainId, childPath, visited, outLeaves, outGroups)
		} else {
			outLeaves.add(ClipLeaf(row, parentPath))
		}
		currentMainId = row.nextIndex
	}
}

/**
 * Converts one folder row into a neutral [SourceGroup].
 *
 * @param ClipLayerRow row    The folder layer row.
 * @param String path         The slash-joined folder path (including this folder's name).
 * @return SourceGroup The neutral folder descriptor.
 */
private fun toSourceGroup(row: ClipLayerRow, path: String): SourceGroup =
	ClipSourceGroup(
		path = path,
		name = row.name,
		visible = (row.visibility and VISIBILITY_SHOWN_BIT) != 0L,
		opacity = (row.opacity.toFloat() / OPACITY_FULL).coerceIn(0f, 1f),
		clipped = row.clip != 0L,
		// CLIP: a folder's blend lives in LayerComposite, same codes as a layer; 30 is "Through".
		blend = ClipBlend.fromCompositeCode(row.composite),
		passThrough = row.composite == COMPOSITE_THROUGH,
	)

/**
 * Converts one leaf CLIP layer into a neutral [SourceLayer], using its decoded raster when present
 * and a transparent 1x1 placeholder otherwise (adjustment/procedural layers carry no own pixels).
 *
 * @param ClipLayerRow row            The leaf layer row.
 * @param String groupPath            The slash-joined enclosing-folder path.
 * @param Int order                   Draw order, top-most == 0.
 * @param ClipDecodedRaster? raster   The decoded raster, or null for a transparent placeholder.
 * @param Set textLayerIds            MainIds of text object layers (for kind classification).
 * @return SourceLayer The neutral layer.
 */
private fun toSourceLayer(row: ClipLayerRow, groupPath: String, order: Int, raster: ClipDecodedRaster?, textLayerIds: Set<Long>): SourceLayer {
	// CLIP: LayerUuid is rename/reorder-stable - the strongest re-import key; fall back to the
	// numeric MainId only if a layer somehow lacks a uuid.
	val stableId = row.uuid?.takeIf { uuid -> uuid.isNotBlank() } ?: row.mainId.toString()
	// Decoded raster supplies real bounds; otherwise a 1x1 transparent placeholder at the layer
	// offset keeps downstream dimensions valid (mirrors the KRA reader's empty-layer placeholder).
	val bounds = raster?.bounds ?: LayerBounds(left = row.offsetX.toInt(), top = row.offsetY.toInt(), width = 1, height = 1)
	val pixels = raster?.raster ?: LayerRaster(width = 1, height = 1, rgba = ByteArray(4))
	return ClipSourceLayer(
		id = LayerId(stableId),
		name = row.name,
		kind = layerKindOf(row, textLayerIds),
		visible = (row.visibility and VISIBILITY_SHOWN_BIT) != 0L,
		groupPath = groupPath,
		order = order,
		bounds = bounds,
		opacity = (row.opacity.toFloat() / OPACITY_FULL).coerceIn(0f, 1f),
		clipped = row.clip != 0L, // CLIP: Layer.LayerClip non-zero == clip to the layer below.
		blend = ClipBlend.fromCompositeCode(row.composite),
		raster = pixels,
	)
}

/** Concrete [SourceLayer] backing a parsed CLIP layer. */
private data class ClipSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val kind: SourceLayerKind,
	override val visible: Boolean,
	override val groupPath: String,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val raster: LayerRaster,
) : SourceLayer

/** Concrete [SourceGroup] backing a parsed CLIP folder. */
private data class ClipSourceGroup(
	override val path: String,
	override val name: String,
	override val visible: Boolean,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val passThrough: Boolean,
) : SourceGroup

/** Concrete [SourceArt] backing a parsed CLIP document. */
data class ClipSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt