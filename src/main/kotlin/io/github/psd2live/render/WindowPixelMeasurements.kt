package io.github.psd2live.render

import io.github.psd2live.util.PresentationFrame
import org.jetbrains.skia.Bitmap

/** Bitmap belongs to the actual Skiko window surface after picture replay, not a preview/offscreen target. */
internal class WindowSurfaceReadback(
    val frame: PresentationFrame,
    val viewportLeft: Float,
    val viewportTop: Float,
    val gpuCompletedAtNanos: Long,
    /** Borrowed for the callback only; copy/encode before returning. */
    val bitmap: Bitmap,
)

/** Explicit development opt-in. Null callbacks allocate/read no window pixels in normal rendering. */
internal object WindowPixelMeasurements {
    /** Replay-context selection only: must be nonblocking and perform no I/O. */
    @Volatile var request: ((PresentationFrame) -> Boolean)? = null
    /** Receives the owned CPU copy outside the explicit picture lock; bitmap is borrowed for this call only. */
    @Volatile var onReadback: ((WindowSurfaceReadback) -> Unit)? = null
}
