package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Entity deletion over the org tree. Removing a drawable, a deformer, or a part scrubs every dangling
 * reference so no later code dereferences a deleted id. Deleting an art mesh is the only inherently
 * destructive case; deleting a deformer "unwraps" it (its sub-deformers and bound meshes re-home to its
 * parent); deleting a part is either a cascade (the part and its whole subtree) or an ungroup (the folder
 * only, contents spliced up into its parent). Org-tree edits re-derive the render order.
 */

/** A copy of this deformer re-nested under [newParent] in the transform hierarchy (used to re-home orphans). */
private fun Deformer.reparentedTo(newParent: DeformerId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(parent = newParent)
		is Deformer.Rotation -> copy(parent = newParent)
	}

/** A copy of this deformer re-bound to the organizational part [newPart] (or null to clear a dangling ref). */
private fun Deformer.reboundToPart(newPart: PartId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(partId = newPart)
		is Deformer.Rotation -> copy(partId = newPart)
	}

/**
 * Returns a copy of [this] with every drawable in [ids] removed and every reference to them scrubbed: the
 * drawables list, the org tree (their [OrgChild.Drawable] entries), any other drawable's clip mask, and any
 * glue whose either partner was deleted. The render order is NOT re-derived here - the caller does that once
 * it has finished its structural changes. An empty set returns the same instance.
 *
 * @param Set<DrawableId> ids The drawables to delete.
 * @return PuppetModel The model with those drawables and their references gone.
 */
private fun PuppetModel.removingDrawables(ids: Set<DrawableId>): PuppetModel {
	if (ids.isEmpty()) {
		return this
	}
	val remaining =
		drawables.filterNot { drawable -> drawable.id in ids }
			.map { drawable ->
				val cleanedMasks = drawable.maskedBy.filterNot { maskId -> maskId in ids }
				if (cleanedMasks.size != drawable.maskedBy.size) drawable.copy(maskedBy = cleanedMasks) else drawable
			}
	val cleanedGlues = glues.filterNot { glue -> glue.meshA in ids || glue.meshB in ids }
	val cleanedRoot = rootChildren.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
	val cleanedParts =
		parts.map { part ->
			val kids = part.children.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
			if (kids.size != part.children.size) part.copy(children = kids) else part
		}
	return copy(drawables = remaining, glues = cleanedGlues, rootChildren = cleanedRoot, parts = cleanedParts)
}

/**
 * Returns a copy of [this] with the single drawable [id] deleted (mask / glue / tree references scrubbed,
 * render order re-derived). A no-op (no such drawable) returns the same instance.
 *
 * @param DrawableId id The drawable to delete.
 * @return PuppetModel The model without that drawable, or [this] if it was absent.
 */
