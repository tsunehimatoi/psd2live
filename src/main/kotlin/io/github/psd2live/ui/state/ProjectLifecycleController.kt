package io.github.psd2live.ui.state

import io.github.psd2live.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Save/Discard navigation and queued Save As are session intent, never a second project store. */
internal class ProjectLifecycleController(
    private val state: () -> PSD2LiveState,
    private val updateState: ((PSD2LiveState) -> PSD2LiveState) -> Unit,
    private val hasUnsavedChanges: () -> Boolean,
    private val flushEditorFields: () -> Unit,
    private val saveNow: suspend (Path?) -> String,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private var pendingDestructiveAction: (() -> Unit)? = null
    private var pendingDestructiveCancellation: (() -> Unit)? = null
    var confirmUnsavedChanges: (() -> Int)? = null
    private var queuedCanvasSave: Boolean? = null
    private var navigationSequence = 0L
    private var saveSequence = 0L

    fun withSavedChanges(action: () -> Unit) = withSavedChangesOrCancel({}, action)

    private fun cancelPendingDestructiveAction() {
        navigationSequence++
        pendingDestructiveAction = null
        pendingDestructiveCancellation?.also { pendingDestructiveCancellation = null; it() }
    }

    fun withSavedChangesOrCancel(onCancel: () -> Unit, action: () -> Unit) {
        if (state().projectSaving) { onCancel(); return }
        // Anything half-typed is closed first, so the dirty check and the confirm dialog below see the
        // value the user actually ended on rather than the last committed one.
        flushEditorFields()
        if (!state().projectDirty && hasUnsavedChanges() != true &&
            !state().workspaceEditBusy && !state().editorDraftBusy) { action(); return }
        when (confirmUnsavedChanges?.invoke() ?: 2) {
            0 -> {
                cancelPendingDestructiveAction()
                pendingDestructiveAction = action
                pendingDestructiveCancellation = onCancel
                requestProjectSave()
            }
            1 -> { cancelPendingDestructiveAction(); queuedCanvasSave = null; action() }
            else -> { cancelPendingDestructiveAction(); onCancel() }
        }
    }
    /**
     * The save asked for while an edit was being applied: true for Save As. One slot, and a Save As in it is never
     * turned into a plain save by a later Ctrl+S (the Save As dialog saves the same content), nor dropped.
     */
    internal val queuedProjectSave: Boolean? get() = queuedCanvasSave

    fun requestProjectSave(saveAs: Boolean = false) {
        // A save captures the workspace, so it has to see the value still sitting in a focused field.
        flushEditorFields()
        if (state().workspaceEditBusy || state().editorDraftBusy) {
            val queued = queuedCanvasSave == true || saveAs
            queuedCanvasSave = queued
            updateState { it.copy(statusText = tr(if (queued) "project.saveAsQueued" else "project.saveQueued")) }
            return
        }
        if (state().analysis == null) return
        if (saveAs || state().projectFile == null) {
            updateState { it.copy(showProjectLocationDialog = true, projectSaveError = null) }
        } else saveProjectTo(Path.of(state().projectFile!!))
    }
    /** Runs the save [requestProjectSave] queued, once no edit is being applied; a Save As opens its dialog. */
    internal fun runQueuedProjectSave() {
        if (state().workspaceEditBusy || state().editorDraftBusy) return
        val saveAs = queuedCanvasSave ?: return
        queuedCanvasSave = null
        if (state().statusText == tr(if (saveAs) "project.saveAsQueued" else "project.saveQueued")) updateState { it.copy(statusText = tr("status.ready")) }
        requestProjectSave(saveAs)
    }
    fun clearProjectSaveError() { updateState { it.copy(projectSaveError = null) } }
    fun cancelProjectLocation() {
        cancelPendingDestructiveAction()
        updateState { it.copy(showProjectLocationDialog = false) }
    }
    fun saveProjectTo(path: Path) {
        val owner = state()
        val request = ++saveSequence
        val navigation = navigationSequence
        fun ownsProject() = state().projectId == owner.projectId && state().projectOpenGeneration == owner.projectOpenGeneration
        fun ownsRequest() = request == saveSequence && ownsProject()
        scope.launch {
            try {
                // Every accepted save for this project still executes; only its UI completion is supersedable.
                if (!ownsProject()) return@launch
                saveNow(path)
                if (!ownsRequest()) return@launch
                updateState { it.copy(showProjectLocationDialog = false) }
                if (navigation == navigationSequence && !state().projectDirty && !hasUnsavedChanges() &&
                    !state().workspaceEditBusy && !state().editorDraftBusy)
                    pendingDestructiveAction?.also {
                        pendingDestructiveAction = null
                        pendingDestructiveCancellation = null
                        it()
                    }
            } catch (_: Throwable) {
                if (ownsRequest() && navigation == navigationSequence) cancelPendingDestructiveAction()
            }
        }
    }

    override fun close() {
        saveSequence++
        queuedCanvasSave = null
        cancelPendingDestructiveAction()
    }
}
