package io.github.psd2live.tools

import io.github.psd2live.util.PresentationReceipt
import io.github.psd2live.util.PresentationToken

/** Acceptance bookkeeping only; it never fabricates a GPU completion or substitutes a CPU endpoint. */
internal class WindowActivationAudit {
    data class Expected(val revision: String, val state: String, val candidate: Long, val viewId: String,
        val capturedAtNanos: Long, val installStartedAtNanos: Long)
    private val forbidden = mutableSetOf<Long>()
    private val accepted = mutableSetOf<Long>()

    fun supersede(token: PresentationToken) { forbidden += token.candidate }

    fun expect(token: PresentationToken, revision: String, state: String, viewId: String, installStarted: Long): Expected {
        check(token.revision == revision && token.state == state) { "Activation did not capture the installed revision/state" }
        check(token.candidate !in forbidden && token.candidate !in accepted) { "Candidate reused" }
        check(token.capturedAtNanos >= installStarted) { "Activation predates synthetic installation" }
        return Expected(revision, state, token.candidate, viewId, token.capturedAtNanos, installStarted)
    }

    fun accept(expected: Expected, receipt: PresentationReceipt): Long {
        check(receipt.candidate !in forbidden) { "Superseded candidate received a window completion: ${receipt.candidate}" }
        check(receipt.candidate !in accepted) { "Duplicate candidate completion: ${receipt.candidate}" }
        check(receipt.revision == expected.revision && receipt.state == expected.state &&
            receipt.candidate == expected.candidate && receipt.viewId == expected.viewId &&
            receipt.capturedAtNanos == expected.capturedAtNanos) { "Window receipt identity mismatch: $receipt; expected $expected" }
        check(receipt.gpuCompletedAtNanos >= expected.capturedAtNanos) { "GPU endpoint precedes activation" }
        accepted += receipt.candidate
        return receipt.gpuCompletedAtNanos - expected.capturedAtNanos
    }
}
