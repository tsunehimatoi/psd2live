package io.github.psd2live.render

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * The application's one GPU renderer lives in Skia's own OpenGL context: every canvas (editing, preview, texture
 * atlas) draws on the thread that renders the window, while that window's context is current, into textures
 * Skia owns ([GpuTarget]), and Compose draws those textures in the same frame. There is no second context, no
 * sharing between contexts, no pixel read back and nothing to synchronize.
 *
 * Skiko keeps the context private; [of] finds a window's `SkiaLayer` and reads its OpenGL redrawer's context
 * handle and the `DirectContext` that draws it. A window drawn otherwise (Direct3D, Metal, software) has no
 * [WindowGpu], and its canvases paint in software.
 */
object SkiaGpu {
	sealed interface Status {
		data object Starting : Status
		data class Ready(val description: String) : Status
		data class Unavailable(val reason: String) : Status
	}

	private val _status = MutableStateFlow<Status>(Status.Starting)
	/** Whether the main window renders with OpenGL, so the GPU renderer can draw in it. */
	val status: StateFlow<Status> = _status.asStateFlow()

	private val windows = WeakHashMap<Window, WindowGpu?>()
	private val observedLayers = WeakHashMap<SkiaLayer, Boolean>()
	private var primaryWindow = WeakReference<Window>(null)
	private val _contextChanges = MutableStateFlow(0L)
	/** Invalidates per-window lookups when Skiko replaces a renderer, including floating windows. */
	val contextChanges: StateFlow<Long> = _contextChanges.asStateFlow()

	private val _primary = MutableStateFlow<WindowGpu?>(null)
	/** The main window's GPU; runtimes whose state lives in one context (Cubism) draw there. */
	val primaryFlow: StateFlow<WindowGpu?> = _primary.asStateFlow()
	val primary: WindowGpu? get() = _primary.value

	/**
	 * Asks Skiko for OpenGL on Windows, where it would pick Direct3D, with the default graphics settings unless the
	 * app applied its own ([GraphicsSettings.applyAtStartup]). Must run before the first window is created.
	 */
	fun requestOpenGl() {
		if (GraphicsSettings.startup == null) GraphicsSettings.applyAtStartup(GraphicsOptions())
	}

	internal fun refreshStatus(gpu: WindowGpu) {
		if (gpu === primary) _status.value = Status.Ready(gpu.description)
	}

	private val primaryWaiters = ConcurrentLinkedQueue<(WindowGpu) -> Unit>()

	/** Runs [action] with the main window's GPU once it is captured (at once if it is). */
	fun whenPrimary(action: (WindowGpu) -> Unit) {
		primary?.let { action(it); return }
		primaryWaiters += action
		primary?.let { gpu -> while (true) { val next = primaryWaiters.poll() ?: break; next(gpu) } }
	}

	/** Makes [window] the main window once it draws; retries while Skiko is still creating its redrawer. */
	fun capturePrimary(window: Window) {
		primaryWindow = WeakReference(window)
		fun attempt(remaining: Int) {
			val gpu = of(window)
			if (gpu != null) {
				gpu.install()
				_primary.value = gpu
				_status.value = Status.Ready(gpu.description)
				while (true) { val next = primaryWaiters.poll() ?: break; next(gpu) }
				return
			}
			if (remaining == 0) {
				_status.value = Status.Unavailable(failure(window))
				return
			}
			javax.swing.Timer(50) { attempt(remaining - 1) }.apply { isRepeats = false }.start()
		}
		javax.swing.SwingUtilities.invokeLater { attempt(40) }
	}

	/** [window]'s GPU, or null when it does not render with OpenGL (yet). */
	fun of(window: Window?): WindowGpu? {
		window ?: return null
		synchronized(windows) {
			windows[window]?.let { if (it.valid()) return it else it.rendererLost() }
			val made = runCatching { resolve(window) }.getOrNull()
			windows[window] = made
			return made
		}
	}

	private fun failure(window: Window): String = runCatching {
		val layer = findLayer(window) ?: return "The window has no Skia layer"
		val redrawer = redrawerGetter?.invoke(layer) ?: return "The window has no redrawer yet"
		"The window draws with ${redrawer.javaClass.simpleName}"
	}.getOrElse { it.message ?: it.javaClass.simpleName }

