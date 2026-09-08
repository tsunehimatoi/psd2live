package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * Target-aware convenience edits on an EditorSession: a single selectable entity (part/drawable/deformer)
 * is toggled or renamed as one undo step, and the subtree variants (a Blender-style Shift+Click on an
 * outliner restriction icon) flip a whole outliner subtree in one step.  These dispatch the right
 * Change + transform per target kind so call sites (the outliner's per-row eye and inline rename) stay a
 * thin one-liner.  The selection-wide visibility toggle stays separate (see withSelectionVisibility) —
 * that one flips the whole selection.
 *
 * 対象1件（パーツ／描画／デフォーマ）を1段の取り消し単位で切り替え・改名するセッション補助。サブツリー版
 * （Shift クリック）は配下全体を1段で切り替える。
 */

/**
 * Toggles the visibility of one [target] as a single undo step, dispatching the part- or drawable-specific
 * change and transform.  A deformer has no visibility flag, so it is a no-op.
 *
 * @param SelectionTarget target The entity whose eyeball to flip.
 */
fun EditorSession.toggleVisibility(target: SelectionTarget) {
	val newVisible = !model.value.visibilityOf(target)
	when (target) {
		is SelectionTarget.Part ->
			mutate(PartChange.SetVisibility(target.id, newVisible)) { model -> model.withPartVisibility(target.id, newVisible) }
		is SelectionTarget.Drawable ->
			mutate(DrawableChange.SetVisibility(target.id, newVisible)) { model -> model.withDrawableVisibility(target.id, newVisible) }
		is SelectionTarget.Deformer -> {
			// Deformers have no visibility flag; nothing to toggle.
		}
	}
}

/**
 * Renames one [target] to [newName] as a single undo step, dispatching the per-kind change and transform.
 * A blank name (after trimming) is ignored, so an empty commit keeps the old name.
 *
 * @param SelectionTarget target The entity to rename.
 * @param String newName The requested new name.
 */
fun EditorSession.rename(target: SelectionTarget, newName: String) {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return
	}
	when (target) {
		is SelectionTarget.Part -> mutate(PartChange.Rename(target.id, trimmed)) { model -> model.withPartName(target.id, trimmed) }
		is SelectionTarget.Drawable -> mutate(DrawableChange.Rename(target.id, trimmed)) { model -> model.withDrawableName(target.id, trimmed) }
		is SelectionTarget.Deformer -> mutate(DeformerChange.Rename(target.id, trimmed)) { model -> model.withDeformerName(target.id, trimmed) }
	}
}

/**
 * Whether [target] is viewport-selectable in [this] model. A missing entity reports true (selectable).
 *
 * @param SelectionTarget target The entity to query.
 * @return Boolean The entity's selectable state.
 */
fun PuppetModel.selectableOf(target: SelectionTarget): Boolean =
	when (target) {
		is SelectionTarget.Part -> parts.firstOrNull { it.id == target.id }?.isSelectable ?: true
		is SelectionTarget.Drawable -> drawables.firstOrNull { it.id == target.id }?.isSelectable ?: true
		is SelectionTarget.Deformer -> deformers.firstOrNull { it.id == target.id }?.isSelectable ?: true
	}

/**
 * Toggles the viewport selectability of one [target] as a single undo step, dispatching the per-kind
 * change and transform. Unlike visibility, a deformer has a selectable flag too.
 *
 * @param SelectionTarget target The entity whose selectability to flip.
 */
fun EditorSession.toggleSelectable(target: SelectionTarget) {
	val newSelectable = !model.value.selectableOf(target)
	when (target) {
		is SelectionTarget.Part ->
			mutate(PartChange.SetSelectable(target.id, newSelectable)) { model -> model.withPartSelectable(target.id, newSelectable) }
		is SelectionTarget.Drawable ->
			mutate(DrawableChange.SetSelectable(target.id, newSelectable)) { model -> model.withDrawableSelectable(target.id, newSelectable) }
		is SelectionTarget.Deformer ->
			mutate(DeformerChange.SetSelectable(target.id, newSelectable)) { model -> model.withDeformerSelectable(target.id, newSelectable) }
	}
}

/**
 * Enumerates the outliner subtree rooted at [target], the target itself first, in tree order.  Matches
 * exactly what the outliner shows as children: a part yields itself plus every descendant part and every
 * drawable in those parts' org-tree children; a deformer yields itself plus its descendant deformers only
 * (never the drawables bound to them via parentDeformerId — those rows live under their parts); a
 * drawable is a leaf, so it yields just itself.  An id that no longer resolves is still yielded (the
 * transforms no-op on it) but never descended into, and a malformed cycle is visited once.  Pure over the
 * model, so it unit-tests without Compose.
 *
 * @param SelectionTarget target The subtree root.
 * @return List The subtree's targets, [target] first.
 */