fun PuppetModel.withDrawableDeleted(id: DrawableId): PuppetModel {
	if (drawables.none { it.id == id }) {
		return this
	}
	return removingDrawables(setOf(id)).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the deformer [id] deleted by unwrapping it: its child deformers and the
 * drawables it deformed re-home to its own parent (null = an armature root), so removing a transform
 * wrapper never deletes art. Does not touch the org tree, so the render order is unchanged. A no-op (no
 * such deformer) returns the same instance.
 *
 * @param DeformerId id The deformer to delete.
 * @return PuppetModel The model without that deformer, or [this] if it was absent.
 */
fun PuppetModel.withDeformerDeleted(id: DeformerId): PuppetModel {
	val deformer = deformers.firstOrNull { it.id == id } ?: return this
	val grandParent = deformer.parent
	val updatedDeformers =
		deformers.filter { it.id != id }
			.map { other -> if (other.parent == id) other.reparentedTo(grandParent) else other }
	val updatedDrawables =
		drawables.map { drawable ->
			if (drawable.parentDeformerId == id) drawable.copy(parentDeformerId = grandParent) else drawable
		}
	return copy(deformers = updatedDeformers, drawables = updatedDrawables)
}

/** The set of part ids in [id]'s org-tree subtree (the part itself plus every descendant part). */
private fun PuppetModel.partSubtreeIds(id: PartId): Set<PartId> {
	val partById = parts.associateBy { it.id }
	val subtree = LinkedHashSet<PartId>()
	val stack = ArrayDeque<PartId>()
	stack.add(id)
	while (stack.isNotEmpty()) {
		val next = stack.removeLast()
		if (!subtree.add(next)) {
			continue
		}
		partById[next]?.children?.forEach { child -> if (child is OrgChild.Part) stack.add(child.id) }
	}
	return subtree
}

/**
 * Returns a copy of [this] with the part [id] deleted. A [cascade] removes the whole subtree - the part,
 * every descendant part, and every drawable under any of them (references scrubbed); an ungroup (cascade
 * false) dissolves only the folder - its children (sub-parts and drawables) splice into its parent in
 * place, so nothing is destroyed. The render order is re-derived. A no-op (no such part) returns the same
 * instance.
 *
 * @param PartId id The part to delete.
 * @param Boolean cascade True to delete the subtree, false to ungroup (keep contents, splice them up).
 * @return PuppetModel The model with the part deleted, or [this] if it was absent.
 */
fun PuppetModel.withPartDeleted(id: PartId, cascade: Boolean): PuppetModel {
	val part = parts.firstOrNull { it.id == id } ?: return this
	val partRef = OrgChild.Part(id)
	// The deleted part's parent (where ungrouped contents land / dangling deformer bindings fall back to).
	val grandParentId = parts.firstOrNull { partRef in it.children }?.id
	return if (cascade) {
		val subtree = partSubtreeIds(id)
		val doomedDrawables =
			parts.filter { it.id in subtree }
				.flatMap { it.children }
				.filterIsInstance<OrgChild.Drawable>()
				.map { it.id }
				.toSet()
		// Drop the subtree parts; detach the part ref from wherever it sits (root or its parent).
		val remainingParts =
			parts.filterNot { it.id in subtree }
				.map { candidate -> if (partRef in candidate.children) candidate.copy(children = candidate.children - partRef) else candidate }
		val cleanedRoot = rootChildren.filter { it != partRef }
		// A deformer's organizational partId may point into the deleted subtree; clear it so nothing dangles.
		val cleanedDeformers =
			deformers.map { deformer ->
				if (deformer.partId != null && deformer.partId in subtree) deformer.reboundToPart(null) else deformer
			}
		copy(parts = remainingParts, rootChildren = cleanedRoot, deformers = cleanedDeformers)
			.removingDrawables(doomedDrawables)
			.copy(rootPartId = if (rootPartId != null && rootPartId in subtree) null else rootPartId)
			.withDerivedRenderRoot()
	} else {
		// Ungroup: replace the part ref with the part's own children, in place, wherever it sits.
		fun splice(children: List<OrgChild>): List<OrgChild> = children.flatMap { if (it == partRef) part.children else listOf(it) }
		val cleanedRoot = splice(rootChildren)
		val remainingParts = parts.filterNot { it.id == id }.map { it.copy(children = splice(it.children)) }
		val cleanedDeformers = deformers.map { deformer -> if (deformer.partId == id) deformer.reboundToPart(grandParentId) else deformer }
		copy(
			parts = remainingParts,
			rootChildren = cleanedRoot,
			deformers = cleanedDeformers,
			rootPartId = if (rootPartId == id) grandParentId else rootPartId,
		).withDerivedRenderRoot()
	}
}

/**
 * Deletes the drawable [id] as one undo step. A no-op records nothing.
 *
 * @param DrawableId id The drawable to delete.
 */
fun EditorSession.deleteDrawable(id: DrawableId) {
	mutate(DrawableChange.Delete(id)) { model -> model.withDrawableDeleted(id) }
}

/**
 * Deletes the deformer [id] (unwrapping it - children re-home to its parent) as one undo step.
 *
 * @param DeformerId id The deformer to delete.
 */
fun EditorSession.deleteDeformer(id: DeformerId) {
	mutate(DeformerChange.Delete(id)) { model -> model.withDeformerDeleted(id) }
}

/**
 * Deletes the part [id] as one undo step: a cascade (subtree) when [cascade], else an ungroup (contents spliced up).
 *
 * @param PartId id The part to delete.
 * @param Boolean cascade True to delete the subtree, false to ungroup.
 */
fun EditorSession.deletePart(id: PartId, cascade: Boolean) {
	mutate(PartChange.Delete(id, cascade)) { model -> model.withPartDeleted(id, cascade) }
}

/**
 * Deletes the entity named by [target] as one undo step. [cascade] applies only to a part (a drawable or a
 * deformer ignores it - a deformer always unwraps).
 *
 * @param SelectionTarget target The entity to delete.
 * @param Boolean cascade For a part, true to delete the subtree, false to ungroup; ignored otherwise.
 */
fun EditorSession.deleteTarget(target: SelectionTarget, cascade: Boolean) {
	when (target) {
		is SelectionTarget.Part -> deletePart(target.id, cascade)
		is SelectionTarget.Drawable -> deleteDrawable(target.id)
		is SelectionTarget.Deformer -> deleteDeformer(target.id)
	}
}