	// --- reflection on Skiko ---

	private val redrawerGetter: Method? by lazy {
		runCatching { SkiaLayer::class.java.getMethod("getRedrawer\$skiko") }.getOrNull()
	}
	private val contextField: Field? by lazy {
		runCatching {
			Class.forName("org.jetbrains.skiko.context.ContextHandler").getDeclaredField("context").apply { isAccessible = true }
		}.getOrNull()
	}

	private fun resolve(window: Window): WindowGpu? {
		val layer = findLayer(window) ?: return null
		observeRenderer(window, layer)
		val redrawer = redrawerGetter?.invoke(layer) ?: return null
		val binding = GlBinding.of(layer, redrawer) ?: return null
		val handler = redrawer.javaClass.getDeclaredField("contextHandler").apply { isAccessible = true }.get(redrawer) ?: return null
		val field = contextField ?: return null
		return WindowGpu(layer, redrawer, binding, handler, field)
	}

	private fun observeRenderer(window: Window, layer: SkiaLayer) {
		if (observedLayers.put(layer, true) != null) return
		val owner = WeakReference(window)
		layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
			// The callback can precede the replacement redrawer's initialization. Resolve after it completes.
			javax.swing.SwingUtilities.invokeLater {
				val currentWindow = owner.get() ?: return@invokeLater
				val replacement = of(currentWindow)
				if (primaryWindow.get() === currentWindow) {
					_primary.value = replacement
					_status.value = replacement?.let { Status.Ready(it.description) } ?: Status.Unavailable(failure(currentWindow))
					if (replacement != null) while (true) { val next = primaryWaiters.poll() ?: break; next(replacement) }
				}
				_contextChanges.value += 1
				currentWindow.repaint()
			}
		}
	}

	private fun findLayer(root: java.awt.Component): SkiaLayer? {
		if (root is SkiaLayer) return root
		if (root is Container) for (child in root.components) findLayer(child)?.let { return it }
		return null
	}

	/** Whether the redrawer [layer] uses is still [redrawer]: Skiko replaces it when it falls back to another API. */
	internal fun sameRedrawer(layer: SkiaLayer, redrawer: Any): Boolean = runCatching { redrawerGetter?.invoke(layer) === redrawer }.getOrDefault(false)
}

/**
 * One window's Skia OpenGL context, as the GPU renderer uses it.
 *
 * GL work runs only on the window's render thread while its context is current: inside a canvas's draw
 * ([turn]) or, for work no canvas is drawing (loads, releases, a runtime's own bookkeeping), as [post]ed tasks
 * that run when the window next renders, before Compose draws, or when a canvas of the window [drain]s them.
 */
