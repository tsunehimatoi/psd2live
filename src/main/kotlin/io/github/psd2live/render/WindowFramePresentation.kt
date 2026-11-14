package io.github.psd2live.render

import io.github.psd2live.util.PresentationMeasurements
import io.github.psd2live.util.PresentationFrame
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * Opt-in receipt after Skiko replays the recorded picture onto the actual window surface. onRender alone
 * only records a picture. The hook follows the same pinned Skiko internals as WindowCompositor; failure
 * disables measurement, never substitutes a recording timestamp. This does not observe buffer swap/scanout.
 */
internal class WindowFramePresentation(
    private val layer: SkiaLayer,
    private val handler: Any,
    private val context: () -> DirectContext?,
    private val valid: () -> Boolean,
    private val current: (GpuFrame) -> Boolean,
) {
    private data class Recording(val previousPicture: Any?, val frames: MutableList<GpuFrame> = ArrayList())
    @Volatile private var recorded: Recording? = null
    private var recording: Recording? = null
    private var installed: Boolean? = null
    private val pictureField by lazy { SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true } }

    fun begin() {
        recorded = null
        if (PresentationMeasurements.onCompleted == null || !install()) return
        recording = Recording(pictureField.get(layer))
        active.set(this)
    }

    fun end(success: Boolean) {
        if (active.get() === this) active.remove()
        recorded = recording?.takeIf { success && it.frames.isNotEmpty() }
        recording = null
    }

    private fun install(): Boolean {
        installed?.let { return it }
        installed = runCatching {
            val type = Class.forName("org.jetbrains.skiko.context.ContextHandler")
            val field = type.getDeclaredField("drawContent").apply { isAccessible = true }
            val pictureLock = SkiaLayer::class.java.getDeclaredField("pictureLock").apply { isAccessible = true }.get(layer)
            pictureField // Verify all reflection before changing the callback.
            @Suppress("UNCHECKED_CAST")
            val original = field.get(handler) as (Canvas) -> Unit
            field.set(handler, { canvas: Canvas ->
                // SkiaLayer.draw uses the same reentrant lock. The receipt must describe the picture it replayed.
                synchronized(pictureLock) {
                    val batch = recorded
                    val picture = pictureField.get(layer)
                    original(canvas)
                    if (batch != null && picture != null && picture !== batch.previousPicture &&
                        batch === recorded && picture === pictureField.get(layer) && valid()) {
                        runCatching {
                            val skia = context() ?: return@runCatching
                            completeWindowPresentation(batch.frames.mapNotNull { it.presentation },
                                stillCurrent = { stamp -> valid() && batch === recorded &&
                                    batch.frames.any { it.presentation === stamp && current(it) } },
                                submit = { skia.flush(); skia.submit(true) })
                        }.onFailure { System.err.println("Window presentation receipt failed: $it") }
                        if (batch === recorded) recorded = null
                    }
                }
            })
            true
        }.onFailure { System.err.println("Window presentation measurement unavailable: $it") }.getOrDefault(false)
        return installed == true
    }

    companion object {
        private val active = ThreadLocal<WindowFramePresentation>()
        /** Called only after the viewport actually issues drawImage for this exact texture draw. */
        fun consume(frame: GpuFrame) {
            if (frame.presentation?.measured != true) return
            active.get()?.recording?.frames?.add(frame)
        }
    }
}

/** The submission boundary is injectable so overwritten textures and failed GPU waits can be tested without GL. */
internal fun completeWindowPresentation(
    frames: List<PresentationFrame>,
    stillCurrent: (PresentationFrame) -> Boolean,
    submit: () -> Unit,
    clock: () -> Long = System::nanoTime,
) {
    val consumed = frames.filter { it.measured && stillCurrent(it) }
    if (consumed.isEmpty()) return
    submit()
    val completedAt = clock()
    consumed.forEach { if (stillCurrent(it)) it.gpuCompleted(completedAt) }
}
