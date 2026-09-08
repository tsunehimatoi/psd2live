package org.umamo.edit

import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.channelGridsOf

/*
 * What "key this" and "unkey this" MEAN when the user asks for them - the intent layer over the concrete
 * ops in KeyformTrackEdits / KeyformChannelEdits / KeyformGeometryEdits.
 *
 * Three questions live here: which parameter an edit writes on, whether it acts where the user pointed or
 * where the pose stands, and whether a typed value becomes a pending edit or a static write.  All three are
 * editing SEMANTICS, so they belong in this module - a panel row, a keypress, and a lane context menu must
 * answer them identically, and none of them is the right place to decide.
 *
 * This is also why these are not in :ui, where they started out: taking a Compose-side hover type put
 * authoring rules in the UI module and made them untestable without a composition.  A :ui caller now passes
 * plain data - a track, an optional parameter id, and a KeyformAim - and keeps only the pointer bookkeeping
 * that produced them.
 */

/**
 * Where a keyform edit is aimed: at a place the user named, or at the pose the rig is standing in.
 *
 * The distinction is not cosmetic.  Pointing at a spot on a track is a statement about WHICH spot, so a key
 * lands there rather than at the playhead; and pointing at a track but not at a key means "that key, of
 * which there is none", which must remove nothing rather than falling back to the pose and destroying a key
 * the user never pointed at.
 */
sealed interface KeyformAim {
	/** No place was named, so the edit acts at the current pose - a property row, or a bare keypress. */
	data object Pose : KeyformAim

	/**
	 * A named place on the track's axis.
	 *
	 * @property Float position The parameter value pointed at.
	 * @property Int? keyIndex The ordinal of the key sitting there, or null when the pointer is between keys.
	 */
	data class Position(val position: Float, val keyIndex: Int?) : KeyformAim
}

/**
 * What a deferred keyform edit will do once the user picks the axis it writes on.
 */
enum class KeyformAction {
	/** Insert a key ([captureKeyOnTrack]). */
	Capture,

	/** Remove a key ([removeKeyOnTrack]). */
	Remove,
}

/**
 * A keyform edit held back because more than one parameter is targeted and the user never named one.
 *
 * The linked-pad case: a 2D pad targets both its axes but reports only the HORIZONTAL one as active, so an
 * unaimed edit would silently always write there and the vertical section would keep reading as unkeyed.
 * Guessing is the one thing that must not happen - the wrong axis is authored work in the wrong place - so
 * the edit parks here and the shell asks.
 *
 * Carries the whole edit rather than just the question, so answering it is a plain replay through the same
 * entry point with the axis filled in; the UI decides nothing but which name was clicked.
 *
 * @property KeyformTrackRef track The track the edit was aimed at.
 * @property KeyformAim aim Where on that track it lands.
 * @property KeyformAction action Which edit is waiting.
 * @property List candidates The targeted parameters to choose between, in model order.
 * @property String? rowKey The keyform-sheet row the aim came from, carried so the replay can reconcile the
 *   key selection exactly as an unparked edit does; null when the aim came from somewhere with no sheet row.
 */
data class ParameterChoiceRequest(
	val track: KeyformTrackRef,
	val aim: KeyformAim,
	val action: KeyformAction,
	val candidates: List<ParameterId>,
	val rowKey: String? = null,
)

/**
 * The targeted parameters an unaimed edit would have to choose between, or null when there is no choice.
 *
 * Null - meaning "just proceed" - whenever the caller named a parameter (a sheet lane always does, and its
 * lane is the answer), or fewer than two of the targeted ids still resolve in the model.
 *
 * @param ParameterId? parameterId The axis the caller named, or null.
 * @return List? The candidates to ask about, or null to proceed without asking.
 */