class WindowGpu internal constructor(
	private val layer: SkiaLayer,
	private val redrawer: Any,
	private val binding: GlBinding,
	private val handler: Any,
	private val contextField: Field,
) {
	/** The `HGLRC` or `GLXContext` Skia draws with. */
	internal val glContext: Long get() = binding.context

	private val presentation = WindowFramePresentation(layer, handler, ::directContext,
		{ valid() && isCurrentContext() }, { frame -> frame.presentation?.let { resources.frame(it.viewId) === frame } == true },
        { compositor?.presentationOffset() })
	private val tasks = ConcurrentLinkedQueue<(GpuResources) -> Unit>()
	private var installed: SkikoRenderDelegate? = null
	private var compositor: WindowCompositor? = null
	private val frameWaiters = ArrayList<kotlinx.coroutines.CancellableContinuation<Long>>()
	private val _composited = androidx.compose.runtime.mutableStateOf(false)

	/**
	 * Whether the window draws its Compose picture over its [CompositedView]s ([WindowCompositor]): a preview canvas
	 * then leaves a hole for its view instead of drawing its frame. Snapshot state, read where canvases draw, so they
	 * draw again when it changes.
	 */
	val composited: androidx.compose.runtime.State<Boolean> get() = _composited

	/** Whether the frame being rendered composites now, read from inside a canvas's draw. */
	val compositing: Boolean get() = compositor?.active == true

	/** The compositor's frames and the frames its picture was replayed in, for the development tools. */
	val compositorFrames: Pair<Long, Long> get() = composedFrames.get() to pictureFrames.get()
	internal val composedFrames = java.util.concurrent.atomic.AtomicLong()
	internal val pictureFrames = java.util.concurrent.atomic.AtomicLong()

	internal val contentScale: Float get() = runCatching { layer.contentScale }.getOrDefault(1f)
	private var capabilities: org.lwjgl.opengl.GLCapabilities? = null
	/** What this context holds for the renderer; touched only during a turn. */
	internal val resources = GpuResources(this)
	@Volatile var description: String = "OpenGL"
		private set
	/** Whether Skia's context is a core profile, where renderers written for compatibility GL (Cubism's) cannot draw. */
	@Volatile var coreProfile: Boolean = false
		private set

	internal fun valid(): Boolean = SkiaGpu.sameRedrawer(layer, redrawer)

	internal fun rendererLost() {
		_composited.value = false
		resumeFrameWaiters(System.nanoTime())
	}

	/** The `DirectContext` that draws the window; null before its first frame. */
	internal fun directContext(): DirectContext? = runCatching { contextField.get(handler) as? DirectContext }.getOrNull()

	internal fun isCurrentContext(): Boolean = isCurrent()
	internal val pixelGeometry: org.jetbrains.skia.PixelGeometry? get() = runCatching { layer.pixelGeometry }.getOrNull()

	/** Whether this context is current on the calling thread, which is what lets GL calls reach it. */
	private fun isCurrent(): Boolean = binding.isCurrent()

	/**
	 * Runs [block] with the context current and hands Skia back a context whose state it must assume changed;
	 * null when the calling thread is not rendering this window (the block does not run).
	 */
	fun <T> turn(block: (GpuResources) -> T): T? {
		if (!valid()) return null
		// Installed before the context check: the installed delegate is what makes the context current (Linux).
		if (javax.swing.SwingUtilities.isEventDispatchThread()) ensureInstalled()
		if (!isCurrent()) return null
		val skia = directContext() ?: return null
		if (capabilities == null) {
			capabilities = GL.createCapabilities()
			val profile = GL11.glGetInteger(0x9126) // GL_CONTEXT_PROFILE_MASK: 1 core, 2 compatibility, 0 legacy
			coreProfile = profile and 1 != 0
			description = "${GL11.glGetString(GL11.GL_RENDERER)} | ${GL11.glGetString(GL11.GL_VERSION)}${if (coreProfile) " core" else ""}"
			println("OpenGL: $description")
			if (this === SkiaGpu.primary) SkiaGpu.refreshStatus(this)
		} else {
			GL.setCapabilities(capabilities)
		}
		// No glGetError here: it waits for the driver's thread where the driver has one (NVIDIA's threaded optimization,
		// Mesa's glthread) or for the host (WSL, virtual machines). Code that checks errors clears them just before.
		try {
			resources.restoreDefaults()
			runTasks()
			return block(resources)
		} finally {
			// Skia sets again whatever it uses; the next turn starts from restoreDefaults.
			skia.resetGLAll()
		}
	}

	/**
	 * Runs the posted tasks now if this thread is rendering the window. A task's own failure is caught in
	 * [runTasks]; this also catches the turn's setup and state restores, which run inside the window's draw too.
	 */
	fun drain() {
		if (tasks.isNotEmpty()) try {
			turn { }
		} catch (failure: Exception) {
			System.err.println("GPU turn failed: $failure")
		}
	}

	/** Queues [task] for the next turn and asks the window to render one; any thread. */
	fun post(task: (GpuResources) -> Unit) {
		tasks += task
		javax.swing.SwingUtilities.invokeLater { ensureInstalled(); requestRedraw() }
	}

	private fun runTasks() {
		while (true) {
			val task = tasks.poll() ?: return
			try {
				task(resources)
			} catch (failure: Throwable) {
				System.err.println("GPU task failed: $failure")
			}
		}
	}

	/** Wraps the window's render delegate now, so its next frame runs with the context current; on the EDT. */
	internal fun install() = ensureInstalled()

	/** Shows [view] under the window's picture until [hideView]; see [CompositedView]. */
	fun showView(viewId: String, view: CompositedView) { compositor?.show(viewId, view) }

	fun hideView(viewId: String) { compositor?.hide(viewId) }

	/**
	 * Suspends until the window next renders and returns its frame time, asking for that frame. A preview's frame
	 * pump waits here rather than on Compose's frame clock: a waiter there makes Compose draw the whole window
	 * every frame. Resumed at the start of the frame, before its GPU tasks run, so a caller on an immediate EDT
	 * dispatcher requests its preview frame in time for this one.
	 *
	 * Under a frame rate limit ([GraphicsSettings.frameRateCap], lower while the window is not active) the frame is
	 * asked for only once the limit allows it: previews are what keep a window drawing continuously.
	 */
	suspend fun awaitFrame(): Long {
		val cap = GraphicsSettings.frameRateCap(active = windowActive())
		if (cap > 0) {
			// Asked for half a refresh early: the frame then starts at the refresh nearest the time due, rather than
			// the one after it, which would bring 30 FPS down to 24 on a 60 Hz display.
			val wait = lastWaitedFrame + 1_000_000_000L / cap - refreshNanos() / 2 - System.nanoTime()
			if (wait > 1_000_000L) kotlinx.coroutines.delay(wait / 1_000_000L)
		}
		return kotlinx.coroutines.suspendCancellableCoroutine { waiter ->
			synchronized(frameWaiters) { frameWaiters += waiter }
			waiter.invokeOnCancellation { synchronized(frameWaiters) { frameWaiters -= waiter } }
			javax.swing.SwingUtilities.invokeLater { ensureInstalled(); requestRedraw() }
		}
	}

	/** When the last frame a preview waited for started ([System.nanoTime]). */
	@Volatile private var lastWaitedFrame = 0L

	private fun windowActive(): Boolean = runCatching { javax.swing.SwingUtilities.getWindowAncestor(layer)?.isActive }.getOrNull() ?: true

	private var refreshPeriod = 0L
	private var refreshCheckedAt = 0L

	/** The display's refresh period, read at most once a second; 0 when the display does not say. */
	private fun refreshNanos(): Long {
		val now = System.nanoTime()
		if (now - refreshCheckedAt > 1_000_000_000L) {
			refreshCheckedAt = now
			val rate = runCatching { layer.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0 }.getOrDefault(0)
			refreshPeriod = if (rate > 0) 1_000_000_000L / rate else 0L
		}
		return refreshPeriod
	}

	private fun resumeFrameWaiters(nanoTime: Long) {
		val ready = synchronized(frameWaiters) {
			if (frameWaiters.isEmpty()) return
			ArrayList(frameWaiters).also { frameWaiters.clear() }
		}
		lastWaitedFrame = System.nanoTime()
		for (waiter in ready) if (waiter.isActive) runCatching { waiter.resume(nanoTime) }
	}

	/** Makes the window's context current for work before Skiko's own draw does; true when it is. */
	private fun makeCurrent(): Boolean = runCatching { binding.makeCurrent() }.getOrDefault(false)

	/** Runs posted tasks at the start of every frame the window renders, then draws it through the compositor. */
	private fun ensureInstalled() {
		if (!valid()) return
		val current = layer.renderDelegate ?: return
		if (current === installed) return
		val inner = current
		// The replaced compositor is not closed: its wrapper may be rendering now (this runs inside a frame's turn).
		val composer = WindowCompositor(this, inner)
		compositor = composer
		val wrapper = object : SkikoRenderDelegate {
			override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
				if (!valid()) return inner.onRender(canvas, width, height, nanoTime)
				// Replaced by a wrapper around this one (another delegate wrapped it meanwhile): that one does the work.
				if (compositor !== composer) return inner.onRender(canvas, width, height, nanoTime)
				presentation.begin()
				var presented = false
				try {
					val started = System.nanoTime()
					pacing.frame(started)
					resumeFrameWaiters(nanoTime)
					// Compose records before Skiko makes the context current (on Linux another window's may be): the
					// canvases drawing in this render need the window's own.
					val current = makeCurrent()
					drain()
					if (compositor !== composer) return inner.onRender(canvas, width, height, nanoTime)
					if (current) directContext()?.let(cacheBudget::apply)
					val skia = if (GraphicsSettings.compositing && current) directContext() else null
					val composited = skia != null && runCatching { composer.render(canvas, width, height, nanoTime, skia) }
						.onFailure { presentation.end(false); System.err.println("Window compositor failed: $it") }.getOrDefault(false)
					if (!composited && !multisampled.render(canvas, width, height) { inner.onRender(it, width, height, nanoTime) })
						inner.onRender(canvas, width, height, nanoTime)
					if (composited != _composited.value) {
						_composited.value = composited
						composer.invalidate()
					}
					frameStats.record(started, System.nanoTime())
					if (GraphicsSettings.current.performanceOverlay) runCatching { PerformanceOverlay.draw(canvas, width, frameStats, contentScale, description) }
					presented = true
				} finally { presentation.end(presented) }
			}
		}
		installed = wrapper
		layer.renderDelegate = wrapper
	}

	internal fun requestRedraw() {
		runCatching { layer.needRedraw() }
	}

	private val pacing = AdaptiveVsync(layer, redrawer)
	private val multisampled = MultisampledWindow(this)
	private val cacheBudget = CacheBudget()
	private val frameStats = FrameStats()
}

