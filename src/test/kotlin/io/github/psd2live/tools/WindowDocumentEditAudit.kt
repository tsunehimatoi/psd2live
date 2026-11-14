package io.github.psd2live.tools

import io.github.psd2live.util.PresentationReceipt

/** Shared by raw rows and the formal edit distribution; activation timing remains a separate metric. */
internal fun windowDocumentEditDuration(receipt: PresentationReceipt): Long {
    val published = checkNotNull(receipt.observation) { "A real edit sample needs a committed observation" }
    check(published.state == receipt.state && published.revision == receipt.revision) { "Edit/receipt identity mismatch" }
    check(published.edit.requestedAtNanos <= published.publishedAtNanos &&
        published.publishedAtNanos <= receipt.capturedAtNanos && receipt.capturedAtNanos <= receipt.gpuCompletedAtNanos)
    return receipt.gpuCompletedAtNanos - published.edit.requestedAtNanos
}
