package io.github.psd2live.tools

import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken
import kotlin.test.*

class WindowActivationAuditTest {
    @Test fun checksAllIdentityFieldsAndKeepsTheGpuEndpoint() {
        val token = PresentationToken("revision", "state", 20, completed = null)
        val audit = WindowActivationAudit()
        val expected = audit.expect(token, "revision", "state", "view", 10)
        val receipt = PresentationReceipt("revision", "state", token.candidate, "view", 20, 70)
        for (wrong in listOf(receipt.copy(revision = "other"), receipt.copy(state = "other"),
            receipt.copy(candidate = token.candidate + 1), receipt.copy(viewId = "other"),
            receipt.copy(capturedAtNanos = 19), receipt.copy(gpuCompletedAtNanos = 19))) {
            assertFailsWith<IllegalStateException> { audit.accept(expected, wrong) }
        }
        assertEquals(50L, audit.accept(expected, receipt))
        assertFailsWith<IllegalStateException> { audit.accept(expected, receipt) }
    }

    @Test fun supersededCandidateCannotMasqueradeAsAValidReceipt() {
        val audit = WindowActivationAudit()
        val old = PresentationToken("r", "s", 20, completed = null)
        val next = PresentationToken("r", "s", 21, completed = null)
        audit.supersede(old)
        val expected = audit.expect(next, "r", "s", "view", 10)
        assertFailsWith<IllegalStateException> {
            audit.accept(expected, PresentationReceipt("r", "s", old.candidate, "view", 20, 40))
        }
        assertFailsWith<IllegalStateException> { audit.expect(next, "wrong", "s", "view", 10) }
    }
}
