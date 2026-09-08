package org.umamo.edit

import org.umamo.runtime.keyform.keyDestinationFor
import org.umamo.runtime.keyform.keyIndexAfterInsert
import org.umamo.runtime.keyform.keyIndexAfterMove
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.channelGridsOf

/*
 * The one dispatch from "a track the sheet is pointing at" to the channel or geometry implementation.
 *
 * The sheet applies the same three gestures - drag a key, insert one, remove one - to every row it draws,
 * and which underlying op that becomes is not a decision the UI should be making twice per gesture.  It is
 * made once, here.
 */

/**
 * This model with [track]'s key at [fromValue] moved to [toValue] on [parameter]'s axis.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The requested new position.
 * @return PuppetModel The model with the key moved, or this on a refusal.
 */
fun PuppetModel.withTrackKeyMoved(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyMoved(track.target, parameter, keyIndex, toValue)
		is KeyformTrackRef.Geometry -> withGeometryKeyMoved(track.owner, parameter, keyIndex, toValue)
	}

/**
 * This model with a shape-preserving key inserted at [position] on [track]'s axis for [parameter].
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 * @param Pose pose The current pose, which fixes the cell on every OTHER axis of a multi-keyed track.
 * @return PuppetModel The model with the key inserted, or this on a refusal.
 */
fun PuppetModel.withTrackKeyInserted(
	track: KeyformTrackRef,
	parameter: Parameter,
	position: Float,
	pose: Pose,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> {
			val posed = pose + (parameter.id to position)
			val heldValue = channelValueAt(track.target, posed)
			if (heldValue == null) this else withChannelKeyCaptured(track.target, parameter, posed, heldValue)
		}

		is KeyformTrackRef.Geometry -> withGeometryKeyInserted(track.owner, parameter, position)
	}

/**
 * This model with [track]'s key at [position] removed from [parameter]'s axis.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withTrackKeyRemoved(track: KeyformTrackRef, parameter: Parameter, keyIndex: Int): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyRemovedAt(track.target, parameter, keyIndex)
		is KeyformTrackRef.Geometry -> withGeometryKeyRemoved(track.owner, parameter, keyIndex)
	}

/** The history-label channel for [track] - null for geometry, which has no channel to name. */
private fun channelOf(track: KeyformTrackRef) =
	when (track) {
		is KeyformTrackRef.Channel -> track.target.channel
		is KeyformTrackRef.Geometry -> null
	}

/**
 * [value] clamped into [parameter]'s range, tolerating a reversed (min > max) range from a malformed
 * import.
 *
 * Centralized HERE rather than at each call site, so a pointer drag, a keyboard nudge, and a lane-edge
 * insert cannot disagree about where the ends are.
 *
 * @param Float value The requested parameter value.
 * @param Parameter parameter The parameter whose range bounds it.
 * @return Float The clamped value.
 */
private fun clampToParameterRange(value: Float, parameter: Parameter): Float =
	value.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max))

/**
 * Moves [track]'s key at [fromValue] to [toValue], as one undo step.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The new position.
 * @return Int The ordinal the key holds AFTER the move, which a crossing changes.
 */
fun EditorSession.moveTrackKey(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): Int {
	// Within the parameter's range a key may cross its neighbours freely; the grid re-sorts and permutes
	// its cells to match.  Only the range itself is a wall - a key outside it can never be reached again.
	val clamped = clampToParameterRange(toValue, parameter)
	// Resolved BEFORE the mutate, against the grid this ordinal still refers to.  A crossing renumbers the
	// axis, so afterwards the ordinal names a different key - which is exactly why the caller has to be
	// told the new one rather than left holding the old.
	val landedIndex = model.value.trackKeyIndexAfterMove(track, parameter, keyIndex, toValue)
	mutate(KeyformChange.MoveKey(channelOf(track))) { model ->
		model.withTrackKeyMoved(track, parameter, keyIndex, clamped)
	}
	return landedIndex
}

/**
 * The ordinal a key inserted at [position] on [track] would take, or -1 when the insert adds none.
 *
 * The insert counterpart of [trackKeyIndexAfterMove], and needed for the same reason: an insert renumbers
 * every key at or above it, so the keyform sheet has to correct its key selection to the ordinal the
 * selected key lands on - and it has to do so before the insert records its step, since the selection rides
 * that snapshot.
 *
 * Clamps [position] exactly as [insertTrackKeyAt] does, so the query cannot answer for a destination the
 * insert would never use.
 *
 * @param KeyformTrackRef track The track to insert into.
 * @param Parameter parameter The parameter whose axis the key goes on.
 * @param Float position The new key's requested parameter value.
 * @return Int The new key's ordinal, or -1 when the track does not resolve or a key already sits there.
 */
