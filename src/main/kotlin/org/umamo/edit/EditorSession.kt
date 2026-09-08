package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.firstEditableDrawableInPanelOrder

/**
 * Where the shell surfaces a [Notice]: the status-bar slot, or a transient label next to the pointer
 * (Blender's near-cursor "can't do this because" style, for feedback about a blocked viewport gesture).
 */
enum class NoticePlacement {
	StatusBar,
	NearCursor,
}

/**
 * A transient user notice: a stable message key plus a monotonic [serial] that distinguishes it from an
 * identical earlier message, so the UI can re-time its dismissal even when the same notice repeats.
 * Carries a key rather than display text so this module stays presentation-free and the UI layer resolves
 * the localized string (the same pattern as [Change.labelKey]).
 *
 * @property String messageKey The stable notice key the UI layer resolves to a localized message.
 * @property Long serial The stamping order (see [EditorSession.emitNotice]); higher is newer.
 * @property NoticePlacement placement Where the shell surfaces this notice.
 */
data class Notice(val messageKey: String, val serial: Long, val placement: NoticePlacement)

/**
 * The single mutable owner of one open document: the live [model], the ephemeral editor state
 * ([selection], [mode]), the undo [History], and the change-event bus. Every edit flows through here so
 * undo, change events, and dirty-tracking stay consistent.
 *
 * Two channels, per the history design: [changes] emits on every mutation (the "everything emits a
 * change event" completeness), while the history stack records only the undoable subset. A document
 * mutation ([mutate]) and a selection gesture ([setSelection]) each push one step; a mode toggle
 * ([setMode]) and future tool/brush switches are transient — they emit but never become steps.
 *
 * Undo is by immutable snapshot, never inverse op: [undo] / [redo] just republish a stored
 * [EditorSnapshot]. Dirty is reference-equality of the model against the last-saved instance — because
 * undo restores the exact prior model instance and selection-only steps reuse the same instance,
 * `model !== savedModel` is correct across undo/redo and is never tripped by a bare selection change.
 *
 * Held on the UI thread (Compose drives it); the render host observes [model] / [selection] as flows.
 * Compose-free by design (its module mandate), so it exposes coroutines flows, not Compose state.
 *
 * @param PuppetModel initialModel The document model at open.
 * @param Pose initialPose The pose at open (the displayed scrub values); defaults to every parameter's
 *   default. The host passes the renderer's starting values so the session, the panel, and the viewport
 *   agree from frame one (e.g. a headless dump's overridden pose is not reset to defaults).
 * @param Int initialHistoryLimit The retained-undo-step cap at open; [historyLimit] carries it and the
 *   host reassigns it when the preference changes.
 */
