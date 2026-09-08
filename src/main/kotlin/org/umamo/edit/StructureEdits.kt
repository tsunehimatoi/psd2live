package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.partSelfAndDescendants
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Organizational-tree structure edits. The org tree (PuppetModel.rootChildren + Part.children - an ordered
 * mix of sub-parts and drawables) is the single source of truth for the hierarchy AND the parts-panel
 * order, and that order is the draw-order tiebreak. So one move - withOrgChildMoved - covers reparenting a
 * part, re-homing a drawable, reordering siblings, and interleaving a drawable between parts; every move
 * re-derives the render order from the tree. Deformer moves are separate (deformers live in the Armature,
 * not the org tree).
 *
 * 組織ツリーの構造編集。ツリーが階層とパネル順の唯一の真実で、描画順のタイブレークでもある。移動は1つに統合。
 */

/** True when [child] is present somewhere in the org tree (the root level or some part's children). */
private fun PuppetModel.containsOrgChild(child: OrgChild): Boolean =
	child in rootChildren || parts.any { child in it.children }

/** This child list with [child] inserted before [before] (or appended when [before] is null or absent). */
private fun List<OrgChild>.withChildInsertedBefore(child: OrgChild, before: OrgChild?): List<OrgChild> {
	val result = toMutableList()
	val insertAt = before?.let { result.indexOf(it) }?.takeIf { it >= 0 } ?: result.size
	result.add(insertAt, child)
	return result
}

/**
 * Returns a copy of [this] with the org child [child] (a sub-part or a drawable) moved under [newParentId]
 * (null = the top level) and positioned before [before] there (null / absent = appended). This is the one
 * organizational move: a part reparent / reorder, a drawable re-home, and a cross-kind interleave (a
 * drawable dropped between two parts) are the same edit. Because the parts-panel order is the draw-order
 * tiebreak, the render order is re-derived. A move that would drop a part inside its own subtree (a cycle),
 * or that changes nothing, returns the same instance.
 *
 * @param OrgChild child The sub-part or drawable to move.
 * @param PartId? newParentId The destination part, or null for the top level.
 * @param OrgChild? before The sibling to insert before, or null to append at the destination's end.
 * @return PuppetModel The model with the child moved, or [this] if the move was a no-op or illegal.
 */
fun PuppetModel.withOrgChildMoved(child: OrgChild, newParentId: PartId?, before: OrgChild?): PuppetModel {
	if (!containsOrgChild(child)) {
		return this
	}
	// The subtree set contains the part itself, so this rejects self-parenting and any deeper cycle at once.
	// The Properties picker filters its candidates against the same query, so an illegal target is not
	// offered there rather than being silently refused here.
	if (child is OrgChild.Part && newParentId != null && newParentId in partSelfAndDescendants(child.id)) {
		return this
	}
	// Detach from wherever it currently sits (the root level and / or some part's children).
	val detachedRoot = rootChildren.filter { it != child }
	val detachedParts = parts.map { part -> if (child in part.children) part.copy(children = part.children - child) else part }
	val newRoot: List<OrgChild>
	val newParts: List<Part>
	if (newParentId == null) {
		newRoot = detachedRoot.withChildInsertedBefore(child, before)
		newParts = detachedParts
	} else {
		newRoot = detachedRoot
		newParts =
			detachedParts.map { part ->
				if (part.id == newParentId) part.copy(children = part.children.withChildInsertedBefore(child, before)) else part
			}
	}
	if (newRoot == rootChildren && newParts == parts) {
		return this
	}
	return copy(rootChildren = newRoot, parts = newParts).withDerivedRenderRoot()
}

/**
 * Moves the org child [child] under [newParentId] (null = top level), before [before] (null = append), as
 * one undo step. A cycle-forming or no-op move records nothing.
 *
 * @param OrgChild child The sub-part or drawable to move.
 * @param PartId? newParentId The destination part, or null for the top level.
 * @param OrgChild? before The sibling to insert before, or null to append.
 */
fun EditorSession.moveOrgChild(child: OrgChild, newParentId: PartId?, before: OrgChild?) {
	val change: Change =
		when (child) {
			is OrgChild.Part -> PartChange.Move(child.id, newParentId, (before as? OrgChild.Part)?.id)
			is OrgChild.Drawable -> DrawableChange.Move(child.id, newParentId)
		}
	mutate(change) { model -> model.withOrgChildMoved(child, newParentId, before) }
}

/** A copy of this deformer re-homed under [newParent] in the transform (nesting) hierarchy. */
private fun Deformer.withParent(newParent: DeformerId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(parent = newParent)
		is Deformer.Rotation -> copy(parent = newParent)
	}

/**
 * Returns a copy of [this] with the deformer [id] re-nested under [newParentId] (null = an armature root)
 * and re-positioned among the deformers list before [beforeId] (null = appended). Reparent and reorder are
 * one move: [Deformer.parent] sets the transform nesting (which changes deformation), and the deformers
 * list order sets sibling order in the armature (cosmetic). A move that would nest the deformer inside its
 * own subtree (a cycle) or that does nothing returns the same instance.
 *
 * @param DeformerId id The deformer to move.
 * @param DeformerId? newParentId The destination parent deformer, or null for an armature root.
 * @param DeformerId? beforeId The sibling to insert before, or null to append.
 * @return PuppetModel The model with the deformer moved, or [this] if the move was a no-op or illegal.
 */
fun PuppetModel.withDeformerMoved(id: DeformerId, newParentId: DeformerId?, beforeId: DeformerId?): PuppetModel {
	val deformer = deformers.firstOrNull { it.id == id } ?: return this
	// The subtree set contains id itself, so this rejects self-parenting and any deeper cycle at once.  The
	// Properties picker filters its candidates against the same query, so an illegal target is not offered
	// there rather than being silently refused here.
	if (newParentId != null && newParentId in deformerSelfAndDescendants(id)) {
		return this
	}
	val moved = deformer.withParent(newParentId)
	val without = deformers.filter { it.id != id }.toMutableList()
	val insertAt = beforeId?.let { target -> without.indexOfFirst { it.id == target } }?.takeIf { it >= 0 } ?: without.size
	without.add(insertAt, moved)
	return copy(deformers = without)
}

/**
 * Moves the deformer [id] under [newParentId] (null = armature root), before [beforeId] (null = append),
 * as one undo step. A cycle-forming or no-op move records nothing.
 *
 * @param DeformerId id The deformer to move.
 * @param DeformerId? newParentId The destination parent deformer, or null for a root.
 * @param DeformerId? beforeId The sibling to insert before, or null to append.
 */
fun EditorSession.moveDeformer(id: DeformerId, newParentId: DeformerId?, beforeId: DeformerId?) {
	mutate(DeformerChange.Move(id, newParentId, beforeId)) { model -> model.withDeformerMoved(id, newParentId, beforeId) }
}