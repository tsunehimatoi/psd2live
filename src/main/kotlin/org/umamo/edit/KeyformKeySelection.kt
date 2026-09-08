package org.umamo.edit

/*
 * What a key REMOVAL does to the keyform sheet's key selection.
 *
 * Removing keys renumbers the ones above them: a [TrackKeyRef] names a row and an ordinal on that row's
 * track, so a selection left untouched across a removal stops naming the key the user selected and instead
 * names whichever key slides down onto the freed ordinal.
 *
 * The rule lives in :edit rather than in the sheet because it is a statement about what an edit MEANS, and
 * three call sites need to agree on it - the aimed removal behind Alt+I and the lane menu, the selected-keys
 * removal behind Delete, and the summary-mark removal.  A [TrackKeyRef]'s row key is opaque here, which is
 * all this needs: the algebra compares row keys, it never resolves one.
 */

/**
 * [current] with [removed] taken out and every ordinal that renumbers under them corrected.
 *
 * Only two kinds of ref are at risk from a removal: the removed keys themselves, and the LATER ordinals on
 * the same row, which slide down one place per key removed below them.  Everything else names exactly what
 * it named before, so a mark the user had not selected must leave their selection untouched rather than
 * clearing it wholesale.
 *
 * Matched on the parameter as well as the row because a linked pair renders one row under two sections, and
 * a removal renumbers only the axis it happened on.
 *
 * @param Set<TrackKeyRef> current The selection before the removal.
 * @param Set<TrackKeyRef> removed The keys the removal takes out.
 * @return Set<TrackKeyRef> The selection that survives it.
 */
fun selectionAfterKeyRemoval(current: Set<TrackKeyRef>, removed: Set<TrackKeyRef>): Set<TrackKeyRef> {
	if (removed.isEmpty()) {
		return current
	}
	return current
		.filterNot { key -> key in removed }
		.map { key ->
			val removedBelow =
				removed.count { gone ->
					gone.parameterId == key.parameterId && gone.rowKey == key.rowKey && gone.keyIndex < key.keyIndex
				}
			if (removedBelow == 0) key else key.copy(keyIndex = key.keyIndex - removedBelow)
		}.toSet()
}

/**
 * [current] with every ordinal at or above [inserted] shifted up to make room for it.
 *
 * The mirror of [selectionAfterKeyRemoval]: an insert renumbers every key at or above where it lands, so a
 * selection on one of those keys must shift up by one to keep naming the same key.  A key below the
 * insertion point already names the right key and is left untouched.
 *
 * @param Set<TrackKeyRef> current The selection before the insert.
 * @param TrackKeyRef inserted The new key, at the ordinal it takes.
 * @return Set<TrackKeyRef> The selection as it reads afterwards.
 */
fun selectionAfterKeyInsertion(current: Set<TrackKeyRef>, inserted: TrackKeyRef): Set<TrackKeyRef> =
	current
		.map { key ->
			val renumbers =
				key.parameterId == inserted.parameterId &&
					key.rowKey == inserted.rowKey &&
					key.keyIndex >= inserted.keyIndex
			if (renumbers) key.copy(keyIndex = key.keyIndex + 1) else key
		}.toSet()

/**
 * Runs [removeKeys] with the key selection re-pointed at what survives it, as ONE undo step.
 *
 * An empty [removed] is the caller saying it cannot name what it removed in sheet terms (a Properties row
 * has no sheet row), and leaves the selection exactly as it was.
 *
 * @param Set<TrackKeyRef> removed The keys [removeKeys] takes out, in sheet-row terms.
 * @param Function removeKeys The removal itself, which records its own step.
 */
fun EditorSession.removingKeys(removed: Set<TrackKeyRef>, removeKeys: () -> Unit) {
	editingKeys({ current -> selectionAfterKeyRemoval(current, removed) }, removeKeys)
}

/**
 * Runs [insertKey] with the key selection re-pointed past the key it adds, as ONE undo step.
 *
 * A null [inserted] is the caller saying no key is added, or that it cannot name the new one in sheet terms,
 * and leaves the selection exactly as it was.
 *
 * @param TrackKeyRef? inserted The new key at the ordinal it takes, or null when none is added.
 * @param Function insertKey The insert itself, which records its own step.
 */
fun EditorSession.insertingKey(inserted: TrackKeyRef?, insertKey: () -> Unit) {
	editingKeys({ current -> if (inserted == null) current else selectionAfterKeyInsertion(current, inserted) }, insertKey)
}

/**
 * Runs [edit] with the key selection put through [reconcile], as ONE undo step.
 *
 * STAGE, EDIT, CONFIRM (see [EditorSession.stageKeySelection]): the reconciled selection is staged first so
 * the edit's own snapshot records it, and confirmed afterwards so it still reaches history when the edit
 * declines to record anything.  Staging it AFTER instead would leave every recorded step holding the
 * pre-edit refs, so undoing back to one would point the selection at whichever keys had taken those ordinals.
 *
 * An edit that refuses is rolled back to the selection as it was, because nothing renumbered - the
 * re-pointing would then be a shift with no edit under it.  Measured by model identity, which the edit ops
 * maintain: a refused edit hands back the instance it was given.
 *
 * @param Function reconcile The selection as it reads after [edit], given how it reads now.
 * @param Function edit The edit itself, which records its own step.
 */
private fun EditorSession.editingKeys(reconcile: (Set<TrackKeyRef>) -> Set<TrackKeyRef>, edit: () -> Unit) {
	val selectionBefore = keySelection.value
	val reconciled = reconcile(selectionBefore)
	val modelBeforeEdit = model.value
	stageKeySelection(reconciled)
	edit()
	if (model.value === modelBeforeEdit) {
		stageKeySelection(selectionBefore)
	} else {
		setKeySelection(reconciled)
	}
}