package org.umamo.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.PuppetModel

/**
 * The session's undo machinery: the snapshot [History] stack, the saved baseline (both the exact
 * snapshot for the history panel's marker and the model instance for the reference-equality dirty
 * flag), and the flags/view derived from them.  [EditorSession] pushes snapshots and republishes
 * flows from the ones this hands back; the derived flows here are exposed on the session unchanged.
 * Dirty is measured against the model only, so pose-only and selection-only steps never trip it.
 */
internal class HistoryCore(initialSnapshot: EditorSnapshot, initialHistoryLimit: Int) {
	private val history = History(initialSnapshot, initialHistoryLimit)

	/**
	 * The retained-undo-step cap.  Assigning trims the stack immediately, never past the live step; the
	 * session republishes the derived flags afterwards so the panel and the undo/redo enabled states
	 * follow the trim.
	 */
	var limit: Int
		get() = history.limit
		set(value) {
			history.limit = value
		}

	/**
	 * The live step's snapshot - what the last push (or the last undo) recorded.
	 *
	 * Distinct from the session's live flows, which run ahead of it whenever an edit is being previewed
	 * without being recorded.  A caller that must decide whether an edit would record anything new has to
	 * compare against THIS, not against the flows.
	 */
	val current: EditorSnapshot get() = history.current

	// The exact stack entry last persisted to disk, tracked by snapshot reference so the panel marks one
	// row precisely (the dirty flag below tracks the model instead - see EditorSession's class doc).
	private var savedSnapshot: EditorSnapshot = history.current

	// The model instance last persisted to disk; dirty is measured against it by reference.
	private var savedModel: PuppetModel = initialSnapshot.model

	private val mutableDirty = MutableStateFlow(false)

	/** True when the document model differs from the last-saved state. */
	val dirty: StateFlow<Boolean> = mutableDirty.asStateFlow()

	private val mutableCanUndo = MutableStateFlow(false)

	/** True when there is a step to undo. */
	val canUndo: StateFlow<Boolean> = mutableCanUndo.asStateFlow()

	private val mutableCanRedo = MutableStateFlow(false)

	/** True when there is a step to redo. */
	val canRedo: StateFlow<Boolean> = mutableCanRedo.asStateFlow()

	private val mutableHistoryView = MutableStateFlow(buildHistoryView())

	/** The undo stack projected for the history panel. */
	val historyView: StateFlow<HistoryView> = mutableHistoryView.asStateFlow()

	/**
	 * Records one undo step.
	 *
	 * @param EditorSnapshot snapshot The complete post-edit state.
	 * @param Change change The descriptor for the bus and the history-panel label.
	 */
	fun push(snapshot: EditorSnapshot, change: Change) {
		history.push(snapshot, change)
	}

	/**
	 * Steps the cursor back one level.
	 *
	 * @return EditorSnapshot? The snapshot to restore, or null with nothing to undo.
	 */
	fun undo(): EditorSnapshot? = history.undo()

	/**
	 * Steps the cursor forward one level.
	 *
	 * @return EditorSnapshot? The snapshot to restore, or null with nothing to redo.
	 */
	fun redo(): EditorSnapshot? = history.redo()

	/**
	 * Jumps the cursor directly to [index].
	 *
	 * @param Int index The target step index.
	 * @return EditorSnapshot? The snapshot to restore, or null when [index] is already live.
	 */
	fun jumpTo(index: Int): EditorSnapshot? = history.jumpTo(index)

	/**
	 * Moves the saved baseline to the current step: [model] becomes the dirty reference and the live
	 * stack entry becomes the panel's saved marker.
	 *
	 * @param PuppetModel model The model instance just persisted.
	 */
	fun markSaved(model: PuppetModel) {
		savedModel = model
		savedSnapshot = history.current
	}

	/**
	 * Republishes the derived flags - dirty, canUndo, canRedo, and the projected history view.  The
	 * session calls this after every mutation, restore, and saved-baseline move.
	 *
	 * @param PuppetModel currentModel The live document model dirty is measured against.
	 */
	fun refreshFlags(currentModel: PuppetModel) {
		mutableDirty.value = currentModel !== savedModel
		mutableCanUndo.value = history.canUndo
		mutableCanRedo.value = history.canRedo
		mutableHistoryView.value = buildHistoryView()
	}

	/**
	 * Builds the current [HistoryView] from the stack: one [HistoryStep] per entry (its label key and
	 * whether it is the saved baseline) plus the live cursor. The saved flag is by snapshot reference, so
	 * exactly one row is marked even when adjacent selection-only steps share a model instance.
	 *
	 * @return HistoryView The projected stack for the panel.
	 */
	private fun buildHistoryView(): HistoryView =
		HistoryView(
			steps =
				history.steps.map { entry ->
					HistoryStep(labelKey = entry.change?.labelKey, saved = entry.snapshot === savedSnapshot)
				},
			cursor = history.cursorIndex,
		)
}