private fun EditorSession.parameterChoiceFor(parameterId: ParameterId?): List<ParameterId>? {
	if (parameterId != null) {
		return null
	}
	val targeted = parameterSelection.value.ids
	if (targeted.size < 2) {
		return null
	}
	// Model order, not set order: the prompt has to list the axes the way the panel does, and a Set's
	// iteration order is an implementation detail the user would experience as the menu reshuffling.
	val candidates = model.value.parameters.map { parameter -> parameter.id }.filter { id -> id in targeted }
	return if (candidates.size < 2) null else candidates
}

/**
 * Answers the pending [ParameterChoiceRequest] by writing on [parameterId], replaying the parked edit.
 *
 * Replayed through the ordinary entry point with the axis now named, so the answered edit takes exactly the
 * path an aimed one takes - there is no second implementation of what an insert or a removal means.
 *
 * @param ParameterId parameterId The axis the user picked.
 */
fun EditorSession.resolveParameterChoice(parameterId: ParameterId) {
	val request = pendingParameterChoice.value ?: return
	cancelParameterChoice()
	when (request.action) {
		KeyformAction.Capture -> captureKeyOnTrack(request.track, parameterId, request.aim, request.rowKey)
		KeyformAction.Remove -> removeKeyOnTrack(request.track, parameterId, request.aim, request.rowKey)
	}
}

/**
 * Captures a key on [track] at [aim], keyed on the parameter the edit writes on.
 *
 * An aimed capture inserts a shape-preserving key at that position, so it holds whatever the track already
 * evaluates to there.  A capture at the pose stores the PENDING unkeyed edit when there is one, else the
 * channel's current evaluated value - so an insert after typing stores what was typed, and an insert with
 * nothing edited pins what is already on screen (Blender's behaviour, and how a rigger anchors a pose before
 * moving on).
 *
 * Refuses with a notice rather than silently doing nothing when no parameter resolves, since that is a thing
 * the user can fix.
 *
 * When no parameter is named and more than one is targeted (a linked pad), the edit parks as a
 * [ParameterChoiceRequest] instead of writing on whichever axis happens to be active.
 *
 * Reconciles the keyform sheet's key selection when [rowKey] names the row the aim came from, exactly as
 * [removeKeyOnTrack] does: the new key renumbers every key at or above where it lands, so a selection on
 * one of those keys must shift up by one to keep naming the same key.
 *
 * @param KeyformTrackRef track The track to key.
 * @param ParameterId? parameterId The axis to key on, or null to use the session's targeted parameter.
 * @param KeyformAim aim Where the key lands.
 * @param String? rowKey The sheet row the aim came from, or null when it came from somewhere without one.
 */
fun EditorSession.captureKeyOnTrack(
	track: KeyformTrackRef,
	parameterId: ParameterId?,
	aim: KeyformAim,
	rowKey: String? = null,
) {
	parameterChoiceFor(parameterId)?.let { candidates ->
		requestParameterChoice(ParameterChoiceRequest(track, aim, KeyformAction.Capture, candidates, rowKey))
		return
	}
	val parameter = keyformParameterFor(parameterId) ?: return
	// An aimed capture keys where it was pointed; every other kind keys at the pose.  Resolved once, because
	// it is both where the key goes and what the ordinal query has to be asked about.
	val keyPosition =
		when (aim) {
			is KeyformAim.Position -> aim.position
			is KeyformAim.Pose -> pose.value[parameter.id] ?: parameter.default
		}
	val inserted = insertedKeyRef(track, parameter, keyPosition, rowKey)
	if (aim is KeyformAim.Position) {
		insertingKey(inserted) { insertTrackKeyAt(track, parameter, aim.position) }
		return
	}
	when (track) {
		is KeyformTrackRef.Channel -> {
			val target = track.target
			// Resolved before the wrap, because a channel with no value to capture must leave the selection
			// alone rather than reconcile it around an edit that never happens.
			val value = pendingChannelEdits.value[target] ?: model.value.channelValueAt(target, pose.value) ?: return
			insertingKey(inserted) {
				// Retired BEFORE the capture records its step, exactly as the unkeyed branch of editKeyedChannel
				// does below and for the same reason: a snapshot defaults every field to live state, so clearing
				// afterwards leaves the consumed value inside the step that consumed it - and redo then re-shows
				// the uncommitted-edit warning over the very key that now stores the value.
				//
				// Only THIS target's pending edit is consumed; the pose did not move, so any other target's typed
				// value is still the value its user chose and must survive for its own capture.
				clearPendingChannelEdit(target)
				captureChannelKey(target, parameter, value)
			}
		}

		// Geometry holds no value the user can have typed, so there is nothing to capture - the key goes in
		// at the pose and holds the shape already there.
		is KeyformTrackRef.Geometry -> insertingKey(inserted) { insertTrackKeyAt(track, parameter, keyPosition) }
	}
}

