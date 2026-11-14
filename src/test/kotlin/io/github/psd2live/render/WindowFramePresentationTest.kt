package io.github.psd2live.render

import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken
import kotlin.test.*

class WindowFramePresentationTest {
    @Test fun overwrittenTextureCannotReceiveTheReplayReceipt() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, receipts::add).apply { retain("view") }
        val first = assertNotNull(token.frame("view")).apply { publish() }
        val second = assertNotNull(token.frame("view")).apply { publish() }
        var submitted = false
        completeWindowPresentation(listOf(first), { it === second }, { submitted = true }, { 20 })
        assertFalse(submitted)
        assertTrue(receipts.isEmpty())
        completeWindowPresentation(listOf(second), { it === second }, { submitted = true }, { 30 })
        assertTrue(submitted)
        assertEquals(30L, receipts.single().gpuCompletedAtNanos)
    }

    @Test fun failureOrCandidateReplacementDuringGpuWaitCannotComplete() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, receipts::add).apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        assertFailsWith<IllegalStateException> {
            completeWindowPresentation(listOf(frame), { true }, { error("GPU lost") }, { 20 })
        }
        assertTrue(receipts.isEmpty())
        completeWindowPresentation(listOf(frame), { true }, { token.invalidate() }, { 30 })
        assertTrue(receipts.isEmpty())
    }

    @Test fun textureReplacedWhileWaitingCannotCompleteEvenIfCandidateIsUnchanged() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, receipts::add).apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        var current = frame
        completeWindowPresentation(listOf(frame), { it === current }, { current = assertNotNull(token.frame("view")) }, { 20 })
        assertTrue(receipts.isEmpty())
    }
}
