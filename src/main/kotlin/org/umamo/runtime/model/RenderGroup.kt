package org.umamo.runtime.model

/**
 * A node in the render-order tree - Cubism's draw-order group hierarchy. The model carries this tree so the
 * renderer can reproduce the  hierarchical render order: a drawable's draw order sorts it only
 * among its group siblings, and a "Group by Draw Order" part moves as a unit positioned by the part's
 * draw order. A model with no draw-order groups is one flat [RenderGroup] of all drawables - equivalent to a
 * global sort (what `paintOrder` does).
 */
sealed interface RenderNode

/** A drawable leaf; its sort key is its own pose-blended draw order, quantised to an int. */
data class RenderDrawable(val id: DrawableId) : RenderNode

/**
 * A draw-order group: the implicit root, or a part whose [PartGroupMode] is Grouped or Isolated. Its
 * [children] sort among themselves (in [drawOrder]-then-panel order); the group as a whole takes [drawOrder]
 * (the part's draw order) as its sort key in its parent, so a child can't escape the group's slot.
 * Basically, override the draw order of children of the part.
 *
 * @param PartId?          partId    The owning part, or null for the implicit root.
 * @param Int              drawOrder The part's default-pose draw order - the static fallback sort key.
 * @param List<RenderNode> children  The group's members, in parts-panel (authoring) order.
 * @param ChannelGrids     channelGrids The owning part's per-channel keyform tracks, copied from the
 *                                   part onto the derived render tree.  DRAW_ORDER blends per pose
 *                                   into the sort key; OPACITY and the color channels feed an
 *                                   isolated part's composite.  A channel with no track falls back
 *                                   to [drawOrder] / [composite].
 * @param PartComposite?   composite The owning part's compositing settings, or null when the part
 *                                   is not isolated - carried on the render tree so the renderer
 *                                   can composite the group's subtree as one layer.
 */
data class RenderGroup(
	val partId: PartId?,
	val drawOrder: Int,
	val children: List<RenderNode>,
	val channelGrids: ChannelGrids = ChannelGrids.Empty,
	val composite: PartComposite? = null,
) : RenderNode