fun PuppetModel.subtreeTargets(target: SelectionTarget): List<SelectionTarget> =
	when (target) {
		is SelectionTarget.Part -> {
			val partsById = parts.associateBy { part -> part.id }
			val visitedPartIds = HashSet<PartId>()
			val collected = mutableListOf<SelectionTarget>()

			fun visitPart(partId: PartId) {
				if (!visitedPartIds.add(partId)) {
					return
				}
				collected += SelectionTarget.Part(partId)
				val part = partsById[partId] ?: return
				for (childEntry in part.children) {
					when (childEntry) {
						is OrgChild.Part -> visitPart(childEntry.id)
						is OrgChild.Drawable -> collected += SelectionTarget.Drawable(childEntry.id)
					}
				}
			}
			visitPart(target.id)
			collected
		}
		is SelectionTarget.Drawable -> listOf(target)
		is SelectionTarget.Deformer -> {
			val deformersByParent = deformers.groupBy { deformer -> deformer.parent }
			val visitedDeformerIds = HashSet<DeformerId>()
			val collected = mutableListOf<SelectionTarget>()

			fun visitDeformer(deformerId: DeformerId) {
				if (!visitedDeformerIds.add(deformerId)) {
					return
				}
				collected += SelectionTarget.Deformer(deformerId)
				for (childDeformer in deformersByParent[deformerId].orEmpty()) {
					visitDeformer(childDeformer.id)
				}
			}
			visitDeformer(target.id)
			collected
		}
	}

/**
 * Returns a copy of [this] with every entity in [targets] set to [selectable], structurally sharing
 * untouched entities.  Unlike the visibility counterpart (withSelectionVisibility) this covers deformers
 * too — they carry the flag.  Folding all targets into one model makes a whole subtree toggle a single
 * undo step.
 *
 * @param Set targets The targets to retoggle.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with those entities' selectability updated.
 */
fun PuppetModel.withSelectionSelectable(targets: Set<SelectionTarget>, selectable: Boolean): PuppetModel {
	var next = this
	for (target in targets) {
		next =
			when (target) {
				is SelectionTarget.Part -> next.withPartSelectable(target.id, selectable)
				is SelectionTarget.Drawable -> next.withDrawableSelectable(target.id, selectable)
				is SelectionTarget.Deformer -> next.withDeformerSelectable(target.id, selectable)
			}
	}
	return next
}

/**
 * Toggles viewport selectability for [target] and its whole outliner subtree as one undo step: the new
 * value is the flip of the clicked target's current state, applied uniformly to every subtree entity (a
 * Blender-style Shift+Click), so a mixed subtree lands on one state.  Reuses the clicked target's
 * per-kind SetSelectable change, mirroring the selection-wide visibility command.
 *
 * @param SelectionTarget target The clicked subtree root.
 */
fun EditorSession.toggleSelectableSubtree(target: SelectionTarget) {
	val newSelectable = !model.value.selectableOf(target)
	val change: Change =
		when (target) {
			is SelectionTarget.Part -> PartChange.SetSelectable(target.id, newSelectable)
			is SelectionTarget.Drawable -> DrawableChange.SetSelectable(target.id, newSelectable)
			is SelectionTarget.Deformer -> DeformerChange.SetSelectable(target.id, newSelectable)
		}
	mutate(change) { model -> model.withSelectionSelectable(model.subtreeTargets(target).toSet(), newSelectable) }
}

/**
 * Toggles visibility for [target] and its whole outliner subtree as one undo step, applied uniformly like
 * the selectable variant.  Deformers have no visibility flag, so a deformer target is a no-op (the
 * outliner shows no eye for one anyway) and any deformer inside a part subtree is skipped by
 * withSelectionVisibility.
 *
 * @param SelectionTarget target The clicked subtree root.
 */
fun EditorSession.toggleVisibilitySubtree(target: SelectionTarget) {
	val newVisible = !model.value.visibilityOf(target)
	val change: Change =
		when (target) {
			is SelectionTarget.Part -> PartChange.SetVisibility(target.id, newVisible)
			is SelectionTarget.Drawable -> DrawableChange.SetVisibility(target.id, newVisible)
			// A deformer has no visibility flag; nothing to toggle.
			is SelectionTarget.Deformer -> return
		}
	mutate(change) { model -> model.withSelectionVisibility(model.subtreeTargets(target).toSet(), newVisible) }
}