package org.umamo.edit

import org.umamo.runtime.model.PuppetModel

/*
 * Selection-aware visibility helpers, bridging the object-mode Selection to the per-entity visibility
 * transforms. Kept here (not in the UI) so the "toggle visibility of the selection" command is a thin
 * dispatch and the model logic stays pure and testable.
 *
 * 選択に対する表示切り替えの補助。UI ではなくここに置き、コマンドは委譲だけにする。
 */

/**
 * The current Parts-panel visibility of [target] in [this]. A deformer has no visibility flag, so it
 * reports true (it is never "hidden"); a missing part/drawable also reports true.
 *
 * @param SelectionTarget target The entity to query.
 * @return Boolean The entity's visibility (true when it has none).
 */
fun PuppetModel.visibilityOf(target: SelectionTarget): Boolean =
	when (target) {
		is SelectionTarget.Part -> parts.firstOrNull { it.id == target.id }?.isVisible ?: true
		is SelectionTarget.Drawable -> drawables.firstOrNull { it.id == target.id }?.isVisible ?: true
		is SelectionTarget.Deformer -> true
	}

/**
 * Returns a copy of [this] with every part and drawable in [targets] set to [visible], structurally
 * sharing untouched entities; deformer targets are skipped (they have no visibility flag). Folding all
 * targets into one model makes the whole multi-selection toggle a single undo step.
 *
 * @param Set targets The selected targets to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with those entities' visibility updated.
 */
fun PuppetModel.withSelectionVisibility(targets: Set<SelectionTarget>, visible: Boolean): PuppetModel {
	var next = this
	for (target in targets) {
		next =
			when (target) {
				is SelectionTarget.Part -> next.withPartVisibility(target.id, visible)
				is SelectionTarget.Drawable -> next.withDrawableVisibility(target.id, visible)
				is SelectionTarget.Deformer -> next
			}
	}
	return next
}