/**
 * The [TrackKeyRef] a key inserted at [position] would take, or null when nothing new is added.
 *
 * Null whenever the selection cannot be affected: the caller named no sheet row, or a key already sits at
 * [position] so the capture overwrites a cell rather than renumbering the axis.
 *
 * @param KeyformTrackRef track The track the key goes on.
 * @param Parameter parameter The parameter whose axis it goes on.
 * @param Float position The new key's parameter value.
 * @param String? rowKey The sheet row the aim came from, or null when it came from somewhere without one.
 * @return TrackKeyRef? The new key's ref, or null when no ordinal renumbers.
 */
private fun EditorSession.insertedKeyRef(
	track: KeyformTrackRef,
	parameter: Parameter,
	position: Float,
	rowKey: String?,
): TrackKeyRef? {
	if (rowKey == null) {
		return null
	}
	val keyIndex = model.value.trackKeyIndexAfterInsert(track, parameter, position)
	return if (keyIndex < 0) null else TrackKeyRef(parameter.id, rowKey, keyIndex)
}

/**
 * Removes the key [aim] names from [track], on the parameter the edit writes on.
 *
 * Either way the key is an ORDINAL, and either way aiming at nothing removes nothing: picking "the nearest
 * key" would be a guess at which one the user meant, and a wrong guess silently destroys authored work.
 *
 * Parks as a [ParameterChoiceRequest] on an ambiguous target, exactly as [captureKeyOnTrack] does - a
 * removal aimed at the wrong axis destroys authored work, so it is the LAST thing to guess at.
 *
 * Reconciles the keyform sheet's key selection when [rowKey] names the row the aim came from - the removal
 * renumbers the keys above it, so a selection left alone would end up naming the neighbour.  Done HERE
 * rather than at each caller because this is where the ordinal is finally resolved, and the aim may not
 * carry one at all (a pose aim resolves it against the track).
 *
 * @param KeyformTrackRef track The track to unkey.
 * @param ParameterId? parameterId The axis to remove from, or null to use the session's targeted parameter.
 * @param KeyformAim aim The key pointed at, or [KeyformAim.Pose] to take the key the pose stands on.
 * @param String? rowKey The sheet row the aim came from, or null when it came from somewhere without one.
 */
fun EditorSession.removeKeyOnTrack(
	track: KeyformTrackRef,
	parameterId: ParameterId?,
	aim: KeyformAim,
	rowKey: String? = null,
) {
	parameterChoiceFor(parameterId)?.let { candidates ->
		requestParameterChoice(ParameterChoiceRequest(track, aim, KeyformAction.Remove, candidates, rowKey))
		return
	}
	val parameter = keyformParameterFor(parameterId) ?: return
	val keyIndex =
		when (aim) {
			is KeyformAim.Position -> aim.keyIndex ?: -1
			is KeyformAim.Pose -> model.value.trackKeyIndexAtPose(track, parameter, pose.value)
		}
	if (keyIndex < 0) {
		return
	}
	// An empty set when the caller has no row to name, which leaves the selection untouched.
	val removed = rowKey?.let { row -> setOf(TrackKeyRef(parameter.id, row, keyIndex)) }.orEmpty()
	removingKeys(removed) {
		removeTrackKeys(listOf(Triple(track, parameter, keyIndex)))
	}
}

