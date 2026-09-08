package org.umamo.render.eval

import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode

/**
 * A node of the resolved per-pose render plan: the back-to-front order WITH the isolated group
 * boundaries preserved.  A plain Grouped part needs no boundary (its children flatten into the
 * surrounding order, exactly one slot's worth) - only an ISOLATED group becomes a node, because
 * the renderer must know which span of drawables renders into a layer and composites back as one
 * unit.  Nesting is direct: a composite node's children may contain further composite nodes.
 */
sealed interface RenderPlanNode

/** A drawable drawn directly into the current render target. */
class RenderPlanDrawable(val id: DrawableId) : RenderPlanNode

/**
 * An isolated part's subtree: render [children] (back-to-front) into a layer, then composite that
 * layer back as one unit using [composite]'s blend modes/masks and the pose's blended channels
 * (PoseDeformInputs.partCompositeStates keyed by [partId]).
 */
class RenderPlanComposite(
	val partId: PartId,
	val composite: PartComposite,
	val children: List<RenderPlanNode>,
) : RenderPlanNode

/**
 * The per-pose render plan over the draw-order group tree: [renderOrder]'s hierarchical sort with
 * isolated group boundaries kept as [RenderPlanComposite] nodes.  Plain groups flatten away
 * exactly as before - `renderOrder` IS this plan flattened, so the two can never disagree.
 *
 * @param RenderGroup           root           The draw-order group tree root (`PuppetModel.renderRoot`).
 * @param Map<DrawableId,Float> drawOrder      Blended draw order per drawable (missing → the Cubism default).
 * @param Map<PartId,Float>     partDrawOrders Blended per-group part draw order (missing → the static value).
 * @return List<RenderPlanNode> The plan nodes, back-to-front.
 */
fun renderPlan(
	root: RenderGroup,
	drawOrder: Map<DrawableId, Float>,
	partDrawOrders: Map<PartId, Float> = emptyMap(),
): List<RenderPlanNode> {
	fun sortKey(node: RenderNode): Int =
		when (node) {
			is RenderDrawable -> ((drawOrder[node.id] ?: DEFAULT_DRAW_ORDER.toFloat()) + 0.001f).toInt()
			is RenderGroup -> node.partId?.let { partDrawOrders[it] }?.let { (it + 0.001f).toInt() } ?: node.drawOrder
		}

	fun emit(group: RenderGroup, into: ArrayList<RenderPlanNode>) {
		for (child in group.children.sortedBy(::sortKey)) {
			when (child) {
				is RenderDrawable -> into.add(RenderPlanDrawable(child.id))
				is RenderGroup -> {
					val composite = child.composite
					val partId = child.partId
					if (composite != null && partId != null) {
						val subtree = ArrayList<RenderPlanNode>()
						emit(child, subtree)
						into.add(RenderPlanComposite(partId, composite, subtree))
					} else {
						emit(child, into)
					}
				}
			}
		}
	}

	val nodes = ArrayList<RenderPlanNode>()
	emit(root, nodes)
	return nodes
}

/**
 * Flattens a render plan to its drawables in back-to-front order (composite boundaries dropped).
 *
 * @param List<RenderPlanNode> nodes The plan nodes.
 * @return List<DrawableId> The drawables, back-to-front.
 */
fun flattenRenderPlan(nodes: List<RenderPlanNode>): List<DrawableId> {
	val result = ArrayList<DrawableId>()

	fun walk(node: RenderPlanNode) {
		when (node) {
			is RenderPlanDrawable -> result.add(node.id)
			is RenderPlanComposite -> node.children.forEach(::walk)
		}
	}
	nodes.forEach(::walk)
	return result
}

/**
 * The back-to-front paint order for one pose, computed hierarchically over the draw-order group tree.
 * Within each [RenderGroup] the children are stably sorted by an int sort key (a [RenderDrawable] by
 * its own quantised draw order, a sub-[RenderGroup] by the part's draw order), then emitted depth-first.
 * So a drawable's draw order only sorts it among its group siblings, and a "Group by Draw Order" part
 * moves as a unit - a child can't escape the group's slot.
 *
 * A group-less model is one flat group of all drawables, so this reduces exactly to [paintOrder]'s global
 * sort.  Quantisation (`+0.001`→int) matches the Umamo C++ runtime and de-noises tied float draw orders
 * (see [paintOrder]).
 *
 * A sub-group's key is its part draw order, which can itself be parameter-driven ([partDrawOrders])
 * - blended per pose, quantised the same way; missing → the group's static [RenderGroup.drawOrder].
 * So whole groups swap front/back as their controlling parameters move.
 *
 * @param RenderGroup           root           The draw-order group tree root (`PuppetModel.renderRoot`).
 * @param Map<DrawableId,Float> drawOrder      Blended draw order per drawable (missing → the Cubism default).
 * @param Map<PartId,Float>     partDrawOrders Blended per-group part draw order (missing → the static value).
 * @return List<DrawableId> The drawables in back-to-front paint order.
 */
fun renderOrder(
	root: RenderGroup,
	drawOrder: Map<DrawableId, Float>,
	partDrawOrders: Map<PartId, Float> = emptyMap(),
): List<DrawableId> = flattenRenderPlan(renderPlan(root, drawOrder, partDrawOrders))

/**
 * The back-to-front paint order for one pose. [baseOrder] is the model's parts-tree (panel) drawable
 * order - already back-to-front when draw orders are equal - and this stably re-sorts it by the
 * per-drawable blended [drawOrder], the primary key. A higher draw order paints later (in front) across
 * the whole model regardless of part; equal draw orders keep [baseOrder]. This mirrors how the Cubism
 * editor stacks drawables (drawOrder first, the parts tree breaking ties).
 *
 * Quantise to int before sorting: (`drawDrawOrders[i] = (int)(0.001f + drawOrder)`).
 * Draw order is authored as an integer (0-1000, default 500); the multilinear keyform blend
 * `Σ wᵢ·orderᵢ` only lands near an integer (float-summation gives e.g. 499.99997 vs 500.00003), so
 * sorting the raw float reshuffles drawables that should be tied - a sub-ULP-noise reorder that makes a
 * coincident-order drawable (e.g. a masked head shadow at 500) flicker in front of the face at the
 * parameter values where the noise crosses. Flooring `+0.001` collapses the noise to a true tie so the
 * stable sort preserves [baseOrder]; the `+0.001` rescues a value sitting just below its intended integer.
 *
 * Because draw order rides on the keyform, it is param-dependent - pass the [DeformedGeometry.drawOrder]
 * for the current pose, so a parameter that animates stacking reorders correctly per frame.
 *
 * @param List<DrawableId>      baseOrder The model's drawables, in parts-tree back-to-front order.
 * @param Map<DrawableId,Float> drawOrder Blended draw order per drawable (missing → the Cubism default).
 * @return List<DrawableId> The drawables in back-to-front paint order.
 */
fun paintOrder(baseOrder: List<DrawableId>, drawOrder: Map<DrawableId, Float>): List<DrawableId> =
	baseOrder.sortedBy { ((drawOrder[it] ?: DEFAULT_DRAW_ORDER.toFloat()) + 0.001f).toInt() }