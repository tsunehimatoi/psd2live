package io.github.psd2live.tools

import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WindowReceiptInboxTest {
    @Test fun onlyArrivalsBeforeTheCutAreArchivedEvenWhenALateReceiptHasAnOldGpuTime() = runBlocking {
        var now = 30L
        val inbox = WindowReceiptInbox { now }
        val setup = PresentationReceipt("setup", "s", 1, "view", 10, 20)
        inbox.record(setup)
        now = 40
        val boundary = inbox.beforeActivation()
        assertEquals(listOf(setup), boundary.received.map { it.receipt })
        assertEquals(40L, boundary.atNanos)
        val late = setup.copy(candidate = 2)
        now = 50
        inbox.record(late)
        assertEquals(late, inbox.receive().receipt)
        assertEquals(listOf(setup, late), inbox.raw().map { it.receipt })
    }

    @Test fun archivingCannotHideSupersededOrDuplicateReceipts() {
        val audit = WindowActivationAudit()
        val token = PresentationToken("r", "s", 10, completed = null)
        val receipt = PresentationReceipt("r", "s", token.candidate, "view", 10, 20)
        val received = WindowReceiptInbox.Received(1, 30, receipt)
        audit.supersede(token)
        assertFailsWith<IllegalStateException> { audit.archiveBeforeActivation(received, 40) }
        val valid = receipt.copy(candidate = token.candidate + 1)
        audit.archiveBeforeActivation(received.copy(receipt = valid), 40)
        assertFailsWith<IllegalStateException> { audit.archiveBeforeActivation(received.copy(receipt = valid), 40) }
        assertFailsWith<IllegalStateException> { audit.archiveBeforeActivation(received.copy(receivedAtNanos = 50, receipt = valid.copy(viewId = "another")), 40) }
    }

    @Test fun previousAcceptedCandidateIsRejectedAfterRetirementEvenUnderAnotherViewId() {
        val audit = WindowActivationAudit()
        val token = PresentationToken("r", "s", 10, completed = null)
        val receipt = PresentationReceipt("r", "s", token.candidate, "view", 10, 20)
        audit.accept(audit.expect(token, "r", "s", "view", 0), receipt)
        audit.archiveBeforeActivation(WindowReceiptInbox.Received(2, 30, receipt.copy(viewId = "imported-view")), 40)
        audit.supersede(token)
        assertFailsWith<IllegalStateException> {
            audit.archiveBeforeActivation(WindowReceiptInbox.Received(3, 50, receipt.copy(viewId = "late-view")), 60)
        }
    }
}
