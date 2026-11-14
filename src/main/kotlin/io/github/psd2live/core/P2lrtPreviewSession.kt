package io.github.psd2live.core

import io.github.psd2live.format.eval.P2lRuntime
import io.github.psd2live.render.GpuResources
import io.github.psd2live.render.P2lrtGlRenderer
import io.github.psd2live.render.WindowGpu
import io.github.psd2live.targets.runtime.P2lrt
import org.umamo.runtime.model.ParameterId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Previews the rig as the exported `.p2lrt` plays: PSD2Live's own runtime (`runtime/`, through [P2lRuntime])
 * evaluates the pose and [P2lrtGlRenderer] draws it, as a task of the view's window ([WindowGpu.post]) with its
 * Skia context current, straight into the view's texture there. The rig compiles on a thread of its own.
 *
 * Like the Cubism session, the workspace clock supplies the whole pose and the runtime only evaluates it. In
 * advanced mode each view's rig also steps its live simulation and skins along arcs (`p2l_set_advanced`); the
 * workspace's physics is kept as supplied, and the runtime's idle behaviors stay off. Skinning is fitted on the
 * compiler thread and shared by all views of the loaded model.
 */
class P2lrtPreviewSession(
	private val onFrame: (PreviewFrame) -> Unit,
	private val onStatus: (String?) -> Unit,
) : AutoCloseable {
	private class Loaded(val generation: Long, val model: P2lRuntime.Model, val pages: List<java.awt.image.BufferedImage>, val presentation: io.github.psd2live.util.PresentationToken?)
	private class PendingModel(val generation: Long, val model: RigPreviewModel, val presentation: io.github.psd2live.util.PresentationToken?)
	private class Delivery(val generation: Long, val frame: PreviewFrame)

	private class View(val rig: P2lRuntime.Rig, val gpu: WindowGpu, val generation: Long) {
		/** Serializes this rig's native use with a close on another window's thread, if the view moves between windows. */
		val native = Any()
		var advanced = -1
		/** The frame time of the last step, for the simulation's own clock. */
		var lastFrameNanos = 0L
		/** The rig's default values, reset per frame without allocating. */
		var values = FloatArray(0)
		/** `parameterIds` by raw id, built once per rig instead of on every frame. */
		var parameterIndex: Map<String, Int>? = null
	}

	/** One context's renderer and the rig generation its meshes and pages are uploaded for. */
	private class ContextRenderer(val renderer: P2lrtGlRenderer) : AutoCloseable {
		var generation = -1L
		/** The generation whose upload failed (a page past the GPU's limit, or out of memory): not retried every frame. */
		var failedGeneration = -1L
		override fun close() = renderer.close()
	}

	private val compiler = Executors.newSingleThreadExecutor { Thread(it, "p2lrt-preview-compile").apply { isDaemon = true } }
	private val latestRender = CanvasLatestQueue<PreviewRenderRequest>()
	private val renderScheduled = ConcurrentHashMap<WindowGpu, CanvasRenderWake>()
	private val latestDelivery = CanvasLatestQueue<Delivery>()
	private val deliveryScheduled = AtomicBoolean(false)
	private val pendingModel = AtomicReference<PendingModel?>(null)
	private val compileScheduled = AtomicBoolean(false)
	private val lock = Any()
	private val generation = AtomicLong()
	@Volatile private var loaded: Loaded? = null
	@Volatile private var closed = false
	private var runtime: P2lRuntime? = null
	private val views = HashMap<String, View>()
	private val lastRequests = HashMap<String, PreviewRenderRequest>()
	private val serviceKey = Any()

	/** How frames reach the window, for the log. */
	val renderPath: String get() = "texture"

	/** Compiles [model] to `.p2lrt` in the background and plays it; requests coalesce to the newest model. */
	fun load(model: RigPreviewModel, presentation: io.github.psd2live.util.PresentationToken? = null) {
		if (closed) return
		synchronized(lock) {
			if (closed) return
			pendingModel.set(PendingModel(generation.incrementAndGet(), model, presentation))
		}
		scheduleCompile()
	}

	private fun scheduleCompile() {
		if (closed || !compileScheduled.compareAndSet(false, true)) return
		try {
			compiler.execute(::drainCompiles)
		} catch (_: RejectedExecutionException) {
			compileScheduled.set(false)
		}
	}

	private fun drainCompiles() {
		try {
			while (!closed) {
				val pending = pendingModel.getAndSet(null) ?: break
				val model = pending.model
				val target = pending.generation
				val result = runCatching {
					val rt = synchronized(lock) {
						runtime ?: (P2lRuntime.load() ?: error("The PSD2Live runtime library is not available")).also { runtime = it }
					}
					try {
						compile(rt, model, target, pending.presentation, simulations = true)
					} catch (failure: Throwable) {
						// A simulation that cannot be prepared must not take the whole preview down: play the rig without it.
						try { compile(rt, model, target, pending.presentation, simulations = false) }
						catch (fallback: Throwable) { fallback.addSuppressed(failure); throw fallback }
					}
				}
				result.onFailure {
					// Keep the last model drawing: a failed build of a newer document must not blank the preview.
					postStatus("compile p2lrt: ${it.message ?: it.javaClass.simpleName}", target)
				}
				val next = result.getOrNull() ?: continue
				val accepted = synchronized(lock) {
					if (closed || target != generation.get()) false
					else { loaded?.model?.close(); loaded = next; true }
				}
				if (!accepted) { next.model.close(); continue }
				postStatus("ready", target)
				// Every view renders its last request again with the new rig.
				val gpus = synchronized(lock) { lastRequests.values.mapNotNull { it.gpu }.toSet() }
				gpus.forEach(::scheduleRender)
			}
		} finally {
			compileScheduled.set(false)
			if (!closed && pendingModel.get() != null) scheduleCompile()
		}
	}

	private fun compile(runtime: P2lRuntime, model: RigPreviewModel, target: Long, presentation: io.github.psd2live.util.PresentationToken?, simulations: Boolean): Loaded {
		val bytes = P2lrt.write(RigIrCompiler.compile(model, simulations = simulations))
		val shared = runtime.loadModel(bytes)
		try {
			shared.createRig().use { rig ->
				// Fitting is cached on the shared native model, including for future views and mode switches.
				rig.setAdvanced(rig.advancedAvailable)
			}
			return Loaded(target, shared, model.atlas.pages.map { it.image }, presentation)
		} catch (failure: Throwable) {
			shared.close()
			throw failure
		}
	}

	fun render(request: PreviewRenderRequest) {
		if (closed || request.width <= 0 || request.height <= 0) return
		val gpu = request.gpu ?: return postStatus("The preview needs a window drawn with OpenGL")
		synchronized(lock) {
			if (closed) return
			lastRequests[request.viewId] = request
			latestRender.put(request.viewId, request)
		}
		scheduleRender(gpu)
	}

	private fun scheduleRender(gpu: WindowGpu) {
		val wake = renderScheduled.getOrPut(gpu) { CanvasRenderWake() }
		if (closed || loaded == null || !wake.request()) return
		postRender(gpu, wake)
	}

	private fun postRender(gpu: WindowGpu, wake: CanvasRenderWake) {
		gpu.post { resources ->
			wake.begin()
			try { drainRenders(gpu, resources) }
			finally { if (wake.finish() && !closed) postRender(gpu, wake) }
		}
	}

	private fun drainRenders(gpu: WindowGpu, resources: GpuResources) {
		val current = loaded ?: return
		val others = ArrayList<PreviewRenderRequest>()
		val rendered = HashSet<String>()
		while (!closed) {
			val request = latestRender.poll() ?: break
			if (request.gpu !== gpu) { others += request; continue }
			renderFrame(current, resources, gpu, request)
			rendered += request.viewId
		}
		others.forEach { latestRender.putIfAbsent(it.viewId, it) }
		// A new rig redraws this window's views that asked for nothing since.
		val stale = synchronized(lock) {
			lastRequests.values.filter {
				it.gpu === gpu && it.viewId !in rendered &&
					(views[it.viewId]?.generation != current.generation || views[it.viewId]?.gpu !== gpu)
			}
		}
		stale.forEach { renderFrame(current, resources, gpu, it) }
		others.mapNotNull { it.gpu }.toSet().forEach(::scheduleRender)
	}

	private fun renderFrame(current: Loaded, resources: GpuResources, gpu: WindowGpu, request: PreviewRenderRequest) {
		// The global lock only guards the maps and the loaded model: evaluation and drawing below run without it, so a
		// slow frame cannot hold up the UI's next request or a finished compile.
		val view = synchronized(lock) {
			if (closed || loaded !== current || lastRequests[request.viewId]?.gpu !== gpu) return
			var view = views[request.viewId]
			if (view == null || view.generation != current.generation || view.gpu !== gpu) {
				// A frame on another window's thread may still be drawing the rig this view replaces.
				view?.let { old -> synchronized(old.native) { old.rig.close() } }
				view = View(current.model.createRig().also { it.behaviors(0) }, gpu, current.generation)
				views[request.viewId] = view
			}
			view
		}
		try {
			synchronized(view.native) { draw(view, current, resources, request) }
		} catch (failure: Throwable) {
			postStatus(failure.message ?: failure.javaClass.simpleName)
		}
	}

	private fun draw(view: View, current: Loaded, resources: GpuResources, request: PreviewRenderRequest) {
		val context = resources.service(serviceKey) { ContextRenderer(P2lrtGlRenderer()) }
		if (context.generation != current.generation) {
			if (context.failedGeneration == current.generation) return
			context.failedGeneration = current.generation
			context.renderer.setRig(view.rig, current.pages)
			context.generation = current.generation
			context.failedGeneration = -1L
		}
		val rig = view.rig
		// The physics switch decides whether the simulation runs; without it the baked swing shows, skinned.
		val features = when {
			!request.advanced -> 0
			request.physics -> rig.advancedAvailable
			else -> rig.advancedAvailable and (SIM or COLLISION).inv()
		}
		if (view.advanced != features) {
			rig.setAdvanced(features)
			view.advanced = features
			view.lastFrameNanos = 0L
		}
		// The pose is the whole pose: parameters it leaves out sit at their defaults.
		val defaults = rig.defaults
		val values = if (view.values.size != defaults.size) defaults.copyOf().also { view.values = it }
			else view.values.also { System.arraycopy(defaults, 0, it, 0, defaults.size) }
		val index = view.parameterIndex ?: rig.parameterIds.withIndex().associate { it.value to it.index }.also { view.parameterIndex = it }
		for ((id, value) in request.parameterOverrides) index[id.raw]?.let { values[it] = value }
		rig.values = values
		if (features and SIM != 0) {
			// Stepped by the frame clock, not playback: a paused preview with physics on keeps swinging.
			val elapsed = if (view.lastFrameNanos == 0L) 0f else ((request.frameTimeNanos - view.lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
			view.lastFrameNanos = request.frameTimeNanos
			rig.updateSimulation(elapsed)
		} else {
			rig.evaluate()
		}

		// Cubism's camera (the canvas height fits the shorter side times scale, offsets in clip units) in view pixels.
		val (canvasWidth, canvasHeight) = rig.canvas
		val pixelsPerCanvas = request.scale * minOf(request.width, request.height) / canvasHeight.coerceAtLeast(1f)
		val centerX = request.width * 0.5f * (1f + request.offsetX)
		val centerY = request.height * 0.5f * (1f - request.offsetY)
		val quality = io.github.psd2live.render.TargetQuality.of(io.github.psd2live.render.GraphicsSettings.current.canvasAntialiasing)
		val presentation = current.presentation?.frame(request.viewId, if (io.github.psd2live.util.PresentationMeasurements.onCompleted != null)
                io.github.psd2live.util.PresentationCamera(request.width, request.height, request.scale, request.offsetX, request.offsetY) else null)
		resources.render(request.viewId, request.width, request.height, bottomUp = false, quality, presentation) { target ->
			// Supersampled, the whole frame is in the larger target's pixels.
			val s = target.scale
			context.renderer.draw(rig, target.framebuffer, request.width * s, request.height * s, pixelsPerCanvas * s,
				(centerX - canvasWidth * 0.5f * pixelsPerCanvas) * s, (centerY - canvasHeight * 0.5f * pixelsPerCanvas) * s)
		} ?: return
		val currentValues = rig.current
		val parameters = HashMap<ParameterId, Float>(currentValues.size)
		for ((i, id) in rig.parameterIds.withIndex()) parameters[ParameterId(id)] = currentValues[i]
		postFrame(PreviewFrame(
			width = request.width, height = request.height, parameters = parameters, animationEnabled = request.animationEnabled,
			viewId = request.viewId, cameraScale = request.scale, cameraOffsetX = request.offsetX, cameraOffsetY = request.offsetY,
			backend = PreviewBackend.P2LRT, advanced = features != 0, advancedAvailable = rig.advancedAvailable,
			presentation = presentation,
		), current.generation)
	}

	fun removeView(viewId: String) {
		latestRender.remove(viewId)
		latestDelivery.remove(viewId)
		val view = synchronized(lock) { lastRequests.remove(viewId); views.remove(viewId) } ?: return
		view.gpu.post { resources -> synchronized(lock) { view.rig.close() }; resources.release(viewId) }
	}

	private fun postFrame(frame: PreviewFrame, generation: Long) {
		latestDelivery.put(frame.viewId, Delivery(generation, frame))
		if (deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliver)
	}

	private fun deliver() {
		try {
			val delivery = latestDelivery.poll()
			if (!closed && delivery != null && delivery.generation == loaded?.generation) onFrame(delivery.frame)
		} finally {
			deliveryScheduled.set(false)
			if (!closed && !latestDelivery.isEmpty() && deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliver)
		}
	}

	private fun postStatus(status: String?, target: Long? = null) {
		if (!closed) SwingUtilities.invokeLater {
			if (!closed && (target == null || target == generation.get())) onStatus(status)
		}
	}

	override fun close() {
		if (closed) return
		closed = true
		generation.incrementAndGet()
		latestRender.clear()
		latestDelivery.clear()
		pendingModel.set(null)
		compiler.shutdownNow()
		val byGpu = synchronized(lock) {
			lastRequests.clear()
			loaded?.model?.close()
			loaded = null
			views.entries.groupBy({ it.value.gpu }, { it.key to it.value }).also { views.clear() }
		}
		for ((gpu, entries) in byGpu) gpu.post { resources ->
			entries.forEach { (viewId, view) -> runCatching { view.rig.close() }; resources.release(viewId) }
			resources.closeService(serviceKey)
		}
	}

	companion object {
		/** `P2L_SIM` and `P2L_COLLISION`: the advanced features the physics switch turns on and off. */
		private const val SIM = 4
		private const val COLLISION = 8

		/** Whether the runtime library can be found; the p2lrt preview needs it. */
		val runtimeAvailable: Boolean by lazy { P2lRuntime.locate() != null }
	}
}
