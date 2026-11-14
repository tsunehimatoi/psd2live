package io.github.psd2live.util

import kotlin.test.*

class FramePresentationTest {
    @Test fun preservesOnlyMatchingPublishedObservationThroughEitherDeliveryOrder() {
        val edit = WorkspaceEditCorrelation(80, null, "replace", "project", "load", "before", "old", 1)
        val observation = WorkspacePublishedObservation(edit, WorkspaceBuildCorrelation(edit, 2, "candidate"), "state", "adopted", 5)
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("adopted", "state", 10, observation, receipts::add).apply { retain("a"); retain("b") }
        assertNotNull(token.frame("a")).apply { publish(); gpuCompleted(20) }
        assertNotNull(token.frame("b")).apply { gpuCompleted(21); publish() }
        assertTrue(receipts.all { it.observation === observation && it.candidate == token.candidate })
        assertEquals(2, receipts.size)
        for (wrong in listOf(observation.copy(state = "other"), observation.copy(revision = "other"),
            observation.copy(publishedAtNanos = 11), observation.copy(publishedAtNanos = 0))) {
            assertNull(PresentationToken("adopted", "state", 10, wrong, completed = null).observation)
        }
    }

    @Test fun publicationAloneDoesNotCompleteAndGpuMayFinishBeforeDelivery() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("revision", "state", 10, completed = receipts::add)
        token.retain("a")
        token.retain("b")
        val a = assertNotNull(token.frame("a"))
        assertTrue(a.publish())
        assertTrue(receipts.isEmpty())
        a.gpuCompleted(20)
        assertEquals(listOf("a"), receipts.map { it.viewId })
        val b = assertNotNull(token.frame("b"))
        b.gpuCompleted(25)
        assertEquals(1, receipts.size)
        b.publish()
        assertEquals(25L, receipts.last().gpuCompletedAtNanos)
        assertEquals(10L, receipts.last().capturedAtNanos)
    }

    @Test fun oldCandidateAndRemovedMountCannotCompleteAfterNewActivation() {
        val receipts = ArrayList<PresentationReceipt>()
        val old = PresentationToken("same-revision", "state", 10, completed = receipts::add)
        old.retain("view")
        val stale = assertNotNull(old.frame("view"))
        stale.publish()
        old.invalidate()
        val next = PresentationToken("same-revision", "state", 10, completed = receipts::add)
        assertNotEquals(old.candidate, next.candidate)
        next.retain("view")
        val removed = assertNotNull(next.frame("view"))
        removed.publish()
        next.remove("view")
        next.retain("view")
        stale.gpuCompleted(20)
        removed.gpuCompleted(21)
        assertFalse(stale.publish())
        assertFalse(removed.current)
        assertTrue(receipts.isEmpty())
        assertNotNull(next.frame("view")).apply { publish(); gpuCompleted(30) }
        assertEquals(next.candidate, receipts.single().candidate)
    }

    @Test fun separateTextureDrawsCannotBorrowPublicationAndCompletionFromEachOther() {
        val receipts = ArrayList<PresentationReceipt>()
        val token = PresentationToken("r", "s", 10, completed = receipts::add)
        token.retain("view")
        val first = assertNotNull(token.frame("view"))
        val second = assertNotNull(token.frame("view"))
        first.publish()
        second.gpuCompleted(30)
        assertTrue(receipts.isEmpty())
        second.publish()
        first.gpuCompleted(40)
        second.gpuCompleted(50)
        assertEquals(30L, receipts.single().gpuCompletedAtNanos)
    }

    @Test fun measurementOffOrUnknownRevisionNeverStopsNormalFrames() {
        for (token in listOf(PresentationToken("r", "s", completed = null),
            PresentationToken(null, null, completed = { error("Unknown revision cannot be measured") }))) {
            token.retain("view")
            val frame = assertNotNull(token.frame("view"))
            assertFalse(frame.measured)
            assertTrue(frame.publish())
            frame.gpuCompleted(System.nanoTime())
            assertTrue(frame.current)
        }
    }

    @Test fun failingObserverRunsOutsideTokenAndFrameLocksAndCannotBreakEitherDeliveryOrder() {
        var calls = 0
        lateinit var token: PresentationToken
        lateinit var frame: PresentationFrame
        token = PresentationToken("r", "s", 10) {
            assertFalse(Thread.holdsLock(token))
            assertFalse(Thread.holdsLock(frame))
            calls++
            error("diagnostic failure")
        }
        for ((view, gpuFirst) in listOf("a" to true, "b" to false)) {
            token.retain(view)
            frame = assertNotNull(token.frame(view))
            if (gpuFirst) frame.gpuCompleted(20)
            assertTrue(frame.publish())
            frame.gpuCompleted(30)
            assertTrue(frame.publish())
        }
        assertEquals(2, calls)
    }
}