fun PuppetModel.trackKeyIndexAfterInsert(track: KeyformTrackRef, parameter: Parameter, position: Float): Int =
	trackGridOf(this, track)?.keyIndexAfterInsert(parameter.id, clampToParameterRange(position, parameter)) ?: -1

/**
 * The ordinal [keyIndex] on [track] would hold after being moved to [toValue] - pure, nothing recorded.
 *
 * Public for the same reason [limitedDragFraction] is: the keyform sheet has to know where a key will land
 * BEFORE the move records its step, because the sheet's key selection rides that step's snapshot and a
 * selection staged afterwards reaches no history entry at all.  Preview, stage, and commit must agree about
 * the landing, so they call one function rather than reimplementing the crossing rule.
 *
 * Clamps [toValue] exactly as [moveTrackKey] does, which is why the clamp lives here and not at either
 * call site - a query that answered for an unclamped destination would disagree with the move it describes.
 *
 * @param KeyformTrackRef track The track the key sits on.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's current ordinal on that axis.
 * @param Float toValue The requested destination.
 * @return Int The ordinal after the move, or [keyIndex] when the track no longer resolves.
 */
fun PuppetModel.trackKeyIndexAfterMove(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): Int =
	trackGridOf(this, track)
		?.keyIndexAfterMove(parameter.id, keyIndex, clampToParameterRange(toValue, parameter))
		?: keyIndex

/**
 * Inserts a shape-preserving key at [position] on [track], as one undo step.
 *
 * Shape-preserving means the new key holds whatever the track already evaluates to there, so the insert is
 * a handle to edit from rather than an edit in itself.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 */
fun EditorSession.insertTrackKeyAt(track: KeyformTrackRef, parameter: Parameter, position: Float) {
	// Clamped like a move: the lane's edge inset extrapolates a hit past the domain ends, and an unclamped
	// insert would author a key outside the range that no scrub can ever reach.
	val clamped = clampToParameterRange(position, parameter)
	mutate(KeyformChange.InsertKey(channelOf(track))) { model ->
		model.withTrackKeyInserted(track, parameter, clamped, pose.value)
	}
}

/**
 * Moves every key in [keys] to [toValue] as ONE undo step - the summary-row drag.
 *
 * They go to the same place rather than by the same delta because that is what dragging a summary mark
 * means: the marks were stacked at one value and stay stacked.  Each still lands through its own track's
 * `withKeyMoved`, so a key that would collide with a neighbour on ITS track is nudged the sub-pixel
 * distance that keeps the span resolvable - the group can drift apart by at most EPS_SPAN, which is far
 * below anything the sheet can draw.
 *
 * @param List keys The (track, parameter, key ordinal) triples to move.
 * @param Float toValue The destination, in the parameter's units.
 */
fun EditorSession.moveTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, toValue: Float) {
	if (keys.isEmpty()) {
		return
	}
	mutate(KeyformChange.MoveKey(channelOf(keys.first().first))) { model ->
		keys.fold(model) { current, (track, parameter, keyIndex) ->
			current.withTrackKeyMoved(track, parameter, keyIndex, clampToParameterRange(toValue, parameter))
		}
	}
}

/**
 * [fraction] reduced to what every key in [keys] can actually take without leaving its parameter's range.
 *
 * Public because the sheet PREVIEWS a group drag before committing it, and a preview drawn at the
 * unclamped fraction would show the marks travelling past a wall they are about to stop at - which is the
 * snap-back this exists to prevent.  Preview and commit must be the same number, so they call the same
 * function rather than reimplementing the bound.
 *
 * @param List keys The (track, parameter, key ordinal) triples being dragged.
 * @param Float fraction The requested drag as a signed fraction of each parameter's range.
 * @return Float The fraction the whole group can take - same sign, or zero when it cannot move.
 */
fun PuppetModel.limitedDragFraction(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, fraction: Float): Float =
	keyDragsOf(keys).filterNotNull().fold(fraction) { limited, move -> move.limitedTo(limited) }

