package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId

/**
 * The preview's two runtimes behind one door: the official Cubism runtime on the exported moc3 and PSD2Live's
 * own on the exported `.p2lrt`. [requested] picks one; when Cubism cannot start (no SDK binaries, no context)
 * the p2lrt runtime takes over, so the canvas only falls back to the editor's own painter when neither runs.
 *
 * [onStatus] reports "ready" or why the active runtime cannot draw, [onBackend] which one draws.
 */
class PreviewSessions(
	private val onFrame: (PreviewFrame) -> Unit,
	private val onStatus: (String?) -> Unit,
	private val onBackend: (PreviewBackend) -> Unit = {},
	cubismFactory: ((PreviewFrame) -> Unit, (String?) -> Unit) -> CubismSdkPreviewSession =
		{ frames, status -> CubismSdkPreviewSession(frames, status) },
	private val p2lrtAvailable: () -> Boolean = { P2lrtPreviewSession.runtimeAvailable },
) : AutoCloseable {
	val cubism: CubismSdkPreviewSession = cubismFactory({ if (active == PreviewBackend.CUBISM) onFrame(it) }, ::cubismStatus)
	private var p2lrtSession: P2lrtPreviewSession? = null
	/** The p2lrt session, loaded with the current model the first time anything draws with it. */
	private val p2lrt: P2lrtPreviewSession
		get() = p2lrtSession ?: P2lrtPreviewSession({ if (active == PreviewBackend.P2LRT || it.viewId in p2lrtViews) onFrame(it) }, ::p2lrtStatus)
			.also { session -> p2lrtSession = session; model?.let { session.load(it, presentation) } }
	/** Views Cubism cannot draw (another window's context) that p2lrt draws instead. */
	private val p2lrtViews = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

	/** The runtime the user picked. */
	@Volatile var requested: PreviewBackend = PreviewBackend.CUBISM
		private set
	/** The runtime that draws: [requested], or p2lrt after Cubism failed to start. */
	@Volatile var active: PreviewBackend = PreviewBackend.CUBISM
		private set
	/** Why Cubism gave way to p2lrt, while it does. */
	@Volatile var cubismFailure: String? = null
		private set

	private var model: RigPreviewModel? = null
	private var presentation: io.github.psd2live.util.PresentationToken? = null

	/** How the active runtime's frames reach the window, for the log. */
	val renderPath: String? get() = if (active == PreviewBackend.CUBISM) cubism.renderPath else p2lrtSession?.renderPath


	/** Switches to [backend]; the next [load] or the model already loaded plays on it. */
	fun select(backend: PreviewBackend) {
		requested = backend
		cubismFailure = null
		val target = if (backend == PreviewBackend.P2LRT && !p2lrtAvailable()) PreviewBackend.CUBISM else backend
		activate(target)
	}

	private fun activate(backend: PreviewBackend) {
		if (active == backend) return
		active = backend
		onBackend(backend)
		model?.let(::loadActive)
	}

	fun load(model: RigPreviewModel, presentation: io.github.psd2live.util.PresentationToken? = null) {
		this.presentation = presentation
		this.model = model
		// Both runtimes draw in the window's Skia OpenGL context; without one the canvas paints in software.
		(io.github.psd2live.render.SkiaGpu.status.value as? io.github.psd2live.render.SkiaGpu.Status.Unavailable)?.let {
			onStatus("The preview needs the window drawn with OpenGL: ${it.reason}")
			return
		}
		loadActive(model)
	}

	private fun loadActive(model: RigPreviewModel) {
		when (active) {
			PreviewBackend.CUBISM -> {
				cubism.load(model.runtimeBundle, model.rig.puppet.parameters.map { it.id }, presentation)
				if (p2lrtViews.isNotEmpty()) p2lrt.load(model, presentation)
			}
			PreviewBackend.P2LRT -> p2lrt.load(model, presentation)
		}
	}

	/**
	 * Asks [request]'s view for a frame. Cubism's framework keeps its GL state in the main window's context, so a
	 * view in another window (a floating dock window) is drawn by p2lrt there.
	 */
	fun render(request: PreviewRenderRequest) {
		val cubismHere = request.gpu == null || request.gpu === io.github.psd2live.render.SkiaGpu.primary
		when {
			active == PreviewBackend.CUBISM && cubismHere -> { p2lrtViews.remove(request.viewId); cubism.render(request) }
			active == PreviewBackend.CUBISM && p2lrtAvailable() -> { p2lrtViews += request.viewId; p2lrt.render(request) }
			active == PreviewBackend.CUBISM -> cubism.render(request)
			else -> p2lrt.render(request)
		}
	}

	fun removeView(viewId: String) {
		p2lrtViews.remove(viewId)
		cubism.removeView(viewId)
		p2lrtSession?.removeView(viewId)
	}

	/** Cubism's own motion evaluation, which agent observation samples whichever runtime draws. */
	suspend fun sampleMotionAwait(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, group: String,
								  frames: Int, fps: Int, progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>> =
		cubism.sampleMotionAwait(bundle, parameters, group, frames, fps, progress, cancelled)

	private fun cubismStatus(status: String?) {
		if (active != PreviewBackend.CUBISM) return
		// Initialization can succeed before model creation or drawing reveals an incompatible context/driver.
		if (status != null && status != "ready" && p2lrtAvailable()) {
			cubismFailure = status
			activate(PreviewBackend.P2LRT)
			return
		}
		onStatus(status)
	}

	private fun p2lrtStatus(status: String?) {
		if (active == PreviewBackend.P2LRT || p2lrtViews.isNotEmpty()) onStatus(status)
	}

	override fun close() {
		cubism.close()
		p2lrtSession?.close()
	}
}
