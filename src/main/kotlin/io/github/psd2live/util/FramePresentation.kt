package io.github.psd2live.util

import java.util.concurrent.atomic.AtomicLong

/** Identity of one captured model activation. Never reconstructed from current state in a frame callback. */
class PresentationToken(
    val revision: String?,
    val state: String?,
    val capturedAtNanos: Long = System.nanoTime(),
    private val completed: ((PresentationReceipt) -> Unit)? = PresentationMeasurements.onCompleted,
) {
    val candidate: Long = candidates.incrementAndGet()
    private var active = true
    private val views = mutableMapOf<String, Any>()
    private val reported = mutableSetOf<Any>()

    @Synchronized fun retain(viewId: String) { views.getOrPut(viewId) { Any() } }
    @Synchronized fun remove(viewId: String) { views.remove(viewId)?.let(reported::remove) }
    @Synchronized fun invalidate() { active = false; views.clear(); reported.clear() }
    @Synchronized fun frame(viewId: String, camera: PresentationCamera? = null): PresentationFrame? =
        views[viewId]?.takeIf { active }?.let { PresentationFrame(this, viewId, it, camera) }
    @Synchronized internal fun current(frame: PresentationFrame): Boolean =
        active && frame.token === this && views[frame.viewId] === frame.mount
    internal fun report(frame: PresentationFrame, gpuCompletedAtNanos: Long) {
        val receipt = synchronized(this) {
            if (!current(frame) || revision == null || completed == null || !reported.add(frame.mount)) return
            PresentationReceipt(revision, state, candidate, frame.viewId, capturedAtNanos, gpuCompletedAtNanos)
        }
        // Diagnostics cannot break publication/rendering and run outside both token and frame locks.
        runCatching { completed?.invoke(receipt) }
            .onFailure { System.err.println("Window presentation observer failed: $it") }
    }
    internal val measured: Boolean get() = completed != null && revision != null
    private companion object { val candidates = AtomicLong() }
}

/** One draw into a reusable texture. A new instance is required even for the same model and texture. */
class PresentationFrame internal constructor(val token: PresentationToken, val viewId: String, internal val mount: Any,
    val camera: PresentationCamera? = null) {
    private var published = false
    private var gpuCompletedAt: Long? = null
    val current: Boolean get() = token.current(this)
    val measured: Boolean get() = token.measured && current
    fun publish(): Boolean {
        val completedAt = synchronized(this) {
            if (!current) return false
            published = true
            gpuCompletedAt
        }
        completedAt?.let { token.report(this, it) }
        return true
    }
    /** Called only after this draw was consumed by the viewport and replayed onto the window's GPU surface. */
    fun gpuCompleted(atNanos: Long) {
        val completedAt = synchronized(this) {
            if (!current || atNanos < token.capturedAtNanos) return
            gpuCompletedAt = minOf(gpuCompletedAt ?: atNanos, atNanos)
            gpuCompletedAt.takeIf { published }
        }
        completedAt?.let { token.report(this, it) }
    }
}

/** Camera of this exact native draw, captured by the backend rather than reconstructed from current UI state. */
data class PresentationCamera(val width: Int, val height: Int, val scale: Float, val offsetX: Float, val offsetY: Float)

data class PresentationReceipt(val revision: String, val state: String?, val candidate: Long, val viewId: String,
    val capturedAtNanos: Long, val gpuCompletedAtNanos: Long)

/** Development measurement opt-in. Install before model capture; normal rendering never waits for the GPU. */
object PresentationMeasurements {
    @Volatile var onCompleted: ((PresentationReceipt) -> Unit)? = null
}