/**
 * One [KeyDrag] per entry of [keys], null where the ref no longer resolves.
 *
 * Aligned WITH keys rather than compacted: a caller gets one answer back per key it passed, so a stale ref
 * must not shift every later answer by one.
 *
 * @param List keys The (track, parameter, key ordinal) triples.
 * @return List The drags, position for position.
 */
private fun PuppetModel.keyDragsOf(keys: List<Triple<KeyformTrackRef, Parameter, Int>>): List<KeyDrag?> =
	keys.map { (track, parameter, keyIndex) ->
		trackKeyValue(track, parameter, keyIndex)?.let { fromValue -> KeyDrag(track, parameter, fromValue) }
	}

/**
 * Drags every key in [keys] by [fraction] of its parameter's range, as ONE undo step - the multi-select
 * mark drag.
 *
 * By a fraction rather than an absolute delta because a selection can span two parameters (a linked pad
 * shows both axes at once) whose ranges differ by orders of magnitude.  Within ONE parameter a fraction of
 * its range IS the absolute delta, so the common case is exactly what the hand did; across two it is the
 * only reading that keeps the group together on screen.
 *
 * Clamped to the MOST CONSTRAINED member rather than per key: clamping individually would let the keys
 * nearest an end pile up while the rest kept travelling, silently destroying the spacing the rigger
 * authored.  Reducing the whole drag instead just stops the group at the wall.
 *
 * Each key is re-found BY VALUE inside the fold rather than trusted to keep its ordinal: a key that crosses
 * an unselected neighbour renumbers the axis under the keys still to be moved.
 *
 * @param List keys The (track, parameter, key ordinal) triples to drag.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @return List<Int> Each key's ordinal AFTER the drag, in the order given - crossings renumber the axis, so
 *   a caller holding the old ordinals would be pointing at whichever keys took their place.
 */
fun EditorSession.dragTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, fraction: Float): List<Int> {
	val plan = model.value.planTrackKeyDrag(keys, fraction) ?: return keys.map { (_, _, keyIndex) -> keyIndex }
	// Computed before the mutate rather than inside it because the landing VALUES have to come back out, and
	// mutate hands back only a model.  It applies the transform exactly once against this same instance, so
	// handing it the finished model is equivalent - including the reference-equality no-op check, which
	// still fires when nothing moved.
	mutate(KeyformChange.MoveKey(channelOf(keys.first().first))) { plan.model }
	return plan.landedOrdinals
}

/**
 * Where every key in [keys] would land if dragged by [fraction] - pure, nothing recorded.
 *
 * The query half of [dragTrackKeys], public for the reason [trackKeyIndexAfterMove] spells out: the sheet
 * has to re-point its key selection at the landed ordinals BEFORE the drag records its step, because the
 * selection rides that step's snapshot.  Both call the same planner, so the answer cannot drift from the
 * drag it describes.
 *
 * @param List keys The (track, parameter, key ordinal) triples being dragged.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @return List<Int> Each key's ordinal after the drag, in the order given - the starting ordinals when the
 *   drag would move nothing.
 */
fun PuppetModel.trackKeyDragLandings(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, fraction: Float): List<Int> =
	planTrackKeyDrag(keys, fraction)?.landedOrdinals ?: keys.map { (_, _, keyIndex) -> keyIndex }

/**
 * A planned group drag: the model it would produce, and the ordinal each key would hold in it.
 *
 * @property PuppetModel model The model with every drag applied.
 * @property List<Int> landedOrdinals Each key's post-drag ordinal, aligned with the keys as given.
 */
private class KeyDragPlan(val model: PuppetModel, val landedOrdinals: List<Int>)

/**
 * Plans the group drag [dragTrackKeys] applies, or null when it would move nothing.
 *
 * Pure over this model, which is what lets the sheet ask for the landings and then commit the same drag.
 * Null rather than a plan whose model is this one, so a caller cannot mistake "refused" for "applied".
 *
 * @param List keys The (track, parameter, key ordinal) triples to drag.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @return KeyDragPlan? The plan, or null when the drag is empty, unresolvable, or clamped to nothing.
 */