/**
 * Keeps Skia's GPU resource cache at the size the settings ask for ([GpuCacheSize]); [GpuCacheSize.AUTO] puts back
 * the budget the context started with.
 */
internal class CacheBudget {
	private var initial = -1L
	private var applied = -1L

	fun apply(skia: DirectContext) {
		if (initial < 0) initial = runCatching { skia.resourceCacheLimit }.getOrDefault(-1L).also { applied = it }
		if (initial < 0) return
		val megabytes = GraphicsSettings.current.gpuCache.megabytes
		val wanted = if (megabytes == 0) initial else megabytes * 1024L * 1024L
		if (wanted != applied) {
			runCatching { skia.resourceCacheLimit = wanted }
			applied = wanted
		}
	}
}


/**
 * Draws the window through a multisampled target. Skia turns a frame's draws into GPU work on the CPU, and without
 * MSAA it makes the coverage of every antialiased path and shape there too; with MSAA the GPU's own rasterizer does,
 * which halves that part of the frame. Skiko's window surface is single-sampled, so the frame Compose records is
 * replayed into a multisampled texture of the window's size and the window draws that texture: Skiko then replays
 * one image instead of the whole window. Drawing Compose straight into the texture instead of recording first is
 * far slower: its layers are Skiko render nodes, which flush poorly into a GPU canvas.
 *
 * Samples from the antialiasing setting ([GraphicsSettings.samples]; 0 or 1 keeps Skiko's own path), followed
 * from the next frame when it changes.
 */
