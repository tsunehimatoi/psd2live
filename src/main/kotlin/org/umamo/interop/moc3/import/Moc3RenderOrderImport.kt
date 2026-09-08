package org.umamo.interop.moc3.import

import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode

/**
 * Builds the runtime render-order tree from the moc's explicit render-order groups (group 0 is the
 * root), or null when the document carries none (degenerate; the caller derives from the org tree).
 *
 * A kind-0 child is a drawable leaf; a kind-1 child is a "Group by Draw Order" part whose sub-group
 * record is [org.umamo.format.moc3.model.RenderOrderChild.groupIndex].  A visited set guards a
 * malformed cyclic group reference, and drawables the stored tree never places are appended at the
 * root (the renderer draws exclusively from this tree, so a missing leaf would never render).
 *
 * Runs BEFORE the part import, and reads the same part attributes it does - a grouped part's draw
 * order, tracks, and composite appear on both its render group and its runtime `Part` - which is why
 * those live in `Moc3PartImport` as plain functions over the context rather than being computed here.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return RenderGroup? The render root, or null when the moc has no render-order groups.
 */
internal fun importRenderRoot(context: Moc3ImportContext): RenderGroup? {
	val mocDocument = context.mocDocument
	if (mocDocument.renderOrderGroups.isEmpty()) {
		return null
	}
	val visitedGroups = HashSet<Int>()

	fun childrenOf(groupIndex: Int): List<RenderNode> {
		val group = mocDocument.renderOrderGroups.getOrNull(groupIndex) ?: return emptyList()
		if (!visitedGroups.add(groupIndex)) {
			return emptyList()
		}
		return group.children.mapNotNull { child ->
			when (child.kind) {
				// MOC3 §5.6 render-order child kind 0: a drawable leaf.
				0 -> context.drawableIdsByFileIndex.getOrNull(child.index)?.let(::RenderDrawable)
				// Kind 1: a draw-order group part; its members live in the referenced sub-group record.
				1 -> {
					val part = mocDocument.parts.getOrNull(child.index) ?: return@mapNotNull null
					val partId = context.partIds.getOrNull(child.index) ?: return@mapNotNull null
					RenderGroup(
						partId = partId,
						drawOrder = partStaticDrawOrder(context, part),
						children = childrenOf(child.groupIndex),
						channelGrids = partChannelsOf(context, part, child.index),
						composite = partCompositeOf(context, part, child.index),
					)
				}

				else -> null
			}
		}
	}

	val root = RenderGroup(null, DEFAULT_DRAW_ORDER, childrenOf(0))
	// Safety net, mirroring deriveRenderRoot's: the renderer draws EXCLUSIVELY from this tree, so
	// a drawable the stored groups never place (out-of-range child index, an unknown future child
	// kind, or a truncated tree) would silently never render.  Append the missing leaves at the
	// root, where they sort by their own draw order like any other root-level drawable.
	val placedLeaves = ArrayList<DrawableId>()
	collectRenderLeaves(root, placedLeaves)
	val placedDrawableIds = placedLeaves.toHashSet()
	val missingLeaves =
		context.drawableIdsByFileIndex.filter { drawableId -> drawableId !in placedDrawableIds }.map(::RenderDrawable)
	return if (missingLeaves.isEmpty()) {
		root
	} else {
		root.copy(children = root.children + missingLeaves)
	}
}

/**
 * Reconstructs each drawable's panel index from [renderRoot].
 *
 * Panel order (top = front) is not stored in moc3.  Render order is back-to-front, so the REVERSED
 * leaf sequence is the panel order.  A null root - a document with no render-order groups - yields an
 * empty map, and every reader then falls back to file order.
 *
 * @param RenderGroup? renderRoot The render tree, or null when the moc carried none.
 * @return Map<DrawableId, Int> Panel index per placed drawable, front-most at 0.
 */
internal fun panelIndexesFrom(renderRoot: RenderGroup?): Map<DrawableId, Int> =
	buildMap {
		if (renderRoot != null) {
			val leaves = ArrayList<DrawableId>()
			collectRenderLeaves(renderRoot, leaves)
			leaves.asReversed().forEachIndexed { panelIndex, drawableId ->
				if (drawableId !in this) {
					put(drawableId, panelIndex)
				}
			}
		}
	}

/**
 * Collects a render tree's drawable leaves depth-first into [into] (back-to-front order).
 *
 * @param RenderNode            node The subtree root.
 * @param ArrayList<DrawableId> into The destination leaf list.
 * @return Unit
 */
private fun collectRenderLeaves(
	node: RenderNode,
	into: ArrayList<DrawableId>,
) {
	when (node) {
		is RenderDrawable -> into.add(node.id)
		is RenderGroup -> node.children.forEach { child -> collectRenderLeaves(child, into) }
	}
}