class EditorSession(
	initialModel: PuppetModel,
	initialPose: Pose = initialModel.parameters.associate { parameter -> parameter.id to parameter.default },
	initialHistoryLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
	// The session's collaborators - the undo machinery (stack, saved baseline, derived flags), the
	// area-request buses, the remembered-selection memory, and the tool latches; the members below
	// delegate so the public API is unchanged, and every flow-write ordering stays in this facade.
	private val history = HistoryCore(EditorSnapshot(initialModel, Selection(), initialPose), initialHistoryLimit)
	private val requestBus = SessionRequestBus()
	private val elementMemory = MeshElementMemory()
	private val latches = ToolLatches(notify = ::emitNotice)

	/**
	 * The model this session opened on - the document's own puppet, before any edit.
	 *
	 * Exposed so a host can verify that a session belongs to the document it is about to act on:
	 * a session outlives nothing, but a stale one paired with a fresh document would silently
	 * apply the PREVIOUS model's rig (see the export guard in EditorApp).
	 */
	val baselineModel: PuppetModel = initialModel

	private val mutableModel = MutableStateFlow(initialModel)

	/** The live document model; panels read it, the render host observes it. */
	val model: StateFlow<PuppetModel> = mutableModel.asStateFlow()

	private val mutableSelection = MutableStateFlow(Selection())

	/** The live object-mode selection. */
	val selection: StateFlow<Selection> = mutableSelection.asStateFlow()

	private val mutableParameterSelection = MutableStateFlow(ParameterSelection())

	/**
	 * The parameters targeted for keyform authoring - which parameter an insert would write a key on.
	 *
	 * Independent of [selection]: the object selection says WHAT to key, this says on WHICH AXIS.
	 */
	val parameterSelection: StateFlow<ParameterSelection> = mutableParameterSelection.asStateFlow()

	private val mutablePendingChannelEdits = MutableStateFlow<Map<KeyableTarget, ChannelValue>>(emptyMap())

	/**
	 * Channel values edited but NOT yet keyed - Blender's model, where changing a keyed property off a key
	 * takes effect now but is lost unless you key it.
	 *
	 * Deliberately NOT in the document: a pending edit never reaches [PuppetModel], so it costs nothing in
	 * document terms and a keyform insert can consume it without that being a document edit either. It IS in
	 * the undo history, one step per gesture end (see [commitPendingChannelEdit]) rather than per keystroke -
	 * a per-frame preview ([setPendingChannelEdit]) records nothing on its own. Every pending value is
	 * cleared whenever the pose moves, because a value chosen FOR one pose is meaningless at another; a
	 * history jump does not clear them, since [restore] lands on the very pose they were chosen for and
	 * restores them alongside it.
	 *
	 * The keyed-field tint reads this to show the edited-but-unkeyed state, and a keyform insert consumes
	 * it: the whole point is that `I` captures what you just typed rather than what is still stored.
	 */
	val pendingChannelEdits: StateFlow<Map<KeyableTarget, ChannelValue>> = mutablePendingChannelEdits.asStateFlow()

	private val mutableKeySelection = MutableStateFlow<Set<TrackKeyRef>>(emptySet())

	/**
	 * The keyform sheet's selected keys - what its Delete and nudge commands act on.
	 *
	 * Shell-wide rather than per-sheet: it is snapshotted, and undo restores session state.  Two open sheets
	 * therefore share one selection, which is the same call [parameterSelection] made and for the same
	 * reason - a second sheet showing a different answer to "what is selected" is worse than agreement.
	 */
	val keySelection: StateFlow<Set<TrackKeyRef>> = mutableKeySelection.asStateFlow()

	private val mutablePose = MutableStateFlow(initialPose)

	/** The live pose (parameter scrub values); the render host mirrors it so undo / redo re-poses. */
	val pose: StateFlow<Pose> = mutablePose.asStateFlow()

	private val mutableMode = MutableStateFlow(EditorMode.Object)

	/**
	 * The live interaction mode. Snapshotted (a mode change is its own undo step), and pose-neutral by
	 * contract: entering or leaving Edit mode NEVER writes the pose — Edit mode's rest view is a
	 * display-only override in the render host, so the Object-mode pose survives an Edit session
	 * untouched with no stashed state to juggle.
	 */
	val mode: StateFlow<EditorMode> = mutableMode.asStateFlow()

	private val mutableMeshSelection = MutableStateFlow(MeshSelection())

	/** The live Edit-mode element selection; snapshotted, so a selection gesture is undoable. */
	val meshSelection: StateFlow<MeshSelection> = mutableMeshSelection.asStateFlow()

	/**
	 * The modal mesh operator currently running (Grab / Scale / Rotate) with its initiating viewport area,
	 * or null. Transient UI coordination (not snapshotted, not on the bus): a registry command latches it,
	 * the initiating area's gizmo overlay observes it to drive the gesture (bystander viewports stay
	 * inert), and clears it on confirm / cancel.
	 */
	val activeMeshOperator: StateFlow<ActiveOperator?> = latches.activeMeshOperator

	/**
	 * The modal OBJECT operator currently running (Grab / Scale / Rotate over the selected drawables' whole
	 * geometry) with its initiating viewport area, or null. The Object-mode sibling of [activeMeshOperator]:
	 * a separate latch because the object overlay captures N drawables where the mesh overlay captures one,
	 * so the two must be distinguishable. Transient UI coordination like [activeMeshOperator] - not
	 * snapshotted, not on the bus - latched by a registry command, observed by the initiating area's object
	 * gizmo overlay, cleared on confirm / cancel / leaving Object mode.
	 */
	val activeObjectOperator: StateFlow<ActiveOperator?> = latches.activeObjectOperator

	/**
	 * The modal UV operator currently running (Grab / Scale / Rotate over the selected vertices' texture
	 * coordinates) with its initiating UV-editor area, or null. The UV-editor sibling of
	 * [activeMeshOperator]: a separate latch so the puppet viewport's gizmo overlays and the UV editor's
	 * overlay can never cross-capture one gesture (each overlay's capture effect keys on its own latch).
	 * Transient UI coordination like the others - not snapshotted, not on the bus - latched by a registry
	 * command, observed by the initiating area's UV overlay, cleared on confirm / cancel / leaving Edit mode.
	 */
	val activeUvOperator: StateFlow<ActiveOperator?> = latches.activeUvOperator

	/**
	 * The one modal transform operator running, from whichever of the three families latched it, or null.
	 *
	 * The families are mutually exclusive, so callers that only need to know whether SOME transform is in
	 * flight - the shell's modal key ladder, deciding who owns Escape / Enter / the axis keys - ask this
	 * instead of testing all three.  An instantaneous read, not a flow: see [ToolLatches.activeOperator].
	 */
	val activeOperator: ActiveOperator?
		get() = latches.activeOperator

	/**
	 * Cancels whichever modal transform operator is running, if any - the family-agnostic counterpart to
	 * [clearMeshOperator] / [clearObjectOperator] / [clearUvOperator].
	 */
	fun clearActiveOperator() {
		latches.clearActiveOperator()
	}

	/**
	 * The transient preview of which drawables an in-flight Object-mode circle stroke is painting, or null when
	 * no stroke is live. The GPU-tint bridge overlays this on top of the committed [selection] so painted
	 * drawables light up immediately without committing each frame (which would spam undo). Not snapshotted,
	 * not on the bus (transient UI coordination like [activeSelectTool]); the stroke commits once on release
	 * via [setSelection] and clears this back to null.
	 */
	val previewSelection: StateFlow<Set<DrawableId>?> = latches.previewSelection

	/**
	 * Publishes the transient circle-stroke preview selection (see [previewSelection]); pass null to clear it.
	 *
	 * @param Set<DrawableId>? drawableIds The drawables currently painted by the stroke, or null to clear.
	 */
	fun setPreviewSelection(drawableIds: Set<DrawableId>?) {
		latches.setPreviewSelection(drawableIds)
	}

	/**
	 * True while a viewport overlay is driving a non-armed pointer gesture (today: the Object-mode
	 * un-armed box drag), so the shell can route Escape to a gesture cancel instead of its next Escape
	 * behavior (clearing the object selection). Transient UI coordination like [previewSelection] - not
	 * snapshotted, not on the bus; the overlay sets it at press and clears it on release or cancel.
	 */
	val viewportGestureActive: StateFlow<Boolean> = latches.viewportGestureActive

	/**
	 * Publishes whether a non-armed viewport gesture is in flight (see [viewportGestureActive]).
	 *
	 * @param Boolean active True while the overlay's gesture owns the pointer.
	 */
	fun setViewportGestureActive(active: Boolean) {
		latches.setViewportGestureActive(active)
	}

	private val mutableNotice = MutableStateFlow<Notice?>(null)

	/**
	 * The current transient user notice, or null when none is showing. A short message the shell surfaces
	 * briefly (near the status bar) to explain why an action did nothing. Deliberately off the undo history and
	 * the change bus - a notice is momentary feedback, never document state. Used today when an Object-mode
	 * transform blocks because the selection holds a part, a deformer, or a mesh-less drawable. A StateFlow (not
	 * a one-shot event) so the shell reads it with the standard collectAsState path and a late subscriber still
	 * sees an in-flight notice; the [Notice.serial] lets the shell time its dismissal and re-trigger on a repeat
	 * of the same text.
	 */
	val notice: StateFlow<Notice?> = mutableNotice.asStateFlow()

	// Monotonic id stamped on each notice so an identical repeated message is still a distinct event the shell
	// can re-time. Not a clock (unavailable here) - just a counter.
	private var noticeSerial: Long = 0L

	/**
	 * Emits a transient user notice (see [notice]); it stays current until dismissed via [clearNotice] or
	 * replaced by a newer one.
	 *
	 * @param String messageKey The stable notice key the UI layer resolves to a localized message.
	 * @param NoticePlacement placement Where the shell surfaces the notice.
	 */
	fun emitNotice(messageKey: String, placement: NoticePlacement = NoticePlacement.StatusBar) {
		noticeSerial += 1
		mutableNotice.value = Notice(messageKey, noticeSerial, placement)
	}

	/**
	 * Dismisses the notice with the given [serial], but only if it is still the current one - so a dismissal
	 * timer for an older notice never clears a newer message that arrived in the meantime.
	 *
	 * @param Long serial The serial of the notice to dismiss (from [Notice.serial]).
	 */
	fun clearNotice(serial: Long) {
		if (mutableNotice.value?.serial == serial) {
			mutableNotice.value = null
		}
	}

	/**
	 * The Edit-mode selection tool currently armed (Box or Circle), or null. Transient UI coordination like
	 * [activeMeshOperator] (not snapshotted, not on the bus): a registry command latches it, the gizmo overlay
	 * observes it to reinterpret pointer input, and it clears on completion / cancel / leaving Edit mode.
	 */
	val activeSelectTool: StateFlow<ActiveSelectTool?> = latches.activeSelectTool

	/**
	 * The viewport area id whose Zoom Region gesture is armed (Blender's Shift+B), or null. Mode-agnostic -
	 * Zoom Region works in Object and Edit mode alike - so it is keyed by area rather than gated on the mode,
	 * and the top-level region overlay for that area reads it to capture the drag. Transient, not snapshotted.
	 */
	val zoomRegionArmedArea: StateFlow<String?> = latches.zoomRegionArmedArea

	private val mutableChanges = MutableSharedFlow<Change>(extraBufferCapacity = 64)

	/** The change-event bus: every mutation emits here, the undoable ones and the transient ones alike. */
	val changes: SharedFlow<Change> = mutableChanges.asSharedFlow()

	/** True when the document model differs from the last-saved state (drives the title/status marker). */
	val dirty: StateFlow<Boolean> = history.dirty

	/** True when there is a step to undo (drives the Edit-menu item's enabled state). */
	val canUndo: StateFlow<Boolean> = history.canUndo

	/** True when there is a step to redo. */
	val canRedo: StateFlow<Boolean> = history.canRedo

	/** The undo stack projected for the history panel; updates on every edit, undo, redo, jump, and save. */
	val historyView: StateFlow<HistoryView> = history.historyView

	/**
	 * The retained-undo-step cap.  The host writes it from the user preference - once when the document
	 * opens and again on every committed change - so the session never reads settings itself.
	 *
	 * Lowering it trims the stack at once rather than waiting for the next edit, but never past the live
	 * step, so the current state and its redo branch always survive; the excess sheds on subsequent
	 * pushes.  The write republishes the derived flags, so the panel drops the same rows the stack did and
	 * [canUndo] stays honest when the trim lands the cursor on the oldest entry.
	 */
	var historyLimit: Int
		get() = history.limit
		set(value) {
			history.limit = value
			refreshFlags()
		}

	/**
	 * Applies a document edit: computes the new model via [transform], records it as one undo step, and
	 * publishes it. The [change] describes the edit for the bus and the history-panel label. A transform
	 * that returns the same model instance (a no-op edit) records nothing, so callers need not pre-check.
	 *
	 * @param Change change The descriptor of this edit (its [Change.undoability] is assumed undoable here).
	 * @param Function transform Produces the new model from the current one.
	 */
	fun mutate(change: Change, transform: (PuppetModel) -> PuppetModel) {
		commit(change, transform(mutableModel.value), mutablePose.value)
	}

	/**
	 * Commits one undo step from an already-computed [model] and [pose], recording it and publishing both.
	 * The single choke point behind [mutate] (model edits), [commitPose] (scrubs), and [setParameterRange]
	 * (both at once). A commit that changes neither the model instance nor the pose records nothing, so
	 * callers need not pre-check. Dirty is measured against the model only, so a pose-only commit (a scrub)
	 * is an undo step without marking the document unsaved, exactly like a selection gesture.
	 *
	 * @param Change change The descriptor of this edit (for the bus and the history-panel label).
	 * @param PuppetModel model The new document model (same instance as now for a pose-only commit).
	 * @param Pose pose The new live pose (same value as now for a model-only commit).
	 */

	private fun commit(change: Change, model: PuppetModel, pose: Pose) {
		if (model === mutableModel.value && pose == mutablePose.value) {
			return
		}
		// A pose move invalidates every pending edit: the value was chosen for the pose being left.
		if (pose != mutablePose.value) {
			clearPendingChannelEdits()
		}
		history.push(snapshot(model = model, pose = pose), change)
		mutableModel.value = model
		mutablePose.value = pose
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * A snapshot of the session's current state, with any field overridden.
	 *
	 * Every history push goes through this rather than calling [EditorSnapshot] directly.  The constructor's
	 * own defaults are dangerous here: a field added later would default to its EMPTY value at every existing
	 * call site, which compiles cleanly but would silently record the wrong state - for example, undoing an
	 * unrelated edit would clear the parameter target instead of leaving it as it was.  Defaulting to live
	 * state instead makes the omission harmless.
	 */
	private fun snapshot(
		model: PuppetModel = mutableModel.value,
		selection: Selection = mutableSelection.value,
		pose: Pose = mutablePose.value,
		meshSelection: MeshSelection = mutableMeshSelection.value,
		mode: EditorMode = mutableMode.value,
		parameterSelection: ParameterSelection = mutableParameterSelection.value,
		pendingChannelEdits: Map<KeyableTarget, ChannelValue> = mutablePendingChannelEdits.value,
		keySelection: Set<TrackKeyRef> = mutableKeySelection.value,
	): EditorSnapshot =
		EditorSnapshot(
			model,
			selection,
			pose,
			meshSelection,
			mode,
			parameterSelection,
			pendingChannelEdits,
			keySelection,
		)

	/**
	 * Commits a parameter scrub as one undo step: the live [pose] reached a new resting position (a slider
	 * or 2D-pad gesture released, a value typed, a reset). Mid-gesture preview frames bypass this and reach
	 * the renderer directly, so a whole drag is a single step. The model is unchanged, so this does not
	 * mark the document dirty. A commit equal to the current pose records nothing.
	 *
	 * @param Change change The scrub descriptor (a [ParameterChange.SetValue]).
	 * @param Pose pose The pose to commit (the gesture's final parameter values).
	 */
	fun commitPose(change: Change, pose: Pose) {
		commit(change, mutableModel.value, pose)
	}

	/**
	 * Records [value] as an unkeyed edit of [target] - a value the user typed that is not stored anywhere yet.
	 *
	 * Transient by construction: no history step, no model change.  The next pose move discards it, which is
	 * the behaviour rather than a limitation - the value was chosen FOR this pose, so carrying it to another
	 * would be applying an edit somewhere it was never meant.
	 *
	 * @param KeyableTarget target The property edited.
	 * @param ChannelValue value The value typed.
	 */
	fun setPendingChannelEdit(target: KeyableTarget, value: ChannelValue) {
		mutablePendingChannelEdits.value = mutablePendingChannelEdits.value + (target to value)
	}

	/**
	 * Records [value] as an unkeyed edit of [target] AND as one undo step, described by [change].
	 *
	 * What a keyable property field calls when its gesture ends, where [setPendingChannelEdit] is what it
	 * calls per frame while the gesture is still running.  A pending edit is still transient - the next pose
	 * move discards it - but discarding is not the same as never having happened: it is a deliberate edit
	 * the user can see take effect in the viewport, so it must be undoable independent of whether it ever
	 * reaches the document.
	 *
	 * Pushes its own snapshot rather than going through [mutate] / [commit], for the same reason
	 * [setSelection] and [setMeshSelection] do: neither the model nor the pose changes, so the commit choke
	 * point would short-circuit and record nothing.  Not a document edit, so it leaves dirty untouched.
	 *
	 * [change] is the SAME descriptor the unkeyed path would have used, so the history entry reads "Set
	 * Opacity" whichever branch the edit took - which branch it took is an implementation detail of where
	 * the value could be stored, not something a rigger asked for.
	 *
	 * @param KeyableTarget target The property edited.
	 * @param ChannelValue value The value the user chose.
	 * @param Change change The descriptor of this edit (for the bus and the history-panel label).
	 */
	fun commitPendingChannelEdit(target: KeyableTarget, value: ChannelValue, change: Change) {
		// Compared against what HISTORY holds, not against the live map: a scrub has already written its last
		// preview frame there, so a live-map comparison makes the release of every drag look like a no-op and
		// records nothing - the one case this exists for.
		if (history.current.pendingChannelEdits[target] == value) {
			// Still published: the guard says this step would record nothing new, not that the value is live.
			// The two diverge whenever something retired the pending edit without recording that it did - a
			// scrub previews through clearPendingChannelEdits and then ends where it began - and returning
			// outright left the re-typed value out of the viewport and the field untinted.
			setPendingChannelEdit(target, value)
			return
		}
		val edits = mutablePendingChannelEdits.value + (target to value)
		history.push(snapshot(pendingChannelEdits = edits), change)
		mutablePendingChannelEdits.value = edits
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * Discards every pending unkeyed edit.
	 *
	 * Called on any pose move - the situation that invalidates ALL of them at once, since every pending value
	 * was chosen for the pose being left.  A history jump does NOT call this: [restore] restores the
	 * snapshot's own [EditorSnapshot.pendingChannelEdits] instead, since the pose it lands on is exactly the
	 * pose those values were chosen for.  A keyform insert that consumed one target's value uses
	 * [clearPendingChannelEdit] instead, because the other targets' values are still valid for the unchanged
	 * pose.
	 */
	fun clearPendingChannelEdits() {
		if (mutablePendingChannelEdits.value.isNotEmpty()) {
			mutablePendingChannelEdits.value = emptyMap()
		}
	}

	/**
	 * Discards the pending unkeyed edit of [target] alone.
	 *
	 * The keyform-insert path: the capture consumed this one value, and the pose did not move, so every
	 * other target's pending value is still the value its user chose for the current pose.
	 *
	 * @param KeyableTarget target The property whose pending edit was consumed.
	 */
	fun clearPendingChannelEdit(target: KeyableTarget) {
		if (mutablePendingChannelEdits.value.containsKey(target)) {
			mutablePendingChannelEdits.value = mutablePendingChannelEdits.value - target
		}
	}

	/**
	 * Sets parameter [id]'s range and default, and re-clamps its live pose value into the new range — all
	 * as one undo step. A model edit (the range is document content), so it marks the document dirty; the
	 * pose re-clamp rides the same step so undo restores both together. A no-op range records nothing.
	 *
	 * @param ParameterId id The parameter to retarget.
	 * @param Float min The requested minimum.
	 * @param Float default The requested default (clamped into the resulting range).
	 * @param Float max The requested maximum.
	 */
	fun setParameterRange(id: ParameterId, min: Float, default: Float, max: Float) {
		val newModel = mutableModel.value.withParameterRange(id, min, default, max)
		if (newModel === mutableModel.value) {
			return
		}
		// Re-clamp the live value into the resulting (normalized) range so the pose stays valid.
		val parameter = newModel.parameters.firstOrNull { it.id == id }
		val newPose =
			if (parameter != null) {
				val current = mutablePose.value[id]
				val clamped = current?.coerceIn(parameter.min, parameter.max)
				if (clamped != null && clamped != current) {
					mutablePose.value + (id to clamped)
				} else {
					mutablePose.value
				}
			} else {
				mutablePose.value
			}
		val change = ParameterChange.SetRange(id, parameter?.min ?: min, parameter?.default ?: default, parameter?.max ?: max)
		commit(change, newModel, newPose)
	}

	/**
	 * Links parameter [horizontal] with [vertical] (the next parameter below it in panel order) into
	 * one 2D pad, or removes that link, as one undo step. A model edit (the link is document content),
	 * so it marks the document dirty. The pose needs no care here: a link only changes presentation,
	 * both parameters keep their live values by construction. An invalid request returns the same
	 * model instance from [withParameterLink], so the commit short-circuit records nothing.
	 *
	 * @param ParameterId horizontal The X-axis (upper) parameter.
	 * @param ParameterId vertical The Y-axis parameter.
	 * @param Boolean linked True to create the link, false to remove it.
	 */
	fun setParameterLink(horizontal: ParameterId, vertical: ParameterId, linked: Boolean) {
		val newModel = mutableModel.value.withParameterLink(horizontal, vertical, linked)
		if (newModel === mutableModel.value) {
			return
		}
		if (!linked) {
			// A pad targets BOTH its axes; once they are two separate sliders that reads as a multi-selection
			// the panel cannot otherwise produce, so the target narrows to the one that was active.  Narrowed
			// BEFORE the commit, so the pushed snapshot carries the narrowed target and a later redo cannot
			// restore the multi-selection.
			val target = mutableParameterSelection.value
			if (target.ids.size > 1) {
				mutableParameterSelection.value =
					target.active?.let { ParameterSelection.of(it) } ?: ParameterSelection()
			}
		}
		commit(ParameterChange.SetLink(horizontal, vertical, linked), newModel, mutablePose.value)
	}

	/**
	 * Deletes parameter [id] everywhere - the axis list, the panel tree, any link, every object's keyform
	 * grid (its axis collapses to the default slice), and the live pose - as one undo step. A model edit,
	 * so it marks the document dirty; dropping the pose entry rides the same step so undo restores both.
	 * A member (not a mutate extension) because it commits a new model and a new pose together, like
	 * [setParameterRange]. A no-op (no such parameter) records nothing.
	 *
	 * @param ParameterId id The parameter to delete.
	 */
	fun deleteParameter(id: ParameterId) {
		val newModel = mutableModel.value.withParameterDeleted(id)
		if (newModel === mutableModel.value) {
			return
		}
		// The target must never dangle on a parameter the model no longer has - pruned BEFORE the commit,
		// so the pushed snapshot carries the pruned selection and a later redo (or a History jump to this
		// entry) cannot restore the dangling id.
		mutableParameterSelection.value =
			mutableParameterSelection.value.prunedTo(newModel.parameters.mapTo(HashSet()) { parameter -> parameter.id })
		commit(ParameterChange.Delete(id), newModel, mutablePose.value - id)
	}

	/**
	 * Records a selection gesture as its own undo step (the chosen Blender-faithful granularity), so a
	 * misclick that clears the selection is recoverable. A no-op (selecting the already-current
	 * selection) records nothing.
	 *
	 * @param Selection selection The new selection.
	 */

	fun setSelection(selection: Selection) {
		if (selection == mutableSelection.value) {
			return
		}
		(selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		history.push(
			snapshot(selection = selection),
			EditorStateChange.SelectionChanged,
		)
		mutableSelection.value = selection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.SelectionChanged)
	}

	/**
	 * Sets the parameters targeted for keyform authoring as its own undo step.
	 *
	 * Pushes its own snapshot rather than going through [mutate] / [commit], exactly like [setSelection]:
	 * neither the model nor the pose changes, so the commit choke point would short-circuit and record
	 * nothing.  Shared session state rather than a panel's view state, so every area agrees on which
	 * parameter an insert would write to.
	 *
	 * @param ParameterSelection parameterSelection The new target set.
	 */
	fun setParameterSelection(parameterSelection: ParameterSelection) {
		if (parameterSelection == mutableParameterSelection.value) {
			return
		}
		history.push(snapshot(parameterSelection = parameterSelection), EditorStateChange.ParameterSelectionChanged)
		mutableParameterSelection.value = parameterSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.ParameterSelectionChanged)
	}

	/**
	 * Sets the interaction mode as its own undo step, so undo restores the mode (and the mesh selection
	 * it seeds) together - the editor never lands in Edit mode showing a state captured in Object mode.
	 * Entering Edit seeds the session with EVERY selected mesh-carrying drawable (multi-mesh edit; the
	 * object selection's active drawable becomes the session's active mesh), falling back to the last
	 * drawable that was active (Blender's remembered selection) when nothing is selected, and past that
	 * to the topmost editable drawable in Parts-panel order (so a fresh document lands on something to
	 * edit rather than an inert session); the remembered fallback is skipped if that drawable no longer
	 * exists in the model. When the model has nothing editable at all, entering Edit is refused - the
	 * mode stays Object and nothing is recorded - so Edit mode always holds meshes. Leaving Edit stashes the
	 * element selection and clears it; re-entering on the same drawable restores the stash when every
	 * element still fits the mesh (Blender's remembered mesh selection). Leaving also ends any in-flight
	 * operator. The mode is editor state, not document content, so it does not dirty the document. A no-op
	 * (already in [mode]) records nothing.
	 *
	 * @param EditorMode mode The new mode.
	 */
	fun setMode(mode: EditorMode) {
		if (mode == mutableMode.value) {
			return
		}
		val newMeshSelection =
			when (mode) {
				EditorMode.Edit -> {
					val model = mutableModel.value
					// The session spans EVERY selected mesh-carrying drawable (multi-mesh edit, needed for
					// glue work); the object selection's active drawable becomes the session's active mesh.
					val selectedMeshedIds =
						mutableSelection.value.targets
							.filterIsInstance<SelectionTarget.Drawable>()
							.map { target -> target.id }
							.filter { candidateId -> model.drawables.any { drawable -> drawable.id == candidateId && drawable.mesh != null } }
					val activeSelectedId =
						(mutableSelection.value.active as? SelectionTarget.Drawable)?.id
							?.takeIf { activeId -> activeId in selectedMeshedIds }
					val rememberedDrawableId =
						elementMemory.lastActiveDrawableId?.takeIf { remembered ->
							model.drawables.any { drawable -> drawable.id == remembered && drawable.mesh != null }
						}
					val seedDrawableIds =
						when {
							selectedMeshedIds.isNotEmpty() -> selectedMeshedIds
							rememberedDrawableId != null -> listOf(rememberedDrawableId)
							else -> listOfNotNull(model.firstEditableDrawableInPanelOrder())
						}
					if (seedDrawableIds.isEmpty()) {
						// The model has nothing editable, so refuse Edit rather than open an inert session
						// (Blender needs an active object too). Stay in Object; record nothing.
						return
					}
					val seedActiveId = activeSelectedId ?: seedDrawableIds.first()
					if (activeSelectedId == null) {
						// Seeded from the remembered drawable or the topmost fallback: remember it so the next
						// entry is stable. The object selection is left untouched - a soft seed, not a
						// re-selection, matching the remembered-drawable behavior.
						elementMemory.lastActiveDrawableId = seedActiveId
					}
					// Clear the transient tool state on entry too: the select tool is shared with Object mode, so
					// an object tool armed before the switch must not leak in and drive the Edit overlay.
					latches.clearTransient(clearViewportGesture = true)
					// Restore each seeded drawable's remembered elements where they still fit its mesh;
					// the rest start empty (a different mesh set no longer forgets the others' memory).
					elementMemory.restore(MeshSelection.editing(seedDrawableIds, seedActiveId), model)
				}
				EditorMode.Object -> {
					// Stash each mesh's element selection so re-entering Edit mode restores it per mesh.
					elementMemory.stash(mutableMeshSelection.value)
					latches.clearTransient(clearViewportGesture = true)
					MeshSelection()
				}
			}
		history.push(
			snapshot(meshSelection = newMeshSelection, mode = mode),
			EditorStateChange.ModeChanged(mode),
		)
		mutableMode.value = mode
		mutableMeshSelection.value = newMeshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.ModeChanged(mode))
	}

	/**
	 * Records an Edit-mode element-selection gesture as its own undo step (the same Blender-faithful
	 * granularity as object selection), so a misclick that loses a selection is recoverable. A no-op
	 * (selecting the already-current selection) records nothing. Not a document edit — leaves dirty
	 * untouched.
	 *
	 * @param MeshSelection meshSelection The new element selection.
	 */
	fun setMeshSelection(meshSelection: MeshSelection) {
		if (meshSelection == mutableMeshSelection.value) {
			return
		}
		history.push(
			snapshot(meshSelection = meshSelection),
			EditorStateChange.MeshSelectionChanged,
		)
		mutableMeshSelection.value = meshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectionChanged)
	}

	/**
	 * Records a keyform-sheet key-selection gesture as its own undo step, so a misclick that loses a
	 * carefully built multi-key selection is recoverable.  A no-op records nothing.  Not a document edit -
	 * leaves dirty untouched.
	 *
	 * Shared session state rather than a sheet's view state, for the same reason the parameter target is: a
	 * thing that undo restores has to live where undo can reach it, and two open sheets agreeing on one
	 * selection is the same trade [setParameterSelection] already made.
	 *
	 * Also the CONFIRM half of [stageKeySelection]: calling this with the staged selection after the
	 * gesture's edit records the selection exactly when the edit did not.
	 *
	 * @param Set<TrackKeyRef> keySelection The new key selection.
	 */
	fun setKeySelection(keySelection: Set<TrackKeyRef>) {
		// Compared against what HISTORY holds, not against the live flow, because a stage has already moved
		// the flow: comparing there makes a confirm look like a no-op in precisely the case it exists for -
		// the one where the edit it followed recorded nothing and the staged selection reached no snapshot.
		if (keySelection == history.current.keySelection) {
			// Published rather than merely dropped: this step records nothing new, which is not the same as
			// the live flow already holding it (a restore or an abandoned stage can leave it elsewhere).
			mutableKeySelection.value = keySelection
			return
		}
		history.push(snapshot(keySelection = keySelection), EditorStateChange.KeySelectionChanged)
		mutableKeySelection.value = keySelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.KeySelectionChanged)
	}

	/**
	 * Publishes [keySelection] WITHOUT recording a step of its own, for a gesture whose own step is about to
	 * follow.
	 *
	 * A keyform-sheet gesture selects and edits at once - a drag re-points the selection at the ordinals its
	 * keys landed on, an empty-track press drops the selection and scrubs - and they are one gesture, so
	 * they are one entry.  [snapshot] defaults every field it is not given to live state, so staging the
	 * selection BEFORE the edit folds it into that edit's own step.  Recording both would make one drag take
	 * two presses of Ctrl+Z to reverse.
	 *
	 * STAGE, EDIT, CONFIRM - every caller runs all three.  The edit may decline to record anything (a drag
	 * clamped to zero, a scrub that ended where it began, a move onto a key's own value), and a stage that
	 * reached no snapshot is a selection change undo cannot see.  So the gesture ends by calling
	 * [setKeySelection] with the same selection: that compares against history rather than against the live
	 * flow, so it is a no-op when the edit's push already carried the selection and records a step of its
	 * own when it did not.  Staging without confirming is a bug, not a shortcut.
	 *
	 * @param Set<TrackKeyRef> keySelection The new key selection.
	 */
	fun stageKeySelection(keySelection: Set<TrackKeyRef>) {
		mutableKeySelection.value = keySelection
	}

	/**
	 * Selects [keySelection] and moves the pose to [pose] as ONE undo step, named for the selection.
	 *
	 * Clicking a mark in the keyform sheet does both - it selects the key and lands the pose exactly on it -
	 * and they are one gesture, so they are one entry.  Naming it for the selection rather than for the
	 * scrub is what makes the history read as what the user did: they clicked a keyframe, and the pose
	 * moving is the consequence.
	 *
	 * Recorded even when the pose does not move (clicking a key the pose already sits on), which is why this
	 * exists rather than staging the selection and letting a pose commit carry it: the pose commit
	 * short-circuits on an unchanged pose, and the selection would then vanish from history entirely.
	 *
	 * @param Set<TrackKeyRef> keySelection The keys the click selected.
	 * @param Pose pose The pose the click landed on.
	 */
	fun selectKeysAtPose(keySelection: Set<TrackKeyRef>, pose: Pose) {
		// Against history rather than the live flow, for the reason [setKeySelection] spells out: a staged
		// selection has already moved the flow, and a click that lands on the selection a stage left there
		// must still record it.
		if (keySelection == history.current.keySelection && pose == history.current.pose) {
			mutableKeySelection.value = keySelection
			mutablePose.value = pose
			return
		}
		// A pose move invalidates every pending edit, exactly as it does through the ordinary commit path.
		if (pose != mutablePose.value) {
			clearPendingChannelEdits()
		}
		history.push(snapshot(pose = pose, keySelection = keySelection), EditorStateChange.KeySelectionChanged)
		mutableKeySelection.value = keySelection
		mutablePose.value = pose
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.KeySelectionChanged)
	}

	/**
	 * Commits a mesh-vertex edit (a finished modal G / S / R gesture) as ONE undo step: each session
	 * drawable's base art-mesh positions become its entry in [newPositionsByDrawable].  An Edit session
	 * spans several meshes, so the per-drawable copy-on-write [withMeshPositions] edits fold into a
	 * single model (one history step, like [commitObjectPositions]).  Mid-gesture preview frames reach
	 * the renderer directly (transient), so a whole drag is a single step.  A model edit (rest geometry
	 * is document content), so it marks the document dirty; a no-op (every array unchanged / mismatched)
	 * records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformVertices]).
	 * @param Map<DrawableId, FloatArray> newPositionsByDrawable Each edited drawable's committed rest positions.
	 */
	fun commitMeshPositions(change: MeshChange, newPositionsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newPositionsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newPositions) ->
				model.withMeshPositions(drawableId, newPositions)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Commits an Object-mode transform of several drawables (a finished modal G / S / R gesture) as ONE undo
	 * step: each drawable's base art-mesh positions become its entry in [newPositionsByDrawable]. Folds the
	 * per-drawable copy-on-write [withMeshPositions] edits into a single model, so N moved drawables are one
	 * history step (not N). Mid-gesture preview frames reach the renderer directly (transient), so a whole drag
	 * is a single step. A model edit (rest geometry is document content), so it marks the document dirty; a
	 * no-op (every array unchanged / mismatched, so the fold returns the same instance) records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformDrawables]).
	 * @param Map<DrawableId, FloatArray> newPositionsByDrawable Each moved drawable's committed rest positions.
	 */
	fun commitObjectPositions(change: MeshChange, newPositionsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newPositionsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newPositions) ->
				model.withMeshPositions(drawableId, newPositions)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Commits a UV edit (a finished modal G / S / R gesture in the UV editor, or a Mirror command) as
	 * ONE undo step: each edited drawable's texture coordinates become its entry in [newUvsByDrawable].
	 * The texture-mapping twin of [commitMeshPositions] - the per-drawable copy-on-write [withMeshUvs]
	 * edits fold into a single model, so N edited meshes are one history step.  Mid-gesture preview
	 * frames reach the renderer directly (transient), so a whole drag is a single step.  A model edit
	 * (the sampled texels are document content), so it marks the document dirty; a no-op (every
	 * array unchanged / mismatched) records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformUvs] or [MeshChange.MirrorUvs]).
	 * @param Map<DrawableId, FloatArray> newUvsByDrawable Each edited drawable's committed atlas UVs.
	 */
	fun commitMeshUvs(change: MeshChange, newUvsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newUvsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newUvs) ->
				model.withMeshUvs(drawableId, newUvs)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Mirrors the selected vertices' texture coordinates about the transform pivot as ONE undo step -
	 * the UV editor's Mirror U / V commands, serving the duplicated-and-flipped texture regions
	 * workflow (both eyes sampling one eye texture).  The pivot follows [pivotMode], resolved in UV
	 * space: Median Point mirrors about the covered vertices' combined median across every edited mesh,
	 * Individual Origins mirrors each connectivity island about its own median, Active Element anchors
	 * on the active element's median, and Cursor anchors on the UV cursor (each falling back to the
	 * combined median when unresolvable - a mirror should never silently do nothing because a pivot was
	 * never placed).  Mirroring is axis-aligned, so operating directly in normalized UV space matches
	 * the on-screen result regardless of the shown surface's size.  A no-op outside Edit mode or with an
	 * empty selection; a notice explains when no covered mesh carries an editable UV array.
	 *
	 * [frame] names the space the user is mirroring in, which for this operation is the whole question:
	 * an axis in the atlas page's frame is a different axis in a rotated or mirrored source layer's.
	 * With no frame the stored coordinates ARE the authoring space (the page view), and the whole
	 * conversion drops out.  Untouched vertices keep their exact stored values either way, so a mirror
	 * never marks a vertex changed that it did not move.
	 *
	 * @param Boolean mirrorU True to mirror horizontally (u about the pivot), false vertically (v).
	 * @param UvFrame? frame The authoring frame, or null when the stored coordinates are the frame.
	 */
	fun mirrorSelectedUvs(mirrorU: Boolean, frame: UvFrame? = null) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.isEmpty) {
			// Speaks up, unlike the mode guard above: reaching Mirror with an empty selection is an ordinary
			// mistake a rigger makes, where a request arriving outside Edit mode is stale bus traffic the
			// palette already gates.
			emitNotice("notice.uv.noSelection", NoticePlacement.NearCursor)
			return
		}
		val model = mutableModel.value
		// Resolve each session mesh's covered vertices, skipping meshes without an editable UV array
		// (imports may leave uvs empty; a malformed length is excluded by the same guard as withMeshUvs).
		val coveredByDrawable = LinkedHashMap<DrawableId, Set<Int>>()
		val meshByDrawable = LinkedHashMap<DrawableId, DrawableMesh>()
		for (drawableId in selection.drawableIds) {
			val mesh = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh ?: continue
			if (mesh.uvs.isEmpty() || mesh.uvs.size != mesh.positions.size) {
				continue
			}
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices)
			if (covered.isEmpty()) {
				continue
			}
			coveredByDrawable[drawableId] = covered
			meshByDrawable[drawableId] = mesh
		}
		if (coveredByDrawable.isEmpty()) {
			emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
			return
		}
		// The whole operation runs in the authoring frame: pivots, island medians, and the reflection
		// itself.  With no frame these arrays are the stored ones and the conversions are absent.
		val frameUvsByDrawable =
			meshByDrawable.mapValues { (_, mesh) -> frame?.toFrame(mesh.uvs) ?: mesh.uvs }
		val sharedPivot =
			if (latches.pivotMode.value == TransformPivotMode.IndividualOrigins) {
				null
			} else {
				resolveUvMirrorPivot(coveredByDrawable, meshByDrawable, frameUvsByDrawable, selection, frame)
			}
		val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>()
		for ((drawableId, covered) in coveredByDrawable) {
			val mesh = meshByDrawable.getValue(drawableId)
			val frameUvs = frameUvsByDrawable.getValue(drawableId)
			val groups =
				if (sharedPivot == null) {
					TransformPivots.islandGroups(frameUvs, covered, mesh.indices)
				} else {
					TransformPivots.sharedGroup(covered, sharedPivot.first, sharedPivot.second)
				}
			var mirroredUvs = frameUvs
			for (group in groups) {
				mirroredUvs =
					MeshTransforms.scaleVerticesAxis(
						mirroredUvs,
						group.vertexIndices,
						if (mirrorU) -1f else 1f,
						if (mirrorU) 1f else -1f,
						group.pivotX,
						group.pivotY,
					)
			}
			// Back to the stored form, then overwrite ONLY the covered vertices onto the current stored
			// array: a frame round trip is exact in the reals but not in floats, so rebuilding from the
			// stored values is what keeps an untouched vertex bit-identical (and out of the export's
			// changed-uv set).  Without a frame the mirrored array is already stored-form and this is a
			// straight copy of the moved components.
			val storedMirrored = frame?.fromFrame(mirroredUvs) ?: mirroredUvs
			val newUvs = mesh.uvs.copyOf()
			for (vertexIndex in covered) {
				newUvs[vertexIndex * 2] = storedMirrored[vertexIndex * 2]
				newUvs[vertexIndex * 2 + 1] = storedMirrored[vertexIndex * 2 + 1]
			}
			newUvsByDrawable[drawableId] = newUvs
		}
		commitMeshUvs(MeshChange.MirrorUvs(newUvsByDrawable.keys.toList(), mirrorU), newUvsByDrawable)
	}

	/**
	 * Resolves the shared UV mirror pivot for the single-anchor pivot modes: Cursor anchors on the UV
	 * cursor and Active Element on the active element's covered median, each falling back to the
	 * combined covered median across every edited mesh - which is also the Median Point result.
	 *
	 * Resolved in the AUTHORING frame throughout, so every anchor means the same thing the reflection
	 * does - including the UV cursor, which is stored in atlas coordinates and converts in like the
	 * meshes do.
	 *
	 * @param Map<DrawableId, Set<Int>> coveredByDrawable Each edited mesh's covered vertex indices.
	 * @param Map<DrawableId, DrawableMesh> meshByDrawable Each edited mesh, keyed like the covered map.
	 * @param Map<DrawableId, FloatArray> frameUvsByDrawable Each edited mesh's uvs in the authoring frame.
	 * @param MeshSelection selection The live selection (for the active element).
	 * @param UvFrame? frame The authoring frame, or null when the stored coordinates are the frame.
	 * @return Pair<Float, Float> The pivot's (u, v), in the authoring frame.
	 */
	private fun resolveUvMirrorPivot(
		coveredByDrawable: Map<DrawableId, Set<Int>>,
		meshByDrawable: Map<DrawableId, DrawableMesh>,
		frameUvsByDrawable: Map<DrawableId, FloatArray>,
		selection: MeshSelection,
		frame: UvFrame?,
	): Pair<Float, Float> {
		when (latches.pivotMode.value) {
			TransformPivotMode.Cursor -> {
				val cursor = latches.uvCursor.value
				if (cursor != null) {
					return frame?.pointToFrame(cursor.u, cursor.v) ?: (cursor.u to cursor.v)
				}
			}

			TransformPivotMode.ActiveElement -> {
				val active = selection.activeElement
				val activeMesh = active?.let { activeElement -> meshByDrawable[activeElement.drawableId] }
				val activeFrameUvs = active?.let { activeElement -> frameUvsByDrawable[activeElement.drawableId] }
				if (active != null && activeMesh != null && activeFrameUvs != null) {
					val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeMesh.indices)
					if (activeCovered.isNotEmpty()) {
						return MeshTransforms.medianPivot(activeFrameUvs, activeCovered)
					}
				}
			}

			TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> {}
		}
		var sumU = 0f
		var sumV = 0f
		var coveredCount = 0
		for ((drawableId, covered) in coveredByDrawable) {
			val uvs = frameUvsByDrawable.getValue(drawableId)
			for (vertexIndex in covered) {
				sumU += uvs[vertexIndex * 2]
				sumV += uvs[vertexIndex * 2 + 1]
				coveredCount += 1
			}
		}
		if (coveredCount == 0) {
			// Unreachable today (callers pre-filter empty covered sets); the authoring frame's center is
			// a safe anchor.
			return 0.5f to 0.5f
		}
		return (sumU / coveredCount) to (sumV / coveredCount)
	}

	/**
	 * Switches the Edit-mode select mode (vertex / edge / face) as its own undo step, converting the
	 * stored selection into the new domain with Blender's flush-down / derive-up rules (see
	 * [MeshSelectionOps.changeSelectMode]). The conversion is lossy by design, so the snapshot is what
	 * makes it recoverable. A no-op outside Edit mode — so the bound 1 / 2 / 3 commands need no context
	 * guard of their own and the keymap stays mode-agnostic — and a no-op when already in [selectMode].
	 * Not a document edit — leaves dirty untouched.
	 *
	 * @param MeshSelectMode selectMode The new select mode.
	 */
	fun setMeshSelectMode(selectMode: MeshSelectMode) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		val converted =
			MeshSelectionOps.changeSelectMode(current, selectMode) { drawableId ->
				model.drawables.firstOrNull { it.id == drawableId }?.mesh?.indices
			}
		if (converted == current) {
			return
		}
		history.push(
			snapshot(meshSelection = converted),
			EditorStateChange.MeshSelectModeChanged(selectMode),
		)
		mutableMeshSelection.value = converted
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectModeChanged(selectMode))
	}

	/**
	 * Selects every element of every session mesh in the current select mode (Blender's Select All) as
	 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes - so the bound
	 * command stays mode-agnostic (it dispatches to [selectAllObjects] in Object mode).  Not a document edit.
	 */
	fun selectAllMeshElements() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		setMeshSelection(MeshSelectionOps.selectAll(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
	}

	/**
	 * Inverts every session mesh's element selection within the current select mode (Blender's Ctrl+I) as
	 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes.  Not a document
	 * edit.
	 */
	fun invertMeshSelection() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		setMeshSelection(MeshSelectionOps.invert(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
	}

	/**
	 * Selects every selectable entity in the model (Object mode's Select All) as one undo step.  A no-op
	 * outside Object mode - so the bound command stays mode-agnostic (it dispatches to [selectAllMeshElements]
	 * in Edit mode).  Not a document edit.
	 */
	fun selectAllObjects() {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		setSelection(SelectionOps.selectAll(mutableSelection.value, mutableModel.value))
	}

	/**
	 * Inverts the object selection over every selectable entity (Object mode's Ctrl+I) as one undo step.  A
	 * no-op outside Object mode.  Not a document edit.
	 */
	fun invertObjectSelection() {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		setSelection(SelectionOps.invert(mutableSelection.value, mutableModel.value))
	}

	/**
	 * True while the active mesh operator was latched with proportional editing suppressed - the
	 * duplicate / rip auto-grabs, which place fresh copies and must never drag bystander vertices.
	 * Transient latch state (never snapshotted), reset whenever the operator latches or clears.
	 */
	val activeMeshOperatorSuppressesProportional: Boolean
		get() = latches.activeMeshOperatorSuppressesProportional

	/**
	 * Latches a modal mesh operator so the gizmo overlay begins the gesture. A no-op unless Edit mode is
	 * active with a drawable and a non-empty selection — so the bound G / S / R commands need no context
	 * guard of their own and the keymap stays mode-agnostic. For an edge or face selection the gesture
	 * moves the union of vertices the selected elements cover (resolved in the overlay).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 * @param Boolean suppressProportional True to ignore proportional editing for this gesture (the
	 *   duplicate / rip auto-grabs; treated like Vertex Slide at every proportional gate).
	 */
	fun beginMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean = false) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.drawableIds.isEmpty() || selection.isEmpty) {
			return
		}
		latches.latchMeshOperator(kind, areaId, suppressProportional)
	}

	/** Clears the active modal mesh operator (the overlay calls this on confirm or cancel). */
	fun clearMeshOperator() {
		latches.clearMeshOperator()
	}

	/**
	 * Begins an Object-mode modal transform (Grab / Scale / Rotate) over the selected drawables' whole
	 * geometry - the Object-mode counterpart to [beginMeshOperator]. A no-op unless Object mode is active
	 * with an eligible selection: at least one selected target must be a drawable that carries a mesh (see
	 * [eligibleTransformDrawables]; parts, deformers, and mesh-less drawables in the selection are silently
	 * ignored, so a Select All that swept them in never blocks the gesture). The gesture is BLOCKED with a
	 * near-cursor notice when the pose is not at parameter defaults: the object overlay captures at the live
	 * pose, and writing a deformed capture back through the warp inverse corrupts the rest meshes - the
	 * Blender-style guard tells the user to reset the parameters first. Clears any other latched tool /
	 * operator (mutual exclusion) before latching.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 */
	fun beginObjectOperator(kind: MeshOperatorKind, areaId: String) {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		if (eligibleTransformDrawables(mutableSelection.value, mutableModel.value) == null) {
			// Nothing transformable at all (empty, or only parts / deformers / mesh-less drawables).
			emitNotice("notice.transform.onlyDrawables", NoticePlacement.NearCursor)
			return
		}
		if (!isPoseNeutral(mutableModel.value, mutablePose.value)) {
			// Transforming rest geometry while the displayed pose is deformed would write garbage through
			// the deformer inverse; refuse and tell the user how to proceed (see the docblock).
			emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)
			return
		}
		latches.latchObjectOperator(kind, areaId)
	}

	/** Clears the active modal object operator (the overlay calls this on confirm or cancel). */
	fun clearObjectOperator() {
		latches.clearObjectOperator()
	}

	/**
	 * Latches a modal UV operator so the UV editor's overlay begins the gesture - the UV-editor
	 * counterpart to [beginMeshOperator], moving texture coordinates instead of rest geometry.  A no-op
	 * unless Edit mode is active with a non-empty selection - so the bound G / S / R commands stay
	 * mode-agnostic - and Vertex Slide is refused (it is rest-geometry math; Blender's UV editor has no
	 * slide either).  The gesture is BLOCKED with a near-cursor notice when no covered mesh carries an
	 * editable UV array (imports may leave uvs empty), since latching would show a modal HUD that can
	 * never commit anything.  Clears any other latched tool / operator (mutual exclusion) before
	 * latching.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
	 */
	fun beginUvOperator(kind: MeshOperatorKind, areaId: String) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		if (kind == MeshOperatorKind.VertexSlide) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.drawableIds.isEmpty() || selection.isEmpty) {
			return
		}
		val model = mutableModel.value
		val anyEditableUvs =
			selection.drawableIds.any { drawableId ->
				val mesh = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh
				mesh != null &&
					mesh.uvs.isNotEmpty() &&
					mesh.uvs.size == mesh.positions.size &&
					MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices).isNotEmpty()
			}
		if (!anyEditableUvs) {
			emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
			return
		}
		latches.latchUvOperator(kind, areaId)
	}

	/** Clears the active modal UV operator (the overlay calls this on confirm or cancel). */
	fun clearUvOperator() {
		latches.clearUvOperator()
	}

	/**
	 * Arms the Box-select tool (Blender's B): the gizmo overlay shows full-viewport crosshair guides and the
	 * next drag boxes.  Mode-agnostic - in Edit mode it needs an active drawable (the box selects that mesh's
	 * elements); in Object mode it arms unconditionally (the box selects whole drawables).  A no-op in Edit
	 * mode without a drawable.  Clears any other latched tool / operator (mutual exclusion).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
	 */
	fun beginBoxSelect(areaId: String) {
		if (mutableMode.value == EditorMode.Edit && mutableMeshSelection.value.drawableIds.isEmpty()) {
			return
		}
		latches.armBoxSelect(areaId)
	}

	/**
	 * Arms the Circle-select tool (Blender's C) at the remembered radius.  Mode-agnostic like [beginBoxSelect]:
	 * needs an active drawable in Edit mode, arms unconditionally in Object mode.  Clears any other latched
	 * tool / operator (mutual exclusion).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
	 */
	fun beginCircleSelect(areaId: String) {
		if (mutableMode.value == EditorMode.Edit && mutableMeshSelection.value.drawableIds.isEmpty()) {
			return
		}
		latches.armCircleSelect(areaId)
	}

	/**
	 * Sets the Circle-select brush radius (clamped), remembering it for the next arm.  When a Circle tool is
	 * live its radius updates in place so the overlay redraws; otherwise only the remembered value moves.
	 *
	 * @param Float radiusPx The requested radius in viewport pixels.
	 */
	fun setCircleRadius(radiusPx: Float) {
		latches.setCircleRadius(radiusPx)
	}

	/** Grows the Circle-select radius by one step (numpad +); a no-op unless a Circle tool is live. */
	fun growCircleRadius() {
		latches.growCircleRadius()
	}

	/** Shrinks the Circle-select radius by one step (numpad -); a no-op unless a Circle tool is live. */
	fun shrinkCircleRadius() {
		latches.shrinkCircleRadius()
	}

	/** Clears any armed Box / Circle select tool (the overlay calls this on completion, Esc, or RMB). */
	fun clearSelectTool() {
		latches.clearSelectTool()
	}

	/**
	 * Arms the Zoom Region gesture (Blender's Shift+B) for [areaId].  Mode-agnostic - valid in Object and
	 * Edit mode.  Clears any latched Edit-mode tool so two overlays never capture at once.
	 *
	 * @param String areaId The viewport area the gesture will run in (the pointer's active area).
	 */
	fun armZoomRegion(areaId: String) {
		latches.armZoomRegion(areaId)
	}

	/** Disarms the Zoom Region gesture (the overlay calls this on completion, Esc, or RMB). */
	fun disarmZoomRegion() {
		latches.disarmZoomRegion()
	}

	/**
	 * The 2D cursor's world position, or null before any placement.  Transient session state like the
	 * tool latches (deliberately NOT part of EditorSnapshot - see [Cursor2d]); placed by Shift+RightClick
	 * in the viewport, moved by the snap commands, drawn by the HUD overlay, and read as the transform
	 * pivot in [TransformPivotMode.Cursor].
	 */
	val cursor2d: StateFlow<Cursor2d?> = latches.cursor2d

	/**
	 * Places (or moves) the 2D cursor.
	 *
	 * @param Float worldX The cursor's new world-space x.
	 * @param Float worldY The cursor's new world-space y.
	 */
	fun setCursor2d(worldX: Float, worldY: Float) {
		latches.setCursor2d(worldX, worldY)
	}

	/**
	 * The UV editor's cursor in normalized atlas coordinates, or null before any placement.  The
	 * texture-space sibling of [cursor2d] (see [UvCursor]): placed by Shift+RightClick in the UV editor,
	 * drawn by its overlay, and read as the UV transform pivot in [TransformPivotMode.Cursor].
	 */
	val uvCursor: StateFlow<UvCursor?> = latches.uvCursor

	/**
	 * Places (or moves) the UV editor's cursor.
	 *
	 * @param Float u The cursor's new normalized atlas u coordinate.
	 * @param Float v The cursor's new normalized atlas v coordinate.
	 */
	fun setUvCursor(u: Float, v: Float) {
		latches.setUvCursor(u, v)
	}

	/**
	 * The viewport grid geometry (major spacing + subdivisions) driving both the drawn backdrop grid and
	 * the grid snap increment.  Transient session state today (deliberately NOT snapshotted - like the 2D
	 * cursor); seeded from the global-default settings and, once the UMA format lands, from the per-file
	 * value.  Read by the snap commands ([GridConfig.snapStep]) and pushed to the renderer by the viewport
	 * binding.
	 */
	val gridConfig: StateFlow<GridConfig> = latches.gridConfig

	/**
	 * Sets the viewport grid geometry.  Called by the viewport binding when the global-default settings
	 * change (and, later, when a per-file value loads); the header overlay control will call it too.
	 *
	 * @param GridConfig config The new grid scale and subdivisions.
	 */
	fun setGridConfig(config: GridConfig) {
		latches.setGridConfig(config)
	}

	/**
	 * What a modal Scale / Rotate turns the selection about (the Period pie / the header dropdown).
	 * Transient editor state - it survives mode switches but is never snapshotted; the default is
	 * Blender's Median Point.
	 */
	val pivotMode: StateFlow<TransformPivotMode> = latches.pivotMode

	/**
	 * Selects the transform pivot mode.
	 *
	 * @param TransformPivotMode mode The pivot mode the next transforms anchor on.
	 */
	fun setPivotMode(mode: TransformPivotMode) {
		latches.setPivotMode(mode)
	}

	/**
	 * The axis the in-flight modal Grab / Scale is locked to, or null when unconstrained.  Set by the
	 * shell's key ladder (X / Z during a modal gesture - the keymap cannot see those keys, the operator
	 * swallows them), read by the gizmo overlays' drive loops, cleared whenever an operator latches or
	 * clears.  Transient coordination like [activeMeshOperator].
	 */
	val axisConstraint: StateFlow<TransformAxisConstraint?> = latches.axisConstraint

	/**
	 * Toggles the modal axis constraint (pressing a lock's own key again releases it; pressing the other
	 * axis switches).  A no-op unless a Grab or Scale operator is in flight - Rotate has no axis to lock
	 * and idle keys must not arm a stale constraint.
	 *
	 * @param TransformAxisConstraint axis The axis whose lock to toggle.
	 */
	fun toggleAxisConstraint(axis: TransformAxisConstraint) {
		latches.toggleAxisConstraint(axis)
	}

	/**
	 * The radial pie menu currently open over the viewport, or null.  Transient UI coordination: a
	 * command opens it (Period / Shift+S / the merge menu), the pie host composable renders it at the
	 * pointer, and picking an entry or Escape closes it.
	 */
	val activePieMenu: StateFlow<PieMenuKind?> = latches.activePieMenu

	/**
	 * Opens a pie menu over the viewport (closing any other transient latch is not needed - a pie is
	 * display-only and the key ladder swallows input while one is open).
	 *
	 * @param PieMenuKind kind The pie to open.
	 */
	fun openPieMenu(kind: PieMenuKind) {
		latches.openPieMenu(kind)
	}

	/** Closes the open pie menu (entry picked, Escape, or a click outside). */
	fun closePieMenu() {
		latches.closePieMenu()
	}

	/**
	 * The keyform edit waiting on an axis, or null.  Transient UI coordination exactly like
	 * [activePieMenu]: an ambiguous `I` / `Alt+I` parks here, the shell lists the candidate parameters at
	 * the pointer, and picking one replays the edit through [resolveParameterChoice].
	 */
	val pendingParameterChoice: StateFlow<ParameterChoiceRequest?> = latches.pendingParameterChoice

	/**
	 * Parks a keyform edit until the user picks the axis it writes on.
	 *
	 * @param ParameterChoiceRequest request The parked edit and the axes to choose between.
	 */
	fun requestParameterChoice(request: ParameterChoiceRequest) {
		latches.openParameterChoice(request)
	}

	/** Abandons the parked keyform edit (Escape, or a click outside the prompt). */
	fun cancelParameterChoice() {
		latches.closeParameterChoice()
	}

	/**
	 * Proportional editing (Blender's O): non-null while enabled, carrying the falloff curve and the
	 * influence radius.  Transient editor state like [pivotMode] - it survives mode switches but is
	 * never snapshotted; the Edit overlay reads it when a modal operator latches (and on mid-gesture
	 * radius scrolls) to weight the unselected vertices near the selection.
	 */
	val proportionalEdit: StateFlow<ProportionalEditState?> = latches.proportionalEdit

	/**
	 * Toggles proportional editing on or off (Blender's O), restoring the last falloff and radius on
	 * re-enable and confirming either way with a near-cursor notice (an idle toggle has no other
	 * visible effect - the influence circle only shows during a modal transform).
	 */
	fun toggleProportionalEdit() {
		latches.toggleProportionalEdit()
	}

	/**
	 * Toggles Connected Only for proportional editing (influence measured along mesh edges instead of
	 * straight-line, so the halo never leaps to unconnected geometry), enabling proportional editing
	 * if it was off - and then connected mode turns ON regardless of the remembered flag, since the
	 * command expresses the intent to use it.  Confirms either way with a near-cursor notice.
	 */
	fun toggleProportionalConnected() {
		latches.toggleProportionalConnected()
	}

	/**
	 * Selects the proportional falloff curve, enabling proportional editing if it was off - picking a
	 * falloff from the palette or header expresses the intent to use it, and silently updating a
	 * disabled state would look like the command did nothing.
	 *
	 * @param ProportionalFalloff falloff The falloff curve the influence weights follow.
	 */
	fun setProportionalFalloff(falloff: ProportionalFalloff) {
		latches.setProportionalFalloff(falloff)
	}

	/**
	 * Sets the proportional influence radius, clamped to the allowed range.  A no-op while proportional
	 * editing is off (the radius only changes from the mid-gesture scroll, which requires it on).
	 *
	 * @param Float radiusWorld The influence radius in world units (canvas px).
	 */
	fun setProportionalRadius(radiusWorld: Float) {
		latches.setProportionalRadius(radiusWorld)
	}

	/**
	 * Fires the geometry-dependent snap operations (Blender's Shift+S) for the active mode's overlay to
	 * execute: the posed world projections and the deformer-chain inverse those snaps need live with the
	 * overlays, not here (the same division as [meshConfirmRequests]).  The purely arithmetical snaps
	 * (cursor to world origin / to grid) never pass through - their command handlers set the cursor
	 * directly.
	 *
	 * The payload carries the dispatch-time resolved area (see [SnapRequest]) purely to elect ONE of the
	 * open viewports; the handlers themselves ignore it.
	 */
	val snapRequests: SharedFlow<SnapRequest> = requestBus.snapRequests

	/**
	 * Requests a geometry-dependent snap (see [snapRequests]).
	 *
	 * @param SnapKind kind The snap to perform.
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSnap(kind: SnapKind, areaId: String?) {
		requestBus.requestSnap(SnapRequest(kind, areaId))
	}

	/**
	 * Fires Select Linked (Blender's L / Ctrl+L) for one overlay to execute: a keymap command carries
	 * no pointer position, so the overlay (which tracks the pointer and owns the projected geometry)
	 * picks the seed and floods.  The payload carries the flood variant AND the dispatch-time resolved
	 * area (see [SelectLinkedRequest]), so collectors gate deterministically on their own area id
	 * instead of re-reading a pointer-side volatile at collect time.
	 */
	val selectLinkedRequests: SharedFlow<SelectLinkedRequest> = requestBus.selectLinkedRequests

	/**
	 * Requests a Select Linked (see [selectLinkedRequests]).
	 *
	 * @param Boolean fromSelection True to flood from the whole selection (Ctrl+L), false from the cursor (L).
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSelectLinked(fromSelection: Boolean, areaId: String?) {
		requestBus.requestSelectLinked(SelectLinkedRequest(fromSelection, areaId))
	}

	/**
	 * Fires a UV snap (the UV editor's Shift+S pie) for one UV editor overlay to execute: the shown
	 * surface's dimensions and display geometry live with the overlay, so it performs the snap over
	 * the texture coordinates (the texture-space sibling of [snapRequests]).  The payload carries the
	 * operation AND the dispatch-time resolved area (see [UvSnapRequest]), so the collector gates
	 * deterministically on its own area id.
	 */
	val uvSnapRequests: SharedFlow<UvSnapRequest> = requestBus.uvSnapRequests

	/**
	 * Requests a UV snap (see [uvSnapRequests]).
	 *
	 * @param UvSnapRequest request The snap operation plus the executing overlay's area, resolved at
	 *   command dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvSnap(request: UvSnapRequest) {
		requestBus.requestUvSnap(request)
	}

	/**
	 * Fires a mirror (the uv.mirrorU / uv.mirrorV commands) for one UV editor overlay to execute: the
	 * axis a mirror reflects about depends on the surface being authored over - an atlas page or a
	 * source layer - and only the overlay knows which it is showing, so it supplies the frame and calls
	 * [mirrorSelectedUvs].  The payload carries the axis AND the dispatch-time resolved area (see
	 * [UvMirrorRequest]), so the collector gates deterministically on its own area id.
	 */
	val uvMirrorRequests: SharedFlow<UvMirrorRequest> = requestBus.uvMirrorRequests

	/**
	 * Requests a mirror (see [uvMirrorRequests]).
	 *
	 * @param UvMirrorRequest request The mirror axis plus the executing overlay's area, resolved at
	 *   command dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvMirror(request: UvMirrorRequest) {
		requestBus.requestUvMirror(request)
	}

	/**
	 * Fires a page switch (the uv.page.* palette commands) for one UV editor area to execute: the
	 * per-area texture selection (the page pin) lives with the area's view state, not the session, so
	 * the space applies the transition itself.  The payload carries the operation AND the
	 * dispatch-time resolved area (see [UvPageRequest]), so the collector gates deterministically on
	 * its own area id.
	 */
	val uvPageRequests: SharedFlow<UvPageRequest> = requestBus.uvPageRequests

	/**
	 * Requests a page switch (see [uvPageRequests]).
	 *
	 * @param UvPageRequest request The page operation plus the executing area, resolved at command
	 *   dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvPage(request: UvPageRequest) {
		requestBus.requestUvPage(request)
	}

	/**
	 * Fires "switch the edited mesh to the drawable under the cursor" (Alt+Q) for the Edit overlay to
	 * execute - the pointer position and the pick live there (the same division as
	 * [selectLinkedRequests]).  The payload IS the dispatch-time resolved area, so the collector gates on
	 * its own id rather than re-reading a pointer-side volatile at collect time.
	 */
	val switchObjectRequests: SharedFlow<String?> = requestBus.switchObjectRequests

	/**
	 * Requests an Alt+Q edited-mesh switch (see [switchObjectRequests]).
	 *
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSwitchObjectUnderCursor(areaId: String?) {
		requestBus.requestSwitchObjectUnderCursor(areaId)
	}

	/**
	 * Fires a rip (Blender's V) for the Edit overlay to execute: which side of the fan follows the
	 * ripped copies depends on the pointer, which lives with the overlay (the same division as
	 * [selectLinkedRequests]).  The payload IS the dispatch-time resolved area, so the collector gates on
	 * its own id rather than re-reading a pointer-side volatile at collect time.
	 */
	val ripRequests: SharedFlow<String?> = requestBus.ripRequests

	/**
	 * Requests a rip at the pointer (see [ripRequests]).
	 *
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestRip(areaId: String?) {
		requestBus.requestRip(areaId)
	}

	/**
	 * Commits a topology operation on one session mesh as ONE undo step: the model takes the edit (mesh
	 * swap, keyform-delta rebuild, glue remap - see [withMeshTopologyEdit]) and the mesh selection
	 * becomes the operation's result elements on that mesh, in the SAME history push - splitting them
	 * would let undo tear the selection from the topology it indexes into.  The ops produce vertex
	 * results; they are re-derived into the CURRENT select mode (Blender keeps the mode across a
	 * topology op - a face-mode duplicate leaves the new faces selected in face mode), falling back to
	 * vertex mode only when nothing in the current domain covers them (e.g. a duplicated lone edge
	 * copies as loose vertices, which no edge or face contains - stranding them unselected would hide
	 * the copies and starve the follow-up auto-grab).  A no-op edit records nothing.
	 *
	 * @param String labelKey The operation's history label key (change.mesh.duplicate / merge / rip / connect).
	 * @param DrawableId drawableId The edited mesh.
	 * @param TopologyOpResult result The op builder's outcome.
	 */
	fun commitMeshTopology(labelKey: String, drawableId: DrawableId, result: TopologyOpResult) {
		val newModel = mutableModel.value.withMeshTopologyEdit(drawableId, result.edit)
		if (newModel === mutableModel.value) {
			return
		}
		val current = mutableMeshSelection.value
		val vertexResult =
			MeshSelection(
				drawableIds = current.drawableIds,
				activeDrawableId = drawableId,
				selectMode = MeshSelectMode.Vertex,
				elementsByDrawable = if (result.newElements.isEmpty()) emptyMap() else mapOf(drawableId to result.newElements),
				activeElement = result.newElements.firstOrNull()?.let { element -> ActiveMeshElement(drawableId, element) },
			)
		val newSelection = rederiveTopologyResult(vertexResult, current.selectMode, drawableId, newModel)
		val change = MeshChange.TopologyEdit(drawableId, labelKey)
		history.push(snapshot(model = newModel, meshSelection = newSelection), change)
		mutableModel.value = newModel
		mutableMeshSelection.value = newSelection
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * Converts a topology op's vertex-mode result selection into [selectMode] against [newModel] (the
	 * post-edit topology, where the new elements exist), via the strict derive-up rules of
	 * [MeshSelectionOps.changeSelectMode]; the first derived element becomes active.  Returns the
	 * vertex result unchanged when the session is already in vertex mode, when the op selected
	 * nothing, or when nothing in the target domain covers the new vertices (see
	 * [commitMeshTopology]'s docblock for that fallback's rationale).
	 *
	 * @param MeshSelection vertexResult The op's result selection, in vertex mode.
	 * @param MeshSelectMode selectMode The session's current select mode to re-derive into.
	 * @param DrawableId drawableId The edited mesh.
	 * @param PuppetModel newModel The model with the topology edit applied.
	 * @return MeshSelection The result selection in the kept mode, or the vertex fallback.
	 */
	private fun rederiveTopologyResult(
		vertexResult: MeshSelection,
		selectMode: MeshSelectMode,
		drawableId: DrawableId,
		newModel: PuppetModel,
	): MeshSelection {
		if (selectMode == MeshSelectMode.Vertex || vertexResult.elementsOf(drawableId).isEmpty()) {
			return vertexResult
		}
		val rederived =
			MeshSelectionOps.changeSelectMode(vertexResult, selectMode) { candidateId ->
				newModel.drawables.firstOrNull { drawable -> drawable.id == candidateId }?.mesh?.indices
			}
		val rederivedElements = rederived.elementsOf(drawableId)
		if (rederivedElements.isEmpty()) {
			return vertexResult
		}
		return rederived.copy(activeElement = ActiveMeshElement(drawableId, rederivedElements.first()))
	}

	/**
	 * Duplicates the ACTIVE session mesh's covered elements in place (Edit-mode Shift+D) as one undo
	 * step, leaving the copies selected - the caller follows with a Grab so the copies pull away under
	 * the pointer, Blender-style.  A no-op outside Edit mode or with nothing covered on the active mesh.
	 */
	fun duplicateSelectedElements() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices)
		val result = MeshTopologyOps.duplicateElements(mesh, covered) ?: return
		commitMeshTopology("change.mesh.duplicate", drawableId, result)
	}

	/**
	 * Merges the ACTIVE session mesh's selected vertices (Blender's M) as one undo step, leaving the
	 * survivor selected.  Vertex mode only - the first / last targets read the selection order, which
	 * only vertex elements carry directly.  Refusals explain themselves with a near-cursor notice.
	 *
	 * @param MergeTarget target Where the survivor lands (center / first / last).
	 */
	fun mergeSelectedVertices(target: MergeTarget) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		if (selection.selectMode != MeshSelectMode.Vertex) {
			emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)
			return
		}
		// The element set is insertion-ordered (a LinkedHashSet built by the gestures), so "first" is
		// the earliest-selected vertex; "last" prefers the active element (the most recent touch).
		val orderedVertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }.toMutableList()
		(selection.activeElement?.element as? MeshElement.Vertex)?.let { activeVertex ->
			if (orderedVertices.remove(activeVertex.index)) {
				orderedVertices.add(activeVertex.index)
			}
		}
		if (orderedVertices.size < 2) {
			emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)
			return
		}
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val result = MeshTopologyOps.mergeVertices(mesh, orderedVertices, target) ?: return
		commitMeshTopology("change.mesh.merge", drawableId, result)
	}

	/**
	 * Connects the ACTIVE session mesh's two selected vertices with a cut (Blender's J) as one undo
	 * step, leaving the cut path selected.  Exactly two selected vertices in vertex mode; a refusal
	 * (already connected, nothing crossed, degenerate geometry) explains itself with a notice.
	 */
	fun connectSelectedVertices() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		val vertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }
		if (selection.selectMode != MeshSelectMode.Vertex || vertices.size != 2) {
			emitNotice("notice.connect.needsTwoVertices", NoticePlacement.NearCursor)
			return
		}
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val result = MeshTopologyOps.connectVertices(mesh, vertices[0], vertices[1])
		if (result == null) {
			emitNotice("notice.connect.refused", NoticePlacement.NearCursor)
			return
		}
		commitMeshTopology("change.mesh.connect", drawableId, result)
	}

	/**
	 * Duplicates every eligible selected drawable (Object-mode Shift+D) as ONE undo step: each copy
	 * lands after its source in the org tree, and the selection becomes the copies - the caller follows
	 * with a Grab so they pull away under the pointer, Blender-style.
	 *
	 * @return List<DrawableId> The created copies (empty when nothing was eligible).
	 */
	fun duplicateSelectedDrawables(): List<DrawableId> {
		if (mutableMode.value != EditorMode.Object) {
			return emptyList()
		}
		val eligibleIds = eligibleTransformDrawables(mutableSelection.value, mutableModel.value) ?: return emptyList()
		var newModel = mutableModel.value
		val copies = ArrayList<DrawableId>(eligibleIds.size)
		for (drawableId in eligibleIds) {
			val (edited, copyId) = newModel.withDrawableDuplicated(drawableId) ?: continue
			newModel = edited
			copies.add(copyId)
		}
		if (copies.isEmpty() || newModel === mutableModel.value) {
			return emptyList()
		}
		val newSelection =
			Selection(
				copies.map { copyId -> SelectionTarget.Drawable(copyId) }.toSet<SelectionTarget>(),
				SelectionTarget.Drawable(copies.last()),
			)
		val change = DrawableChange.Duplicate(copies)
		history.push(snapshot(model = newModel, selection = newSelection), change)
		mutableModel.value = newModel
		mutableSelection.value = newSelection
		refreshFlags()
		mutableChanges.tryEmit(change)
		return copies
	}

	/**
	 * Re-seeds the Edit session onto one drawable (Alt+Q's switch), as ONE undo step covering both
	 * selections: the session's meshes become just [drawableId] (with its remembered elements restored
	 * where they still fit), and the OBJECT selection moves onto the same drawable - so tabbing back to
	 * Object mode keeps the switched mesh instead of reviving the selection Edit mode was entered with.
	 * The outgoing meshes' element selections stash into the per-mesh memory first, and the
	 * remembered-drawable memory follows.  A no-op outside Edit mode or when the drawable carries no
	 * mesh.
	 *
	 * Built as one combined snapshot push (never chained setSelection + setMeshSelection - each of
	 * those snapshots the OTHER selection's pre-change value, which would tear the pair across two
	 * undo steps).
	 *
	 * @param DrawableId drawableId The mesh to edit next.
	 */
	fun switchEditDrawable(drawableId: DrawableId) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val model = mutableModel.value
		if (model.drawables.none { drawable -> drawable.id == drawableId && drawable.mesh != null }) {
			return
		}
		elementMemory.stash(mutableMeshSelection.value)
		elementMemory.lastActiveDrawableId = drawableId
		val newObjectSelection = SelectionOps.replace(SelectionTarget.Drawable(drawableId))
		val newMeshSelection = elementMemory.restore(MeshSelection.editing(listOf(drawableId)), model)
		if (newObjectSelection == mutableSelection.value && newMeshSelection == mutableMeshSelection.value) {
			return
		}
		history.push(
			snapshot(model = model, selection = newObjectSelection, meshSelection = newMeshSelection),
			EditorStateChange.MeshSelectionChanged,
		)
		mutableSelection.value = newObjectSelection
		mutableMeshSelection.value = newMeshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectionChanged)
	}

	/**
	 * Fires when an in-flight modal mesh gesture should confirm. The working positions live in the desktop
	 * overlay, so the session cannot commit directly - it signals here and the overlay commits. This is the
	 * keyboard path (Enter); a primary click confirms in the overlay's own pointer loop.
	 */
	val meshConfirmRequests: SharedFlow<Unit> = requestBus.meshConfirmRequests

	/** Requests that the gizmo overlay confirm the in-flight modal gesture (bound to Enter, like a click). */
	fun requestMeshConfirm() {
		requestBus.requestMeshConfirm()
	}

	/**
	 * Fires when an in-flight selection gesture should be abandoned (Escape).  The box rubber-band and the
	 * circle stroke live in the overlay's local state, so the session cannot discard them directly - it
	 * signals here and the overlay clears them.  Clearing a latched tool ([clearSelectTool]) already tells
	 * the overlay to abandon its gesture through the tool flow; this is the extra path for a non-armed box
	 * drag, which owns no tool state to change.
	 */
	val meshGestureCancelRequests: SharedFlow<Unit> = requestBus.meshGestureCancelRequests

	/** Requests that the gizmo overlay abandon any in-flight box / circle selection gesture (bound to Escape). */
	fun requestMeshGestureCancel() {
		requestBus.requestMeshGestureCancel()
	}

	/** Steps back one undo level, republishing the model and selection. No-op when nothing to undo. */
	fun undo() {
		restore(history.undo() ?: return)
	}

	/** Steps forward one redo level, republishing the model and selection. No-op when nothing to redo. */
	fun redo() {
		restore(history.redo() ?: return)
	}

	/**
	 * Jumps the history cursor directly to [index], republishing the model and selection at that step. The
	 * history panel calls this when a row is clicked, so the user can leap across several undo levels at
	 * once. No-op when [index] is already the live step.
	 *
	 * @param Int index The target step index within [historyView].
	 */
	fun jumpTo(index: Int) {
		restore(history.jumpTo(index) ?: return)
	}

	/**
	 * Marks the current model as the saved baseline, clearing the dirty marker. Called after a successful
	 * Save. (The PuppetModel -> CMO3 lowering that actually persists edits is a later phase; this only
	 * moves the dirty baseline.)
	 */
	fun markSaved() {
		history.markSaved(mutableModel.value)
		refreshFlags()
	}

	/**
	 * Restores every session flow from [snapshot] - the history mechanism behind undo, redo, and jumpTo.
	 * Also updates the remembered active drawable and tears down all transient tool state (see the inline
	 * comment), then republishes the derived flags.
	 *
	 * @param EditorSnapshot snapshot The history snapshot to restore.
	 */
	private fun restore(snapshot: EditorSnapshot) {
		// The remembered drawable tracks whatever was most recently shown active, undo/redo included.
		(snapshot.selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		mutableModel.value = snapshot.model
		mutableSelection.value = snapshot.selection
		mutablePose.value = snapshot.pose
		mutableMeshSelection.value = snapshot.meshSelection
		mutableParameterSelection.value = snapshot.parameterSelection
		// Restored, not cleared: the snapshot carries the pose these values were chosen for, so restoring the
		// pair together keeps them coherent - an undo must land on the step's pose WITH the step's pending
		// edits, not on the pose alone.
		mutablePendingChannelEdits.value = snapshot.pendingChannelEdits
		mutableKeySelection.value = snapshot.keySelection
		// An undo / redo ends any in-flight gesture or armed tool, regardless of the restored mode: the select
		// tool and its overlays are shared across modes, so a tool armed in one mode must not survive a restore
		// into a snapshot of the other and drive the wrong overlay.
		latches.clearTransient(clearAxisConstraint = true, clearViewportGesture = true)
		latches.setPreviewSelection(null)
		latches.closePieMenu()
		mutableMode.value = snapshot.mode
		refreshFlags()
	}

	/**
	 * Republishes the flags derived from the model and the history stack - dirty, canUndo, canRedo, and
	 * the projected history view.  Called after every mutation, restore, and saved-baseline move.
	 */
	private fun refreshFlags() {
		history.refreshFlags(mutableModel.value)
	}
}