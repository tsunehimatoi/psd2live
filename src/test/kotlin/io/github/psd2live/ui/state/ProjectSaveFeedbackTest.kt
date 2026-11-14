package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import kotlin.test.*

class ProjectSaveFeedbackTest {
    private val dirty = PSD2LiveState(projectId = "project", projectFile = "model.psd2live", projectDirty = true)

    @Test fun recoveryAcknowledgementNeverClaimsThatTheArchiveWasSaved() {
        val state = dirty.copy(recoveryPersistence = WorkspacePersistenceState("project", "2", WorkspacePersistencePhase.SAVED))
        assertEquals("project.feedback.recoverySaved", projectSaveFeedback(state, "2", true, false)!!.key)
        assertEquals("project.feedback.committed", projectSaveFeedback(state, "3", true, false)!!.key)
        assertEquals("project.feedback.draft", projectSaveFeedback(state, "2", true, true)!!.key)
    }

    @Test fun runtimeDirtyPreventsAStaleCleanProjectionFromShowingSaved() {
        val state = dirty.copy(projectDirty = false)
        assertEquals("project.feedback.committed", projectSaveFeedback(state, "3", true, false)!!.key)
        assertEquals("project.feedback.saved", projectSaveFeedback(state, "3", false, false)!!.key)
    }

    @Test fun failuresAreRetryableOnlyForTheirOwnCurrentProjectAndRevision() {
        val state = dirty.copy(recoveryPersistence = WorkspacePersistenceState("project", "3", WorkspacePersistencePhase.FAILED, "Disk full"))
        val failed = projectSaveFeedback(state, "3", true, false)!!
        assertTrue(failed.retryRecovery)
        assertEquals("Disk full", failed.error)
        assertFalse(projectSaveFeedback(state, "4", true, false)!!.retryRecovery)
        assertFalse(projectSaveFeedback(state.copy(projectId = "other"), "3", true, false)!!.retryRecovery)
        assertEquals("project.saving", projectSaveFeedback(state.copy(projectSaving = true), "3", true, false)!!.key)
    }
}
