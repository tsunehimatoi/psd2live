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
    private val sceneOffset: () -> Pair<Float, Float>?,
) {
    private data class Recording(val previousPicture: Any?, val frames: MutableList<GpuFrame> = ArrayList(),
        var origins: java.util.IdentityHashMap<GpuFrame, Pair<Float, Float>>? = null)
    @Volatile private var recorded: Recording? = null
    private var recording: Recording? = null
    private var installed: Boolean? = null
    private val pictureField by lazy { SkiaLayer::class.java.getDeclaredField("picture").apply { isAccessible = true } }
    private val surfaceField by lazy {
        Class.forName("org.jetbrains.skiko.context.ContextHandler").getDeclaredField("surface").apply { isAccessible = true }
    }

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
                var completion: WindowPresentationCompletion? = null
                var pixels: (() -> Unit)? = null
                // SkiaLayer.draw uses the same reentrant lock. The receipt must describe the picture it replayed.
                synchronized(pictureLock) {
                    val batch = recorded
                    val picture = pictureField.get(layer)
                    original(canvas)
                    if (batch != null && picture != null && picture !== batch.previousPicture &&
                        batch === recorded && picture === pictureField.get(layer) && valid()) {
                        runCatching {
                            val skia = context() ?: return@runCatching
                            completion = prepareWindowPresentation(batch.frames.mapNotNull { it.presentation },
                                stillCurrent = { stamp -> valid() && batch === recorded &&
                                    batch.frames.any { it.presentation === stamp && current(it) } },
                                submit = { skia.flush(); skia.submit(true) })
                            completion?.let { pixels = readWindowPixels(batch, it.completedAtNanos) }
                        }.onFailure { System.err.println("Window presentation receipt failed: $it") }
                        if (batch === recorded) recorded = null
                    }
                }
                // Only GPU submission/readback need the replay lock. Receipt observers, pixel validation and
                // PNG/report encoding run after releasing it. Delivery retains the original GPU timestamp.
                try { completion?.dispatch() } finally { pixels?.invoke() }
            })
            true
        }.onFailure { System.err.println("Window presentation measurement unavailable: $it") }.getOrDefault(false)
        return installed == true
    }

    private fun readWindowPixels(batch: Recording, completedAt: Long): (() -> Unit)? {
        val callback = WindowPixelMeasurements.onReadback ?: return null
        val request = WindowPixelMeasurements.request ?: return null
        val origins = batch.origins ?: return null
        val offset = sceneOffset() ?: error("Window pixel measurement has no scene placement")
        val wanted = batch.frames.distinct().filter {
            it in origins && it.presentation?.camera != null && it.presentation.current && current(it) && request(it.presentation)
        }
        if (wanted.isEmpty()) return null
        val surface = surfaceField.get(handler) as? org.jetbrains.skia.Surface ?: error("Skiko window surface unavailable")
        val bitmap = org.jetbrains.skia.Bitmap()
        try {
            check(bitmap.allocPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(surface.width, surface.height)))
            check(surface.readPixels(bitmap, 0, 0)) { "Actual window surface readback failed" }
            val readbacks = wanted.mapNotNull { frame ->
                if (!valid() || batch !== recorded || !current(frame) || frame.presentation?.current != true) return@mapNotNull null
                val origin = origins.getValue(frame)
                WindowSurfaceReadback(frame.presentation, origin.first + offset.first, origin.second + offset.second, completedAt, bitmap)
            }
            return {
                bitmap.use {
                    readbacks.forEach { readback -> if (readback.frame.current) {
                        runCatching { callback(readback) }.onFailure { System.err.println("Window pixel observer failed: $it") }
                    } }
                }
            }
        } catch (error: Throwable) {
            bitmap.close()
            throw error
        }
    }

    companion object {
        private val active = ThreadLocal<WindowFramePresentation>()
        /** Called only after the viewport actually issues drawImage for this exact texture draw. */
        fun consume(frame: GpuFrame, left: Float? = null, top: Float? = null) {
            if (frame.presentation?.measured != true) return
            val batch = active.get()?.recording ?: return
            batch.frames.add(frame)
            if (WindowPixelMeasurements.onReadback != null && left != null && top != null) {
                val origins = batch.origins ?: java.util.IdentityHashMap<GpuFrame, Pair<Float, Float>>().also { batch.origins = it }
                origins[frame] = left to top
            }
        }
    }
}

/** The submission boundary is injectable so overwritten textures and failed GPU waits can be tested without GL. */
internal fun prepareWindowPresentation(
    frames: List<PresentationFrame>,
    stillCurrent: (PresentationFrame) -> Boolean,
    submit: () -> Unit,
    clock: () -> Long = System::nanoTime,
): WindowPresentationCompletion? {
    val consumed = frames.filter { it.measured && stillCurrent(it) }
    if (consumed.isEmpty()) return null
    submit()
    val completedAt = clock()
    return WindowPresentationCompletion(consumed.filter(stillCurrent), completedAt)
}

/** Captured under the replay lock; observers are dispatched only after leaving it. */
internal class WindowPresentationCompletion(private val frames: List<PresentationFrame>, val completedAtNanos: Long) {
    fun dispatch() { frames.forEach { it.gpuCompleted(completedAtNanos) } }
}