private fun PuppetModel.planTrackKeyDrag(
	keys: List<Triple<KeyformTrackRef, Parameter, Int>>,
	fraction: Float,
): KeyDragPlan? {
	if (keys.isEmpty() || fraction == 0f) {
		return null
	}
	val moves = keyDragsOf(keys)
	val live = moves.filterNotNull()
	if (live.isEmpty()) {
		return null
	}
	val effectiveFraction = live.fold(fraction) { limited, move -> move.limitedTo(limited) }
	if (effectiveFraction == 0f) {
		return null
	}
	val destinations = live.associateWith { move -> move.destinationAt(effectiveFraction) }
	// Nearest the destination first, so a run of keys shuffling the same way cannot have an earlier one
	// clamp against a neighbour that is about to move out of the way.
	val ordered = if (effectiveFraction > 0f) live.sortedByDescending { it.fromValue } else live.sortedBy { it.fromValue }
	val outcome = withKeyDragsApplied(ordered, destinations)
	val landedOrdinals =
		keys.mapIndexed { position, (track, parameter, keyIndex) ->
			// Resolved by where the key ACTUALLY landed, not by where it was sent.  A key dropped onto a
			// neighbour is nudged EPS_SPAN aside, which is wider than the EPS_KEY tolerance this lookup uses -
			// so asking for the requested value would find the key already sitting there and hand the caller
			// ITS ordinal, silently moving the selection onto the wrong mark (and merging two of them into one).
			val landed = moves[position]?.let { move -> outcome.landedValueByMove[move] } ?: return@mapIndexed keyIndex
			outcome.model.trackKeyIndexAt(track, parameter, landed).takeIf { index -> index >= 0 } ?: keyIndex
		}
	return KeyDragPlan(outcome.model, landedOrdinals)
}

/**
 * The result of applying a batch of key drags: the new model, and where each key actually ended up.
 *
 * The landing values are not the requested destinations.  A key dropped within EPS_SPAN of a neighbour is
 * nudged aside, and one that cannot be nudged anywhere resolvable does not move at all - so the only
 * description of a key that survives its own move is the value it truly holds afterwards.
 *
 * @property PuppetModel model The model with every drag applied.
 * @property Map landedValueByMove Each drag's true landing value.
 */
private class KeyDragOutcome(val model: PuppetModel, val landedValueByMove: Map<KeyDrag, Float>)

/**
 * This model with [ordered] applied in sequence, reporting where each key truly landed.
 *
 * Pure, and applied in the given order because each move reshapes the axis the next one is found on.
 *
 * @param List ordered The drags, already sorted into the direction of travel.
 * @param Map destinations Each drag's requested destination.
 * @return KeyDragOutcome The new model plus each drag's true landing value.
 */
private fun PuppetModel.withKeyDragsApplied(ordered: List<KeyDrag>, destinations: Map<KeyDrag, Float>): KeyDragOutcome {
	val landed = HashMap<KeyDrag, Float>(ordered.size)
	var working = this
	for (move in ordered) {
		// Re-found BY VALUE, not by the ordinal the caller passed: an earlier key crossing an unselected
		// neighbour renumbers the axis under the keys still to be moved.
		val liveIndex = working.trackKeyIndexAt(move.track, move.parameter, move.fromValue)
		if (liveIndex < 0) {
			continue
		}
		val requested = destinations.getValue(move)
		// Asked BEFORE the move, off the same grid withTrackKeyMoved will consult, so the answer is the one
		// that is about to be applied.  Null is a refusal - the key stays exactly where it is.
		landed[move] = working.trackKeyDestination(move.track, move.parameter, liveIndex, requested) ?: move.fromValue
		working = working.withTrackKeyMoved(move.track, move.parameter, liveIndex, requested)
	}
	return KeyDragOutcome(working, landed)
}

/**
 * Where [track]'s key at [keyIndex] would land if moved toward [requested] - the collision nudge applied.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float requested The requested destination.
 * @return Float? The value it would take, or null when the move cannot apply.
 */
private fun PuppetModel.trackKeyDestination(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	requested: Float,
): Float? = trackGridOf(this, track)?.keyDestinationFor(parameter.id, keyIndex, requested)

/**
 * One key's in-flight drag: where it is now, and what bounds how far it may go.
 *
 * @property KeyformTrackRef track The track it sits on.
 * @property Parameter parameter The parameter whose axis it sits on.
 * @property Float fromValue Its position before the drag.
 */
