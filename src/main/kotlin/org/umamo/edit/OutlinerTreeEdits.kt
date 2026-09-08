package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * Outliner drop resolution: the pure rules deciding what dropping one outliner row onto another does,
 * over the organizational tree (parts / drawables) and the deformer armature.  The UI reads the band
 * for its indicator and resolves the drop for its dispatch through the SAME functions here, so the
 * line / fill the user sees always matches the move that happens.  The tree transforms themselves
 * (withOrgChildMoved / withDeformerMoved) live in StructureEdits.kt; this file only routes a drop to
 * them.  Mirrors the parameter panel's split (ParametersDropRules in :ui feeds ParameterTreeEdits
 * here) - except the outliner's rules mention no UI types, so the whole resolution lives in :edit.
 *
 * アウトライナーのドロップ解決。行ドロップの帯域判定と移動先の解決を一箇所で行い、指標と実際の
 * 移動が常に一致する。ツリー変換自体は StructureEdits.kt にある。
 */

/**
 * A resolved outliner drop: the concrete move a drop dispatches, plus whether the UI should expand
 * the target row afterwards (a nest-inside drop opens the destination so the user sees the moved
 * item land, rather than fearing it vanished into a collapsed branch).
 */
sealed interface OutlinerDrop {
	/** True when the UI should expand the drop-target row after applying the move. */
	val expandTarget: Boolean

	/** Re-home a deformer in the armature's nesting hierarchy. */
	data class MoveDeformer(
		val id: DeformerId,
		val newParentId: DeformerId?,
		val beforeId: DeformerId?,
		override val expandTarget: Boolean,
	) : OutlinerDrop

	/** Re-home an org child (a part or drawable) in the organizational tree. */
	data class MoveOrgChild(
		val child: OrgChild,
		val newParentId: PartId?,
		val before: OrgChild?,
		override val expandTarget: Boolean,
	) : OutlinerDrop
}

/**
 * The single source of truth for what a drag will do, given what is dragged, the row under the pointer,
 * and the pointer's vertical band within that row - used by BOTH the drop dispatch and the row's indicator,
 * so the line / fill the user sees always matches the move that happens.  A drawable nests INTO a part
 * (always, regardless of band) and relayers before / after another drawable; a part nests into or reorders
 * before / after another part (a wider middle band for "into").  Incompatible pairings (a drawable onto the
 * armature, a part onto a drawable) return null - no drop.
 *
 * @param SelectionTarget dragged The entity being dragged.
 * @param SelectionTarget target The entity under the pointer.
 * @param Float fraction The pointer's 0..1 position down the target row.
 * @return RowDropBand? The resolved band, or null when the drop is not allowed.
 */
fun outlinerDropBandFor(dragged: SelectionTarget, target: SelectionTarget, fraction: Float): RowDropBand? {
	// Deformers reorder only within the armature; org children (parts / drawables) only within the org tree,
	// so a cross-domain drop is never valid.
	if ((dragged is SelectionTarget.Deformer) != (target is SelectionTarget.Deformer)) {
		return null
	}
	return when (target) {
		// A part (and a deformer, which also nests) gets a wider middle "into" band; before / after reorder.
		is SelectionTarget.Part, is SelectionTarget.Deformer ->
			when {
				fraction < 0.33f -> RowDropBand.Before
				fraction > 0.66f -> RowDropBand.After
				else -> RowDropBand.Into
			}
		// A drawable has no children, so a drop next to it is only before / after (a sibling reorder - which,
		// for an org child dropped next to a drawable, is the cross-kind interleave Cubism allows).
		is SelectionTarget.Drawable -> if (fraction >= 0.5f) RowDropBand.After else RowDropBand.Before
	}
}

/**
 * This selection target as an [OrgChild] (a part or drawable), or null for a deformer (not in the org tree).
 *
 * @return OrgChild? The org-tree child, or null.
 */
fun SelectionTarget.asOrgChild(): OrgChild? =
	when (this) {
		is SelectionTarget.Part -> OrgChild.Part(id)
		is SelectionTarget.Drawable -> OrgChild.Drawable(id)
		is SelectionTarget.Deformer -> null
	}

/** The org child [child]'s sibling list - its owning part's children, or the root level when it is top-level. */
private fun PuppetModel.orgSiblingsOf(child: OrgChild): List<OrgChild> {
	val parent = parts.firstOrNull { child in it.children }
	return parent?.children ?: rootChildren
}