internal class MultisampledWindow(private val gpu: WindowGpu) {
	private var surface: org.jetbrains.skia.Surface? = null
	private var surfaceSamples = 0

	/** Records [draw] and draws it into [canvas] through the multisampled target; false when it did not. */
	fun render(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): Boolean {
		val samples = effectiveSamples()
		if (samples <= 1 || width <= 0 || height <= 0) {
			surface?.close(); surface = null
			return false
		}
		if (!gpu.isCurrentContext()) return false
		val context = gpu.directContext() ?: return false
		var surface = surface
		if (surface == null || surface.width != width || surface.height != height || surfaceSamples != samples) {
			surface?.close()
			surface = target(context, org.jetbrains.skia.ImageInfo.makeN32Premul(width, height),
				org.jetbrains.skia.SurfaceProps(pixelGeometry = gpu.pixelGeometry ?: org.jetbrains.skia.PixelGeometry.UNKNOWN))
			this.surface = surface
			surfaceSamples = samples
			if (surface == null) return false
		}
		val picture = org.jetbrains.skia.PictureRecorder().use { recorder ->
			draw(recorder.beginRecording(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat())))
			recorder.finishRecordingAsPicture()
		}
		// Last frame's image may still be held by Skiko's last picture: give the surface new pixels rather than copy them.
		surface.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
		surface.canvas.clear(0)
		surface.canvas.drawPicture(picture)
		picture.close()
		surface.makeImageSnapshot().use { canvas.drawImage(it, 0f, 0f) }
		return true
	}

	companion object {
		/**
		 * The fewest samples a target could not be made with. Some drivers (Intel UHD on Windows) report no sample
		 * counts for any format, and Skiko throws rather than returning null then: uncaught on the frame, that ends
		 * Skiko's frame loop and the window stops drawing. Counts from here up are not tried again.
		 */
		@Volatile private var refusedFrom = Int.MAX_VALUE

		/** The window's samples now, also those of the compositor's cached picture ([WindowCompositor]); 0 without MSAA. */
		fun effectiveSamples(): Int = GraphicsSettings.samples.takeIf { it in 2 until refusedFrom } ?: 0

		/** A target with [effectiveSamples], or null when MSAA is off or the GPU cannot make one. */
		fun target(context: org.jetbrains.skia.DirectContext, info: org.jetbrains.skia.ImageInfo, props: org.jetbrains.skia.SurfaceProps): org.jetbrains.skia.Surface? {
			val samples = effectiveSamples()
			if (samples <= 1) return null
			return try {
				org.jetbrains.skia.Surface.makeRenderTarget(context, false, info, samples, org.jetbrains.skia.SurfaceOrigin.TOP_LEFT, props, false)
			} catch (failure: Exception) {
				refusedFrom = minOf(refusedFrom, samples)
				System.err.println("Multisampled window target unavailable at $samples samples, drawing without it: $failure")
				null
			}
		}
	}
}

