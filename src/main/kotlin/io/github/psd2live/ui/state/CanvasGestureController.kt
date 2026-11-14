package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentOperation
import kotlinx.serialization.json.JsonObject

/** Warp control gestures prepare from one captured source and publish once on release. */
internal class CanvasGestureController(
    private val state: () -> PSD2LiveState,
    private val updateState: ((PSD2LiveState) -> PSD2LiveState) -> Unit,
    private val stateToken: () -> String?,
    private val submit: (String, String, List<WorkspaceDocumentOperation>, (String?) -> Unit) -> Unit,
) {
    private val warpControlFields = mutableMapOf<String, WarpControlField>()

    internal fun applyWarpControlField(token: String, operation: String, request: kotlinx.serialization.json.JsonObject) {
        val current = state()
        if (current.workspaceEditBusy || current.editorDraftBusy) return
        val source = current.previewModel ?: return
        val expected = stateToken() ?: return
        val edit = io.github.psd2live.application.WorkspaceDocumentOperation(operation, request)
        val field = warpControlFields.getOrPut(token) { WarpControlField(expected, current, WorkspaceStateCodec.document(current), source, edit) }
        try {
            val document = io.github.psd2live.application.WorkspaceWarpControlEdits.apply(edit, field.document, field.model)
            val records = document.rigEdits.authoringJournal.drop(field.document.rigEdits.authoringJournal.size)
            // The copies over the bangs follow the edited warps' meshes before the commit, as on the canvas.
            val puppet = io.github.psd2live.core.SeeThroughLayers.refresh(
                records.fold(field.model.rig.puppet) { model, record -> io.github.psd2live.core.RigAuthoringJournal.apply(model, record) }, field.model)
            val preview = field.model.copy(rig = field.model.rig.copy(puppet = puppet), config = field.model.config.copy(rigEdits = document.rigEdits))
            field.operation = edit; field.preview = preview
            updateState { it.copy(previewModel = preview, previewModelDirty = true, projectDirty = true) }
        } catch (failure: Exception) { updateState { it.copy(errorMessage = failure.message) } }
    }

    internal fun endWarpControlField(token: String) {
        val field = warpControlFields.remove(token) ?: return
        if (state().projectOpenGeneration != field.state.projectOpenGeneration) return
        submit(field.expectedState, "Edit Warp controls", listOf(field.operation)) { failure ->
            if (state().projectOpenGeneration != field.state.projectOpenGeneration) return@submit
            if (failure != null || stateToken() == field.expectedState) updateState { state ->
                if (state.previewModel === field.preview) state.copy(previewModel = field.state.previewModel,
                    previewModelDirty = field.state.previewModelDirty, projectDirty = field.state.projectDirty,
                    errorMessage = failure) else state.copy(errorMessage = failure)
            }
        }
    }

    fun flush() = warpControlFields.keys.toList().forEach(::endWarpControlField)
    fun clear() = warpControlFields.clear()
}