/**
 * Routes a keyable property's typed or picked [value]: a KEYED channel records it as a pending unkeyed
 * edit, an unkeyed channel writes the static through [writeStatic].
 *
 * The one place the rule lives.  Writing the static of a keyed channel is shadowed by the track, so the
 * edit appears to be silently rejected (the field snaps back and a following `I` captures the old track
 * value); a pending edit previews in the viewport and waits for `I`.  An unkeyed channel has no track to
 * shadow it, so the static is the real store.  Reimplementing this branch at each property row instead of
 * calling here would let the two halves drift: some rows would apply the guard and others would silently
 * skip it.
 *
 * Both branches record exactly one undo step, and both record it under [change] - which of the two a given
 * channel takes depends on whether it happens to carry a track, which is not a distinction the rigger made.
 *
 * @param KeyableTarget target The entity and channel being edited.
 * @param ChannelValue value The value the user chose.
 * @param Change change The descriptor of this edit, used by whichever branch runs.
 * @param Function writeStatic Writes the owner's static (the unkeyed path); the caller supplies it because
 *   which setter that is depends on the owner and channel.
 */
fun EditorSession.editKeyedChannel(
	target: KeyableTarget,
	value: ChannelValue,
	change: Change,
	writeStatic: () -> Unit,
) {
	val keyed = model.value.channelGridsOf(target.owner)?.get(target.channel) != null
	if (keyed) {
		commitPendingChannelEdit(target, value, change)
	} else {
		// A scrub previews through the pending buffer even on an unkeyed channel (see previewChannelEdit),
		// so the static write has to retire that preview - otherwise the stale pending value keeps
		// shadowing the very static just committed, and the field stays tinted as an uncommitted edit.
		clearPendingChannelEdit(target)
		writeStatic()
	}
}

/**
 * Previews [value] on [target] without committing anything - what a field reports while being scrubbed.
 *
 * Always the pending buffer, whether or not the channel is keyed.  For a keyed channel that is already
 * where a committed edit lands, so preview and commit coincide; for an unkeyed one it is the difference
 * between a live viewport and a history entry per pointer frame.  [editKeyedChannel] retires the preview
 * when the gesture ends.
 *
 * @param KeyableTarget target The entity and channel being scrubbed.
 * @param ChannelValue value The in-flight value.
 */
fun EditorSession.previewChannelEdit(target: KeyableTarget, value: ChannelValue) {
	setPendingChannelEdit(target, value)
}

/**
 * The parameter a keyform edit writes on: [parameterId] when the caller named one (a sheet lane belongs to
 * one section's axis, which need not be the selection's active member), else the targeted parameter.
 *
 * Emits the no-parameter notice and returns null when the named parameter no longer resolves.
 *
 * @param ParameterId? parameterId The axis the caller named, or null to fall back to the target.
 * @return Parameter? The parameter to write on, or null.
 */
private fun EditorSession.keyformParameterFor(parameterId: ParameterId?): Parameter? {
	val namedId = parameterId ?: return targetedParameter()
	val parameter = model.value.parameters.firstOrNull { candidate -> candidate.id == namedId }
	if (parameter == null) {
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
	}
	return parameter
}

/**
 * The parameter a keyform edit falls back to - the active member of the session's parameter selection.
 *
 * Emits a notice and returns null when nothing is targeted, since an edit with no axis to write on has no
 * sensible default: guessing a parameter would key the rig somewhere the user never looked.
 *
 * @return Parameter? The targeted parameter, or null.
 */
private fun EditorSession.targetedParameter(): Parameter? {
	val activeId = parameterSelection.value.active
	if (activeId == null) {
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
		return null
	}
	val parameter = model.value.parameters.firstOrNull { candidate -> candidate.id == activeId }
	if (parameter == null) {
		// A dangling id (a stale snapshot's target for a parameter since deleted) is as unfixable-by-waiting
		// as no target at all, so it gets the same notice rather than a silent no-op.
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
	}
	return parameter
}