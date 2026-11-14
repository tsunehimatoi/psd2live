package io.github.psd2live.tools

import io.github.psd2live.util.*
import kotlin.test.*

class WindowDocumentEditAuditTest {
    private fun receipt(): PresentationReceipt {
        val edit = WorkspaceEditCorrelation(1, null, "replace", "project", "load", "before", "old", 10)
        val publication = WorkspacePublishedObservation(edit, WorkspaceBuildCorrelation(edit, 1, "candidate"), "state", "revision", 60)
        return PresentationReceipt("revision", "state", 42, "view", 70, 100, publication)
    }

    @Test fun measuresFromPublicRequestInsteadOfLaterActivation() {
        assertEquals(90L, windowDocumentEditDuration(receipt()))
        assertNotEquals(receipt().gpuCompletedAtNanos - receipt().capturedAtNanos, windowDocumentEditDuration(receipt()))
    }

    @Test fun rejectsMissingOrMismatchedPublicationAndReversedClock() {
        for (wrong in listOf(receipt().copy(observation = null), receipt().copy(state = "other"),
            receipt().copy(revision = "other"), receipt().copy(capturedAtNanos = 50), receipt().copy(gpuCompletedAtNanos = 65))) {
            assertFailsWith<IllegalStateException> { windowDocumentEditDuration(wrong) }
        }
    }
}