/** The id of the part owning [child], or null when it is at the top level. */
private fun PuppetModel.orgParentOf(child: OrgChild): PartId? =
	parts.firstOrNull { child in it.children }?.id

/** The org child immediately after [child] among its siblings, or null when it is last (append). */
private fun PuppetModel.orgSiblingAfter(child: OrgChild): OrgChild? {
	val siblings = orgSiblingsOf(child)
	return siblings.getOrNull(siblings.indexOf(child) + 1)
}

/** The deformer [id]'s parent in the nesting hierarchy, or null when it is an armature root. */
private fun PuppetModel.deformerParentOf(id: DeformerId): DeformerId? =
	deformers.firstOrNull { it.id == id }?.parent

/** The deformer immediately after [id] among its same-parent siblings (list order), or null when last. */
private fun PuppetModel.deformerSiblingAfter(id: DeformerId): DeformerId? {
	val parent = deformerParentOf(id)
	val siblings = deformers.filter { it.parent == parent }.map { it.id }
	return siblings.getOrNull(siblings.indexOf(id) + 1)
}

/**
 * Resolves a drop of [dragged] onto [target] at [fraction] into the concrete move it performs, routed
 * by the pointer's vertical band within the target row (the same [outlinerDropBandFor] the indicator
 * drew):
 *  - before / after a sibling: re-home the dragged item next to the target in its parent (parts and
 *    drawables interleave, so a drawable can land between two parts) - which changes the panel order,
 *    hence the draw order, so the viewport follows;
 *  - into a part (or a deformer): nest the dragged item inside it, expanding the target.
 * Drops with no valid band or across the org / armature boundary resolve to null - no move.  Cycles
 * are refused inside the move transforms themselves.
 *
 * @param SelectionTarget dragged The entity being dragged.
 * @param SelectionTarget target The entity under the pointer.
 * @param Float fraction The pointer's 0..1 position down the target row.
 * @return OutlinerDrop? The move to dispatch, or null when the drop does nothing.
 */
fun PuppetModel.resolveOutlinerDrop(dragged: SelectionTarget, target: SelectionTarget, fraction: Float): OutlinerDrop? {
	val band = outlinerDropBandFor(dragged, target, fraction) ?: return null
	// Deformers move within the armature only.
	if (dragged is SelectionTarget.Deformer && target is SelectionTarget.Deformer) {
		return when (band) {
			RowDropBand.Before ->
				OutlinerDrop.MoveDeformer(dragged.id, deformerParentOf(target.id), beforeId = target.id, expandTarget = false)
			RowDropBand.After ->
				OutlinerDrop.MoveDeformer(dragged.id, deformerParentOf(target.id), beforeId = deformerSiblingAfter(target.id), expandTarget = false)
			RowDropBand.Into ->
				OutlinerDrop.MoveDeformer(dragged.id, target.id, beforeId = null, expandTarget = true)
		}
	}
	// Org children (parts / drawables): one move covers reparent, reorder, and cross-kind interleave.
	val draggedChild = dragged.asOrgChild() ?: return null
	val targetChild = target.asOrgChild() ?: return null
	return when (band) {
		RowDropBand.Before ->
			OutlinerDrop.MoveOrgChild(draggedChild, orgParentOf(targetChild), before = targetChild, expandTarget = false)
		RowDropBand.After ->
			OutlinerDrop.MoveOrgChild(draggedChild, orgParentOf(targetChild), before = orgSiblingAfter(targetChild), expandTarget = false)
		RowDropBand.Into ->
			if (target is SelectionTarget.Part) {
				OutlinerDrop.MoveOrgChild(draggedChild, target.id, before = null, expandTarget = true)
			} else {
				// The band rules never produce Into for a drawable target, so this arm is unreachable in
				// practice; resolving to null keeps the function total anyway.
				null
			}
	}
}

/**
 * Applies a resolved outliner drop as one undo step, dispatching to the matching structure edit.
 *
 * @param OutlinerDrop drop The resolved move.
 */
fun EditorSession.applyOutlinerDrop(drop: OutlinerDrop) {
	when (drop) {
		is OutlinerDrop.MoveDeformer -> moveDeformer(drop.id, drop.newParentId, drop.beforeId)
		is OutlinerDrop.MoveOrgChild -> moveOrgChild(drop.child, drop.newParentId, drop.before)
	}
}