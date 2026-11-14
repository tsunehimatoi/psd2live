package io.github.psd2live.render

import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken
import kotlin.test.*

class WindowFramePresentationTest {
    @Test fun replayOnlyCapturesCompletionAndDispatchPreservesItsClockOutsideTheLock() {
        val lock = Any()
        var held: Boolean? = null
        var receipt: PresentationReceipt? = null
        val token = PresentationToken("r", "s", 10) { held = Thread.holdsLock(lock); receipt = it }.apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        val completion = synchronized(lock) {
            prepareWindowPresentation(listOf(frame), { true }, {}, { 30 }).also { assertNull(receipt) }
        }
        assertNotNull(completion).dispatch()
        assertEquals(false, held)
        assertEquals(30L, assertNotNull(receipt).gpuCompletedAtNanos)
    }

    @Test fun invalidatedCandidateCannotCompleteWhileDeliveryIsDeferred() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, completed = receipts::add).apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        val completion = assertNotNull(prepareWindowPresentation(listOf(frame), { true }, {}, { 30 }))
        token.invalidate()
        completion.dispatch()
        assertTrue(receipts.isEmpty())
    }

    @Test fun overwrittenTextureCannotReceiveTheReplayReceipt() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, completed = receipts::add).apply { retain("view") }
        val first = assertNotNull(token.frame("view")).apply { publish() }
        val second = assertNotNull(token.frame("view")).apply { publish() }
        var submitted = false
        prepareWindowPresentation(listOf(first), { it === second }, { submitted = true }, { 20 })?.dispatch()
        assertFalse(submitted)
        assertTrue(receipts.isEmpty())
        prepareWindowPresentation(listOf(second), { it === second }, { submitted = true }, { 30 })?.dispatch()
        assertTrue(submitted)
        assertEquals(30L, receipts.single().gpuCompletedAtNanos)
    }

    @Test fun failureOrCandidateReplacementDuringGpuWaitCannotComplete() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, completed = receipts::add).apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        assertFailsWith<IllegalStateException> {
            prepareWindowPresentation(listOf(frame), { true }, { error("GPU lost") }, { 20 })?.dispatch()
        }
        assertTrue(receipts.isEmpty())
        prepareWindowPresentation(listOf(frame), { true }, { token.invalidate() }, { 30 })?.dispatch()
        assertTrue(receipts.isEmpty())
    }

    @Test fun textureReplacedWhileWaitingCannotCompleteEvenIfCandidateIsUnchanged() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, completed = receipts::add).apply { retain("view") }
        val frame = assertNotNull(token.frame("view")).apply { publish() }
        var current = frame
        prepareWindowPresentation(listOf(frame), { it === current }, { current = assertNotNull(token.frame("view")) }, { 20 })?.dispatch()
        assertTrue(receipts.isEmpty())
    }
}