private class KeyDrag(val track: KeyformTrackRef, val parameter: Parameter, val fromValue: Float) {
	/** The parameter's span, floored so a degenerate (min == max) parameter cannot divide by zero. */
	private val span: Float get() = (maxOf(parameter.min, parameter.max) - minOf(parameter.min, parameter.max)).takeIf { it > 0f } ?: 1f

	/**
	 * [fraction] reduced, if needed, so this key stays inside its parameter's range.
	 *
	 * @param Float fraction The requested drag.
	 * @return Float The fraction this key can take, same sign or zero.
	 */
	fun limitedTo(fraction: Float): Float {
		val low = minOf(parameter.min, parameter.max)
		val high = maxOf(parameter.min, parameter.max)
		return if (fraction > 0f) {
			minOf(fraction, ((high - fromValue) / span).coerceAtLeast(0f))
		} else {
			maxOf(fraction, ((low - fromValue) / span).coerceAtMost(0f))
		}
	}

	/**
	 * Where this key lands at [fraction] of its parameter's range.
	 *
	 * @param Float fraction The (already limited) drag.
	 * @return Float The destination, clamped defensively to the range.
	 */
	fun destinationAt(fraction: Float): Float =
		(fromValue + span * fraction).coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max))
}

/**
 * The parameter value key [keyIndex] currently sits at on [track], or null when there is no such key.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return Float? Its current position, or null.
 */
fun PuppetModel.trackKeyValue(track: KeyformTrackRef, parameter: Parameter, keyIndex: Int): Float? {
	val axis =
		trackGridOf(this, track)?.axes?.firstOrNull { candidate -> candidate.parameterId == parameter.id }
			?: return null
	return axis.keys.getOrNull(keyIndex)
}

/**
 * The ordinal of [track]'s key at the current [pose] on [parameter]'s axis, or -1 when the pose is not on
 * one.
 *
 * Within EPS_KEY, the same tolerance `bindBracket` snaps with - so "on a key" here means exactly what it
 * means to the evaluator, and the field tint, the shortcut, and the rendered result cannot disagree about
 * whether the pose is sitting on a key.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Pose pose The pose to resolve against.
 * @return Int The key's ordinal, or -1.
 */
fun PuppetModel.trackKeyIndexAtPose(track: KeyformTrackRef, parameter: Parameter, pose: Pose): Int {
	val grid = trackGridOf(this, track) ?: return -1
	return grid.keyIndexAt(parameter.id, pose[parameter.id] ?: parameter.default)
}

/**
 * The ordinal of [track]'s key sitting at [value] on [parameter]'s axis, or -1 when none does.
 *
 * Within EPS_KEY, the same tolerance the evaluator snaps with - so "the key at this value" means what it
 * means everywhere else.  The value-addressed sibling of [trackKeyIndexAtPose], for the one case where an
 * ordinal cannot be trusted: after a move that may have crossed a neighbour and renumbered the axis.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Float value The parameter value to look at.
 * @return Int The key's ordinal, or -1.
 */
fun PuppetModel.trackKeyIndexAt(track: KeyformTrackRef, parameter: Parameter, value: Float): Int =
	trackGridOf(this, track)?.keyIndexAt(parameter.id, value) ?: -1

/** The grid behind [track] - its owner's channel track or its geometry - or null when there is none. */
private fun trackGridOf(puppet: PuppetModel, track: KeyformTrackRef): KeyformGrid<*>? =
	when (track) {
		is KeyformTrackRef.Channel -> puppet.channelGridsOf(track.owner)?.get(track.target.channel)
		is KeyformTrackRef.Geometry -> puppet.geometryGridOf(track.owner)
	}

/**
 * Removes every key in [keys] as ONE undo step - the sheet's Delete over a multi-key selection.
 *
 * One step rather than one per key because the user made one gesture; a per-key step would make undoing a
 * five-key delete take five presses.  Applied in DESCENDING index order so each removal cannot renumber
 * the keys the later ones still have to find.
 *
 * @param List keys The (track, parameter, key ordinal) triples to remove.
 */
fun EditorSession.removeTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>) {
	if (keys.isEmpty()) {
		return
	}
	mutate(KeyformChange.DeleteKey(channelOf(keys.first().first))) { model ->
		keys
			.sortedByDescending { (_, _, keyIndex) -> keyIndex }
			.fold(model) { current, (track, parameter, keyIndex) ->
				current.withTrackKeyRemoved(track, parameter, keyIndex)
			}
	}
}