/**
 * Skiko presents with swap interval 0 and, with vsync on, waits after every frame until the desktop composes
 * (`DwmFlush`): a frame that takes a little longer than a refresh waits for the one after, so 240 Hz falls to
 * 120 FPS. While frames keep missing the refresh, this turns that wait off, so each frame is presented as soon as
 * it is drawn and the desktop shows the latest at every refresh (no tearing: the window is composed); once frames
 * fit a refresh again, the wait comes back and paces them to the display.
 *
 * Only on Windows ([VsyncMode.ADAPTIVE]): Skiko's Linux redrawer fixes its swap interval when it is made, so the
 * switch there would add a timer to the wait instead of removing it. Followed from the next frame.
 */
internal class AdaptiveVsync(private val layer: SkiaLayer, redrawer: Any) {
	private val properties: Any? = runCatching {
		redrawer.javaClass.getDeclaredField("properties").apply { isAccessible = true }.get(redrawer)
	}.getOrNull()
	private val vsync: Field? = properties?.let {
		runCatching { it.javaClass.getDeclaredField("isVsyncEnabled").apply { isAccessible = true } }.getOrNull()
	}
	/** Whether the window started with vsync, which this can then lift and put back. */
	private val available = vsync != null && vsync.getBoolean(properties)
	private var period = 0L
	private var periodCheckedAt = 0L
	private var last = 0L
	private var late = 0
	private var early = 0
	/** Whether frames are presented without waiting for the desktop. */
	@Volatile var free = false
		private set

	fun frame(now: Long) {
		if (!available) return
		if (!GraphicsSettings.adaptiveVsync) {
			if (free) set(false)
			return
		}
		if (now - periodCheckedAt > 1_000_000_000L) {
			periodCheckedAt = now
			val rate = runCatching { layer.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0 }.getOrDefault(0)
			period = if (rate > 0) 1_000_000_000L / rate else 0L
		}
		val interval = now - last
		last = now
		if (period == 0L || interval > period * 4) { late = 0; early = 0; return }
		if (!free) {
			// Waiting made this frame take two refreshes or more.
			if (interval > period * 3 / 2) late++ else late = 0
			if (late >= 3) set(true)
		} else {
			// Drawn faster than the display refreshes: waiting would no longer drop a refresh.
			if (interval < period * 17 / 20) early++ else early = 0
			if (early >= 8) set(false)
		}
	}

	private fun set(free: Boolean) {
		this.free = free
		late = 0
		early = 0
		runCatching { vsync!!.setBoolean(properties, !free) }
	}
}
