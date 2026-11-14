package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspacePersistencePhase

internal data class ProjectSaveFeedback(val key: String, val error: String? = null, val retryRecovery: Boolean = false)

/** Rendering projection only: acknowledgements belong to the coordinator and dirty state to the runtime. */
internal fun projectSaveFeedback(state: PSD2LiveState, runtimeState: String?, runtimeDirty: Boolean,
                                 fieldsOpen: Boolean): ProjectSaveFeedback? {
    if (state.projectId == null) return null
    if (state.projectSaving) return ProjectSaveFeedback("project.saving")
    state.projectSaveError?.let { return ProjectSaveFeedback("project.saveFailed", it) }
    if (fieldsOpen || state.editorDraftBusy) return ProjectSaveFeedback("project.feedback.draft")
    if (!state.projectDirty && !runtimeDirty && state.projectFile != null) return ProjectSaveFeedback("project.feedback.saved")
    val recovery = state.recoveryPersistence?.takeIf { it.projectId == state.projectId && it.state == runtimeState }
    return when (recovery?.phase) {
        WorkspacePersistencePhase.SAVING -> ProjectSaveFeedback("project.feedback.recoverySaving")
        WorkspacePersistencePhase.SAVED -> ProjectSaveFeedback("project.feedback.recoverySaved")
        WorkspacePersistencePhase.FAILED -> ProjectSaveFeedback("project.feedback.recoveryFailed", recovery.error, retryRecovery = true)
        else -> ProjectSaveFeedback("project.feedback.committed")
    }
}
