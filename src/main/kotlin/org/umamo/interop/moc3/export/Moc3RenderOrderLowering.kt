package org.umamo.interop.moc3.export

import org.umamo.format.moc3.model.RenderOrderChild
import org.umamo.format.moc3.model.RenderOrderGroup
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode

/**
 * Lowers the runtime's render tree into the MOC3's draw-group records.
 *
 * The shapes already correspond: a MOC3 draw group holds an ordered child list where each child is
 * either an art mesh or a nested group tagged with the part that supplies its depth, and
 * `RenderGroup` is that same thing.  Group 0 is the root by convention, and records are allocated
 * in the order the tree is walked so a parent always precedes its children.
 *
 * The format has one hard rule worth stating: EVERY art mesh must appear exactly once across the
 * whole tree.  A runtime that finds a drawable referenced twice, or not at all, is documented as
 * free to behave erratically - so the guarantee has to arrive with the tree rather than be checked
 * here: the render tree the import builds supplies it through its own append-the-missing safety
 * net, and this walk preserves that shape, skipping only a child whose drawable or part could not
 * be written at all.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal fun lowerRenderOrder(puppet: PuppetModel, plan: Moc3IndexPlan): List<RenderOrderGroup> {
	val groups = ArrayList<RenderOrderGroup>()

	/**
	 * Allocates a record for [group] and recursively for its sub-groups, returning its index.
	 *
	 * The record is reserved BEFORE its children are walked, so a nested group's own index is already
	 * assigned when its parent's child entry needs to name it.
	 *
	 * @param RenderGroup group The subtree root.
	 * @return Int The allocated record index.
	 */
	fun allocate(group: RenderGroup): Int {
		val recordIndex = groups.size
		// Placeholder, replaced below: the slot must exist before the children can reference it.
		groups.add(RenderOrderGroup(emptyList()))
		val children = ArrayList<RenderOrderChild>(group.children.size)
		for (child: RenderNode in group.children) {
			when (child) {
				is RenderDrawable -> {
					val drawableIndex = plan.drawableIndex(child.id)
					if (drawableIndex >= 0) {
						// MOC3 §5.6 render-order child kind 0: an art-mesh leaf.  groupIndex is unused for a leaf.
						children.add(RenderOrderChild(kind = 0, index = drawableIndex, groupIndex = 0))
					}
				}
				is RenderGroup -> {
					val partIndex = plan.partIndex(child.partId)
					// The part check comes BEFORE the record is allocated.  Allocating first and then
					// declining to reference it leaves a group in the table that no child names - an
					// unreachable record still counted by CI_RENDER_ORDER_GROUPS and still consuming a slot
					// in the child-start prefix sum.
					//
					// Skipping the record takes its whole subtree with it, which is only sound because the
					// sole reason a part is unwritable is that it is a sketch, and the export drops every
					// drawable under a sketch part too.  A future rule that drops a part while KEEPING its
					// drawables would strand those art meshes outside the render tree, which the format
					// says leaves the runtime free to behave erratically.
					if (partIndex >= 0) {
						val childRecord = allocate(child)
						// Kind 1: a "group by draw order" part, whose members live in the named record.
						children.add(RenderOrderChild(kind = 1, index = partIndex, groupIndex = childRecord))
					}
				}
			}
		}
		groups[recordIndex] = RenderOrderGroup(children)
		return recordIndex
	}

	allocate(puppet.renderRoot)
	return groups
}