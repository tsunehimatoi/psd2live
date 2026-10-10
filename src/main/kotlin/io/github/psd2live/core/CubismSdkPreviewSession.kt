package io.github.psd2live.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The native preview bridge (`native/live2d_renderer`); a test can stand in for it. */
internal interface CubismNativeApi : Library {
	/** Initializes the framework on the context current on the calling thread; only with [CubismNativeBinding.drawCurrent]. */
	fun Live2D_Init(): Int
	fun Live2D_InitOffscreen(): Int
	fun Live2D_Shutdown()
	fun Live2D_CreateModel(modelFilePath: String): Pointer?
	/** Only on libraries with [CubismNativeBinding.memoryModels]; [bundle] is [encodeCubismPreviewBundle]'s. */
	fun Live2D_CreateModelFromMemory(bundle: ByteArray, size: Long): Pointer?
	/** Only on libraries with [CubismNativeBinding.textureReplace]; width and height 0 pass PNG bytes. */
	fun Live2D_ReplaceTexture(handle: Pointer, index: Int, data: ByteArray, size: Long, width: Int, height: Int): Int
	fun Live2D_DestroyModel(handle: Pointer)
	fun Live2D_Update(handle: Pointer, deltaTime: Float)
	/** Draws into the framebuffer bound on the current context; only with [CubismNativeBinding.drawCurrent]. */
	fun Live2D_Draw(handle: Pointer, width: Int, height: Int, scale: Float, offsetX: Float, offsetY: Float)
	fun Live2D_SetDragging(handle: Pointer, x: Float, y: Float)
	fun Live2D_StartMotion(handle: Pointer, group: String, index: Int, priority: Int): Int
	fun Live2D_SetParameterValue(handle: Pointer, parameterId: String, value: Float)
	fun Live2D_GetParameterValue(handle: Pointer, parameterId: String): Float
	fun Live2D_RefreshModel(handle: Pointer)
	fun Live2D_GetParameterCount(handle: Pointer): Int
	fun Live2D_CopyParameterValues(handle: Pointer, output: Pointer, capacity: Int): Int
	fun Live2D_GetLastError(): Pointer?
}

/** A loaded bridge and the optional entry points it exports (`size_t` is 64-bit on the supported x86-64 targets). */
internal class CubismNativeBinding(
	val api: CubismNativeApi,
	val memoryModels: Boolean,
	val textureReplace: Boolean,
	/** `Live2D_Init` and `Live2D_Draw`: the library can draw on a context the caller owns. */
	val drawCurrent: Boolean = false,
	/**
	 * `Live2D_UsesCallerContext`: after `Live2D_Init` in-memory models use the caller's context too. Older bridges
	 * create them on their private hidden context, so with the caller's context they load models from files.
	 */
	val callerContextModels: Boolean = false,
)

internal data class CubismPointerTrackingBinding(
	val parameterId: String,
	val xScale: Float = 0f,
	val yScale: Float = 0f,
)

internal val CUBISM_POINTER_TRACKING_BINDINGS = listOf(
	CubismPointerTrackingBinding("ParamAngleX", xScale = 30f),
	CubismPointerTrackingBinding("ParamAngleY", yScale = 30f),
	CubismPointerTrackingBinding("ParamBodyAngleX", xScale = 10f),
	CubismPointerTrackingBinding("ParamEyeBallX", xScale = 1f),
	CubismPointerTrackingBinding("ParamEyeBallY", yScale = 1f),
)

/** Cubism maps a non-zero native Y target into ParamAngleZ through its XY cross term. */
internal const val CUBISM_NATIVE_POINTER_Y = 0f

/**
 * Runs the Cubism 5-r.5 runtime. The Java SDK release is Android-only, so Windows uses the matching 5-r.5
 * desktop Core/Framework ABI behind this JVM adapter.
 *
 * Every native call runs as a task of [gpu]: in the app on the main window's render thread with its Skia OpenGL
 * context current ([io.github.psd2live.render.PrimaryGpuExecutor]). The framework initializes on that context
 * (`Live2D_Init`) and each frame draws straight into the view's texture in it (`Live2D_Draw`), which the canvas
 * draws in the same frame; no pixel leaves the GPU. Tests run the tasks on a thread of their own.
 */
class CubismSdkPreviewSession internal constructor(
	private val onFrame: (PreviewFrame) -> Unit,
	private val onStatus: (String?) -> Unit,
	/** Where observation sampling stages its temporary model files; the system temp directory by default. */
	private val stagingRoot: java.nio.file.Path?,
	private val nativeLoader: () -> CubismNativeBinding,
	private val gpu: io.github.psd2live.render.GpuExecutor = io.github.psd2live.render.ThreadGpuExecutor("cubism-sdk-preview"),
) : AutoCloseable {
	constructor(
		onFrame: (PreviewFrame) -> Unit,
		onStatus: (String?) -> Unit,
		stagingRoot: java.nio.file.Path? = null,
	) : this(onFrame, onStatus, stagingRoot, CubismNativeRuntime::load, io.github.psd2live.render.PrimaryGpuExecutor())

	private data class QueuedRender(
		val generation: Long,
		val request: PreviewRenderRequest,
	)

	private data class QueuedDelivery(
		val generation: Long,
		val frame: PreviewFrame,
	)

	private data class PendingLoad(
		val generation: Long,
		val bundle: CubismRuntimeBundle,
		val parameters: List<ParameterId>,
	)

	/** Where live models come from: the encoded bundle in memory, or a materialized manifest on old libraries. */
	private sealed interface ModelSource {
		class InMemory(val bytes: ByteArray) : ModelSource
		class OnDisk(val manifest: Path) : ModelSource
	}

	private val renderWorkerScheduled = AtomicBoolean(false)
	private val latestRender = CanvasLatestQueue<QueuedRender>()
	private val deliveryScheduled = AtomicBoolean(false)
	private val latestDelivery = CanvasLatestQueue<QueuedDelivery>()
	/** Reload requests coalesce: the native thread only ever applies the newest bundle. */
	private val pendingLoad = AtomicReference<PendingLoad?>(null)
	private val loadScheduled = AtomicBoolean(false)
	@Volatile private var generation = 0L
	@Volatile private var loadedGeneration = -1L
	@Volatile private var closed = false
	private var binding: CubismNativeBinding? = null
	/** The context's resources while a task runs; null in tests. */
	private var resources: io.github.psd2live.render.GpuResources? = null
	/** How the session renders, for the log; null until the library is loaded. */
	@Volatile var renderPath: String? = null
		private set
	private var model: Pointer? = null
	private var parameterIds: List<ParameterId> = emptyList()
	private var parameterMemory: Memory? = null
	private var parameterMemoryCapacity = 0
	private class NativeCanvas(var handle: Pointer) {
        var lastRenderedFrameTimeNanos = 0L
        var previousFrameWasAnimated = false
        var lastPoseRequest: PreviewRenderRequest? = null
        /** The last request this view rendered, rendered again after a live update so the view shows it at once. */
        var lastRequest: PreviewRenderRequest? = null
    }
    // All handles stay on the same native GL thread, but own their animation/physics state.
    private val nativeCanvases = mutableMapOf<String, NativeCanvas>()
    private var modelSource: ModelSource? = null
    private var loadedFingerprint: CubismBundleFingerprint? = null
    /** How the newest load reached the native models, for tests and diagnostics. */
    @Volatile internal var lastAppliedReload: CubismPreviewReload? = null
        private set
    private var hasIdleMotion = false
    private var motionSlots: Map<String, Pair<String, Int>> = emptyMap()

    private fun canvasHandle(native: CubismNativeApi, viewId: String): NativeCanvas = nativeCanvases.getOrPut(viewId) {
        // The model created while loading can serve the first view. Keeping it idle alongside
        // per-view copies used an extra full Cubism model for every preview session.
        val loaded = model
        val handle = if (loaded != null) {
            model = null
            loaded
        } else {
            createModel(native, requireNotNull(modelSource), "Could not create canvas preview")
        }
        if (loaded == null && hasIdleMotion) native.Live2D_StartMotion(handle, "Idle", 0, 1)
        NativeCanvas(handle)
    }

    private fun createModel(native: CubismNativeApi, source: ModelSource, failure: String): Pointer = when (source) {
        is ModelSource.InMemory -> native.Live2D_CreateModelFromMemory(source.bytes, source.bytes.size.toLong())
        is ModelSource.OnDisk -> native.Live2D_CreateModel(source.manifest.toString())
    } ?: error(nativeError(native, failure))

	/**
	 * Follows [bundle] on the native thread. Requests coalesce to the newest one. With the in-memory entry
	 * points the live models stay: texture-only changes upload just the changed pages, other changes rebuild
	 * each model from memory and restore its pose. Older libraries reload through a materialized directory.
	 */
	fun load(bundle: CubismRuntimeBundle, parameters: List<ParameterId>) {
		if (closed) return
		val targetGeneration = ++generation
		loadedGeneration = -1L
		pendingLoad.set(PendingLoad(targetGeneration, bundle, parameters))
		scheduleLoad()
	}

	private fun scheduleLoad() {
		if (closed || !loadScheduled.compareAndSet(false, true)) return
		if (!onNativeThread(::drainLoads)) loadScheduled.set(false)
	}

	private fun drainLoads() {
		try {
			while (!closed) {
				val next = pendingLoad.getAndSet(null) ?: break
				if (next.generation == generation) applyLoad(next)
			}
		} finally {
			loadScheduled.set(false)
			if (!closed && pendingLoad.get() != null) scheduleLoad()
		}
	}

	private fun applyLoad(next: PendingLoad) {
		var stage = "load native library"
		try {
			val current = binding ?: initializeBinding { stage = it }
			val native = current.api
			stage = "compare runtime bundle"
			val fingerprint = CubismBundleFingerprint.of(next.bundle, loadedFingerprint)
			val live = model != null || nativeCanvases.isNotEmpty()
			val plan = planCubismPreviewReload(
				loadedFingerprint.takeIf { live && modelSource is ModelSource.InMemory },
				fingerprint, current.memoryModels, current.textureReplace,
			)
			val manifestText = next.bundle.assets.firstOrNull { it.path == next.bundle.manifestPath }?.bytes?.decodeToString()
			var applied = plan
			when (plan) {
				CubismPreviewReload.Unchanged -> Unit
				is CubismPreviewReload.Textures -> {
					stage = "replace texture pages"
					if (!replaceTextures(native, next.bundle, fingerprint, plan.pages)) {
						stage = "rebuild Cubism model in place"
						recreateInPlace(native, next, manifestText)
						applied = CubismPreviewReload.Recreate
					}
				}
				CubismPreviewReload.Recreate -> {
					stage = "rebuild Cubism model in place"
					recreateInPlace(native, next, manifestText)
				}
				CubismPreviewReload.Full -> {
					postStatus(null)
					stage = "dispose previous Cubism model"
					disposeModels(native)
					stage = if (current.memoryModels) "encode runtime bundle" else "materialize exported runtime family"
					val source = if (current.memoryModels) ModelSource.InMemory(encodeCubismPreviewBundle(next.bundle))
						else ModelSource.OnDisk(materialize(next.bundle))
					modelSource = source
					stage = "create Cubism model"
					val loaded = createModel(native, source, "Cubism Core rejected the exported MOC3 model")
					model = loaded
					adoptManifest(manifestText)
					if (hasIdleMotion) {
						stage = "start generated idle motion"
						native.Live2D_StartMotion(loaded, "Idle", 0, 1)
					}
				}
			}
			loadedFingerprint = fingerprint
			lastAppliedReload = applied
			parameterIds = next.parameters
			loadedGeneration = next.generation
			if (plan != CubismPreviewReload.Full) requeueLastRequests()
			postStatus(if (next.generation == generation) "ready" else null)
			scheduleRenderWorker()
		} catch (failure: Throwable) {
			binding?.api?.let { native -> runCatching { disposeModels(native) } }
			postStatus("$stage: ${failure.message ?: failure.javaClass.simpleName}")
		}
	}

	private fun adoptManifest(manifestText: String?) {
		hasIdleMotion = manifestText?.contains("\"Idle\"") == true
		motionSlots = manifestText?.let(::cubismMotionSlots).orEmpty()
	}

	/** Uploads the changed pages into every live model; false when the library refused one of them. */
	private fun replaceTextures(native: CubismNativeApi, bundle: CubismRuntimeBundle, fingerprint: CubismBundleFingerprint, pages: List<Int>): Boolean {
		val handles = listOfNotNull(model) + nativeCanvases.values.map { it.handle }
		val bytes = bundle.assets.associate { it.path to it.bytes }
		for (page in pages) {
			val png = bytes[fingerprint.texturePaths[page]] ?: return false
			for (handle in handles) {
				if (native.Live2D_ReplaceTexture(handle, page, png, png.size.toLong(), 0, 0) == 0) return false
			}
		}
		// Views opened later are created from this bundle, so they see the new pages too.
		modelSource = ModelSource.InMemory(encodeCubismPreviewBundle(bundle))
		return true
	}

	/**
	 * Rebuilds every live model from the new bundle without dropping its view: each keeps its clock and is
	 * set to the pose its old model showed, so the preview does not jump before the next request arrives.
	 */
	private fun recreateInPlace(native: CubismNativeApi, next: PendingLoad, manifestText: String?) {
		val source = ModelSource.InMemory(encodeCubismPreviewBundle(next.bundle))
		val known = next.parameters.toSet()
		val poses = nativeCanvases.mapValues { (_, canvas) -> copyParameterValues(native, canvas.handle) }
		val created = ArrayList<Pointer>()
		try {
			val failure = "Cubism Core rejected the exported MOC3 model"
			val replacements = nativeCanvases.mapValues { createModel(native, source, failure).also(created::add) }
			val idle = if (model != null || nativeCanvases.isEmpty()) createModel(native, source, failure).also(created::add) else null
			created.clear()
			model?.let(native::Live2D_DestroyModel)
			model = idle
			modelSource = source
			adoptManifest(manifestText)
			idle?.let { if (hasIdleMotion) native.Live2D_StartMotion(it, "Idle", 0, 1) }
			for ((viewId, canvas) in nativeCanvases) {
				native.Live2D_DestroyModel(canvas.handle)
				val handle = replacements.getValue(viewId)
				canvas.handle = handle
				canvas.lastPoseRequest = null
				if (hasIdleMotion) native.Live2D_StartMotion(handle, "Idle", 0, 1)
				val pose = poses.getValue(viewId)
				for ((id, value) in pose) if (id in known) native.Live2D_SetParameterValue(handle, id.raw, value)
				if (pose.isNotEmpty()) native.Live2D_RefreshModel(handle)
			}
		} finally {
			created.forEach(native::Live2D_DestroyModel)
		}
	}

	/** After a live update the views render their last request again with the new model. */
	private fun requeueLastRequests() {
		for ((viewId, canvas) in nativeCanvases) {
			val last = canvas.lastRequest ?: continue
			// A native clock advances on its own frames; rendering one again would step it twice.
			if (!last.nativeClock) latestRender.putIfAbsent(viewId, QueuedRender(generation, last))
		}
	}

	private fun disposeModels(native: CubismNativeApi) {
		nativeCanvases.values.forEach { native.Live2D_DestroyModel(it.handle) }
		nativeCanvases.clear()
		model?.let(native::Live2D_DestroyModel)
		model = null
		modelSource = null
		loadedFingerprint = null
	}

	/** Waits until queued native work, including work it queues in turn, has run; for tests. */
	internal fun awaitIdle() {
		repeat(4) {
			val done = java.util.concurrent.CompletableFuture<Unit>()
			if (!onNativeThread { done.complete(Unit) }) return
			done.get(10, TimeUnit.SECONDS)
		}
	}

	/**
	 * Starts the motion named [name] on [viewId]'s model. A name is a motion file's, so a loop preset
	 * exported into the idle group is found there; a name no file carries is tried as a group.
	 */
	fun startMotion(name: String, priority: Int = 3, viewId: String = "") {
		if (closed) return
		onNativeThread {
			if (closed || loadedGeneration != generation) return@onNativeThread
			val native = binding?.api ?: return@onNativeThread
			val canvas = canvasHandle(native, viewId)
			canvas.lastPoseRequest = null
			val handle = canvas.handle
			val (group, index) = motionSlots[name.lowercase()] ?: (name to 0)
			native.Live2D_StartMotion(handle, group, index, priority)
		}
	}

    /** Evaluate a disposable exported model on this session's native thread; the live model is untouched. */
    fun sampleMotion(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, group: String,
                     frames: Int, fps: Int, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): java.util.concurrent.CompletableFuture<List<Map<ParameterId, Float>>> {
        require(frames in 1..1201 && fps in 15..120)
        val result = java.util.concurrent.CompletableFuture<List<Map<ParameterId, Float>>>()
        if (closed) { result.completeExceptionally(IllegalStateException("Cubism session closed")); return result }
        val queued = onNativeThread {
            try {
                fun checkpoint() {
                    if (closed || result.isCancelled || cancelled()) throw java.util.concurrent.CancellationException("Motion sampling cancelled")
                }
                checkpoint(); progress(0f)
                val native = (binding ?: initializeBinding {}).api
                checkpoint()
                val directory = stagingRoot?.let { Files.createTempDirectory(it, "psd2live-motion-sample-") }
                    ?: Files.createTempDirectory("psd2live-motion-sample-")
                val samples = ArrayList<Map<ParameterId, Float>>(frames)
                try {
                    val manifest = materialize(bundle, directory, cleanupOnExit = false)
                    checkpoint()
                    val handle = native.Live2D_CreateModel(manifest.toString())
                        ?: error(nativeError(native, "Cubism rejected the observation model"))
                    try {
                        checkpoint()
                        require(native.Live2D_StartMotion(handle, group, 0, 3) != 0) { "Observation motion could not start" }
                        repeat(frames) { index ->
                            checkpoint()
                            native.Live2D_Update(handle, if (index == 0) 0f else 1f / fps)
                            samples += parameters.associateWith { native.Live2D_GetParameterValue(handle, it.raw) }
                            progress((index + 1).toFloat() / frames)
                        }
                        checkpoint()
                    } finally { native.Live2D_DestroyModel(handle) }
                } finally {
                    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                }
                checkpoint(); result.complete(samples)
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        if (!queued) result.completeExceptionally(IllegalStateException("Cubism session closed"))
        return result
    }

    /**
     * Coroutine cancellation cancels the queued future and stops the native loop at its next frame. A runtime that
     * never answers fails with why: a timeout is a cancellation to coroutines, which would end the job as if the
     * caller had cancelled it.
     */
    suspend fun sampleMotionAwait(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, group: String,
                                 frames: Int, fps: Int, progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>> =
        withTimeoutOrNull(SAMPLE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val future = sampleMotion(bundle, parameters, group, frames, fps, progress, cancelled)
                continuation.invokeOnCancellation { future.cancel(false) }
                future.whenComplete { samples, failure ->
                    if (failure == null) continuation.resume(samples) else continuation.resumeWithException(failure)
                }
            }
        } ?: throw IllegalStateException("The Cubism runtime did not answer within ${SAMPLE_TIMEOUT_MILLIS / 1000} s. It runs on the " +
            "canvas's OpenGL thread, which a display without GPU rendering may never start; motion_sample with view_render_model " +
            "gives the same poses from the editor's evaluator.")

	private fun materialize(bundle: CubismRuntimeBundle, directory: Path = Files.createTempDirectory("psd2live-preview-model-"), cleanupOnExit: Boolean = true): Path {
		for (asset in bundle.assets) {
			val target = directory.resolve(asset.path.replace('/', java.io.File.separatorChar)).normalize()
			require(target.startsWith(directory)) { "Invalid Cubism model asset path: ${asset.path}" }
			Files.createDirectories(target.parent)
			Files.write(target, asset.bytes)
			if (cleanupOnExit) target.toFile().deleteOnExit()
		}
		if (cleanupOnExit) directory.toFile().deleteOnExit()
		return directory.resolve(bundle.manifestPath.replace('/', java.io.File.separatorChar)).toAbsolutePath().normalize()
	}

    fun removeView(viewId: String) {
        latestRender.remove(viewId)
        latestDelivery.remove(viewId)
        if (!closed) onNativeThread {
            nativeCanvases.remove(viewId)?.let { binding?.api?.Live2D_DestroyModel(it.handle) }
            resources?.release(viewId)
        }
    }

	fun render(request: PreviewRenderRequest) {
		if (closed || request.width <= 0 || request.height <= 0) return
		latestRender.put(request.viewId, QueuedRender(generation, request))
		scheduleRenderWorker()
	}

	private fun scheduleRenderWorker() {
		if (closed || loadedGeneration != generation || !renderWorkerScheduled.compareAndSet(false, true)) return
		if (!onNativeThread(::drainRenderRequests)) renderWorkerScheduled.set(false)
	}

	private fun drainRenderRequests() {
		try {
			while (!closed) {
				val queued = latestRender.poll() ?: break
				// A request queued before a reload still describes its view; it renders with the new model.
				if (loadedGeneration != generation) {
					latestRender.putIfAbsent(queued.request.viewId, queued)
					break
				}
				renderFrame(queued)
		}
		} finally {
			renderWorkerScheduled.set(false)
			if (!closed && !latestRender.isEmpty() && loadedGeneration == generation) scheduleRenderWorker()
		}
	}

	private fun renderFrame(queued: QueuedRender) {
		val request = queued.request
		try {
			val frameGeneration = generation
			if (closed || loadedGeneration != frameGeneration) return
			val native = binding?.api ?: return
			val canvas = canvasHandle(native, request.viewId)
			canvas.lastRequest = request
            val handle = canvas.handle
            val reusePose = canvas.lastPoseRequest?.let { previous ->
                previous.animationEnabled == request.animationEnabled &&
					previous.nativeClock == request.nativeClock &&
					(!request.nativeClock || previous.frameTimeNanos == request.frameTimeNanos) &&
                    previous.pointerX == request.pointerX && previous.pointerY == request.pointerY &&
                    previous.parameterOverrides == request.parameterOverrides &&
                    previous.pointerTrackingEnabled == request.pointerTrackingEnabled &&
                    previous.lockedParameters == request.lockedParameters &&
                    previous.parameterDefinitions == request.parameterDefinitions
            } == true
			var needsRefresh: Boolean
            if (reusePose) {
                needsRefresh = false
            } else if (request.nativeClock) {
				// X runs through Cubism's look updater before physics so hair receives the head
				// movement. Y is deliberately zero here because Cubism also maps it to AngleZ.
				native.Live2D_SetDragging(handle, request.pointerX, CUBISM_NATIVE_POINTER_Y)
				native.Live2D_Update(handle, animationDeltaTime(canvas, request))
				// Apply vertical head/eye tracking after the scheduler without touching AngleZ.
				applyAnimatedVerticalTracking(native, handle, request.pointerY)
				// Locked inspector values remain authoritative over motion/physics outputs.
				applyParameterValues(native, handle, request.parameterOverrides)
				needsRefresh = request.pointerY != 0f || request.parameterOverrides.isNotEmpty()
			} else {
				// Do not call Update(0): Cubism may still restore the paused motion's old values.
				canvas.previousFrameWasAnimated = false
				canvas.lastRenderedFrameTimeNanos = request.frameTimeNanos
				// A slider changes one value in a full pose map. The native model retains the other
				// values, so avoid a JNA call and Cubism ID lookup for every unchanged parameter.
				val previous = canvas.lastPoseRequest?.takeUnless { it.nativeClock }?.parameterOverrides
				for ((id, value) in request.parameterOverrides) {
					if (previous == null || previous[id] != value) {
						native.Live2D_SetParameterValue(handle, id.raw, value)
					}
				}
				// Paused previews cannot advance Cubism's smoothed drag manager. Apply the static
				// look offsets directly so mouse tracking remains useful while inspecting a pose.
				val tracked = pointerPreviewPose(request.parameterOverrides, request.pointerX, request.pointerY,
					request.parameterDefinitions, request.pointerTrackingEnabled, request.lockedParameters)
				for (tracking in CUBISM_POINTER_TRACKING_BINDINGS) {
					val id = ParameterId(tracking.parameterId)
					tracked[id]?.let { native.Live2D_SetParameterValue(handle, id.raw, it) }
				}
				needsRefresh = true
			}
			// Native motion/physics and look updates can add after the SDK's setters clamp.
			// Bound the final pose before geometry evaluation, using this model's edited ranges.
			if (!reusePose && request.parameterDefinitions.isNotEmpty()) {
				val pose = copyParameterValues(native, handle)
				val bounded = boundedPreviewPose(pose, request.parameterDefinitions)
				if (bounded !== pose) {
					for ((id, value) in bounded) if (value != pose[id]) native.Live2D_SetParameterValue(handle, id.raw, value)
					needsRefresh = true
				}
			}
			if (needsRefresh) native.Live2D_RefreshModel(handle)
            if (!reusePose) canvas.lastPoseRequest = request

			val target = resources
			// Cubism's renderer feeds vertices from client memory, which a core profile does not have.
			check(target?.coreProfile != true) { "Cubism needs a compatibility OpenGL context; the window's is a core profile" }
			if (target != null) {
				// Cubism draws GL's way up; the target turns it upright into the texture the canvas draws.
				target.render(request.viewId, request.width, request.height, bottomUp = true) {
					native.Live2D_Draw(handle, request.width, request.height, request.scale, request.offsetX, request.offsetY)
				} ?: error("Cubism frame rendering failed")
			} else {
				native.Live2D_Draw(handle, request.width, request.height, request.scale, request.offsetX, request.offsetY)
			}
			val frame = PreviewFrame(
				width = request.width,
				height = request.height,
				parameters = copyParameterValues(native, handle),
				animationEnabled = request.animationEnabled,
                viewId = request.viewId,
                cameraScale = request.scale,
                cameraOffsetX = request.offsetX,
                cameraOffsetY = request.offsetY,
			)
			if (!closed && frameGeneration == generation) postFrame(frameGeneration, frame)
		} catch (failure: Throwable) {
			postStatus(failure.message ?: failure.javaClass.simpleName)
		}
	}

	private fun animationDeltaTime(canvas: NativeCanvas, request: PreviewRenderRequest): Float {
		val requested = request.deltaTime.coerceIn(0f, 0.1f)
		val sinceLast = request.frameTimeNanos - canvas.lastRenderedFrameTimeNanos
		// Frame stamps come from two clocks: the pump's vsync time, and System.nanoTime while paused. A stamp
		// behind the last one must not pin the clock at zero: a motion that never reaches its end keeps its
		// priority, and Cubism then turns every later motion away.
		val elapsed = if (canvas.previousFrameWasAnimated && canvas.lastRenderedFrameTimeNanos > 0L && sinceLast > 0L) {
			(sinceLast / 1_000_000_000f).coerceAtMost(0.1f)
		} else {
			requested
		}
		canvas.lastRenderedFrameTimeNanos = request.frameTimeNanos
		canvas.previousFrameWasAnimated = true
		return elapsed
	}

	private fun applyParameterValues(native: CubismNativeApi, handle: Pointer, values: Map<ParameterId, Float>) {
		for ((id, value) in values) native.Live2D_SetParameterValue(handle, id.raw, value)
	}

	private fun applyAnimatedVerticalTracking(native: CubismNativeApi, handle: Pointer, y: Float) {
		if (y == 0f) return
		for (binding in CUBISM_POINTER_TRACKING_BINDINGS) {
			val amount = y * binding.yScale
			if (amount == 0f) continue
			val current = native.Live2D_GetParameterValue(handle, binding.parameterId)
			native.Live2D_SetParameterValue(handle, binding.parameterId, current + amount)
		}
	}

	private fun copyParameterValues(native: CubismNativeApi, handle: Pointer): Map<ParameterId, Float> {
		val count = native.Live2D_GetParameterCount(handle).coerceAtLeast(0)
		if (count == 0) return emptyMap()
		val outputMemory = ensureParameterMemory(count)
		val copied = native.Live2D_CopyParameterValues(handle, outputMemory, count).coerceIn(0, count)
		val output = outputMemory.getFloatArray(0, copied)
		return buildMap(minOf(copied, parameterIds.size)) {
			for (index in 0 until minOf(copied, parameterIds.size)) put(parameterIds[index], output[index])
		}
	}

	private fun ensureParameterMemory(requiredFloats: Int): Memory {
		val existing = parameterMemory
		if (existing != null && parameterMemoryCapacity >= requiredFloats) return existing
		existing?.close()
		return Memory(requiredFloats.toLong() * Float.SIZE_BYTES).also {
			parameterMemory = it
			parameterMemoryCapacity = requiredFloats
		}
	}

	/**
	 * Loads the library and initializes it on the context the tasks run with, or on its own hidden one when they
	 * have none (tests).
	 */
	private fun initializeBinding(stage: (String) -> Unit): CubismNativeBinding {
		stage("load native library")
		val loaded = nativeLoader()
		val native = loaded.api
		if (gpu.providesContext) {
			require(loaded.drawCurrent) { "This Cubism bridge cannot draw on the window's context; rebuild native/live2d_renderer" }
			stage("initialize Cubism runtime")
			require(native.Live2D_Init() != 0) { nativeError(native, "Cubism runtime initialization failed") }
			renderPath = if (loaded.callerContextModels) "texture" else "texture · models from files (rebuild native/live2d_renderer for in-memory reloads)"
			if (!loaded.callerContextModels) {
				// The old bridge's in-memory entry point would move the model onto its hidden context.
				return CubismNativeBinding(native, memoryModels = false, textureReplace = loaded.textureReplace, drawCurrent = true)
					.also { binding = it }
			}
		} else {
			stage("initialize offscreen Cubism runtime")
			require(native.Live2D_InitOffscreen() != 0) { nativeError(native, "Cubism runtime initialization failed") }
			renderPath = "offscreen"
		}
		binding = loaded
		return loaded
	}

	private fun postFrame(frameGeneration: Long, frame: PreviewFrame) {
		latestDelivery.put(frame.viewId, QueuedDelivery(frameGeneration, frame))
		if (deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliverLatestFrame)
	}

	private fun deliverLatestFrame() {
		try {
			val delivery = latestDelivery.poll()
			if (!closed && delivery != null && delivery.generation == generation) onFrame(delivery.frame)
		} finally {
			deliveryScheduled.set(false)
			if (!closed && !latestDelivery.isEmpty() && deliveryScheduled.compareAndSet(false, true)) {
				SwingUtilities.invokeLater(::deliverLatestFrame)
			}
		}
	}

	private fun nativeError(native: CubismNativeApi, fallback: String): String =
		native.Live2D_GetLastError()?.getString(0, Charsets.UTF_8.name()).orEmpty().ifBlank { fallback }

	private fun postStatus(status: String?) {
		if (!closed) SwingUtilities.invokeLater { onStatus(status) }
	}

	override fun close() {
		if (closed) return
		closed = true
		generation++
		latestRender.clear()
		latestDelivery.clear()
		pendingLoad.set(null)
		onNativeThread {
			val native = binding?.api
			if (native != null) {
				disposeModels(native)
				native.Live2D_Shutdown()
			}
			parameterMemory?.close()
			parameterMemory = null
		}
		gpu.close()
	}

	/** Queues [task] as a GPU task; false once the executor is closed. */
	private fun onNativeThread(task: () -> Unit): Boolean =
		gpu.execute { context ->
			resources = context
			try { task() } finally { resources = null }
		}

	private object CubismNativeRuntime {
		private fun isWindows(): Boolean =
			System.getProperty("os.name").contains("windows", ignoreCase = true)

		private fun isLinux(): Boolean =
			System.getProperty("os.name").contains("linux", ignoreCase = true)

		private fun isArm64(): Boolean = System.getProperty("os.arch").orEmpty().lowercase().let { it == "aarch64" || it == "arm64" }

		private fun isAmd64(): Boolean = System.getProperty("os.arch").orEmpty().lowercase().let { it == "amd64" || it == "x86_64" }

		/** The Cubism Core ships for Windows x86-64 and Linux x86-64, and for Linux arm64 as an experimental library. */
		private fun getPlatformDir(): String = when {
			isWindows() && isAmd64() -> "windows-x86_64"
			isLinux() && isAmd64() -> "linux-x86_64"
			isLinux() && isArm64() -> "linux-arm64"
			else -> throw UnsupportedOperationException(
				"Cubism SDK preview requires Windows x86-64 or Linux x86-64 / arm64. " +
					"Platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
			)
		}

		private fun getLibraryName(): String = when {
			isWindows() -> "live2d_renderer.dll"
			isLinux() -> "liblive2d_renderer.so"
			else -> throw UnsupportedOperationException("Unsupported platform")
		}

		private fun runtimeFiles(): List<String> = listOf(
			getLibraryName(),
			"FrameworkShaders/FragShaderSrc.frag",
			"FrameworkShaders/FragShaderSrcAlphaBlend.frag",
			"FrameworkShaders/FragShaderSrcBlend.frag",
			"FrameworkShaders/FragShaderSrcColorBlend.frag",
			"FrameworkShaders/FragShaderSrcCopy.frag",
			"FrameworkShaders/FragShaderSrcMask.frag",
			"FrameworkShaders/FragShaderSrcMaskBlend.frag",
			"FrameworkShaders/FragShaderSrcMaskInverted.frag",
			"FrameworkShaders/FragShaderSrcMaskInvertedBlend.frag",
			"FrameworkShaders/FragShaderSrcMaskInvertedPremultipliedAlpha.frag",
			"FrameworkShaders/FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag",
			"FrameworkShaders/FragShaderSrcMaskPremultipliedAlpha.frag",
			"FrameworkShaders/FragShaderSrcMaskPremultipliedAlphaBlend.frag",
			"FrameworkShaders/FragShaderSrcPremultipliedAlpha.frag",
			"FrameworkShaders/FragShaderSrcPremultipliedAlphaBlend.frag",
			"FrameworkShaders/FragShaderSrcSetupMask.frag",
			"FrameworkShaders/VertShaderSrc.vert",
			"FrameworkShaders/VertShaderSrcBlend.vert",
			"FrameworkShaders/VertShaderSrcCopy.vert",
			"FrameworkShaders/VertShaderSrcMasked.vert",
			"FrameworkShaders/VertShaderSrcMaskedBlend.vert",
			"FrameworkShaders/VertShaderSrcSetupMask.vert",
		)

		fun load(): CubismNativeBinding {
			val platformDir = getPlatformDir()
			val libraryName = getLibraryName()

			val customPathStr = System.getProperty("psd2live.cubism.path")
				?.ifBlank { null }
				?: System.getenv("CUBISM_SDK_PATH")?.ifBlank { null }
				?: System.getenv("LIVE2D_SDK_PATH")?.ifBlank { null }
			val customDir = customPathStr?.let { Path.of(it) }

			val directory = Files.createTempDirectory("psd2live-cubism-5-r5-")
			for (relative in runtimeFiles()) extract(directory, relative, customDir, platformDir)
			System.setProperty("jna.library.path", directory.toString())
			val path = directory.resolve(libraryName).toString()
			val api = Native.load(path, CubismNativeApi::class.java)
			// Libraries built before the in-memory entry points keep the file-based reload.
			val library = NativeLibrary.getInstance(path)
			fun exports(symbol: String) = runCatching { library.getFunction(symbol) }.isSuccess
			return CubismNativeBinding(
				api = api,
				memoryModels = exports("Live2D_CreateModelFromMemory"),
				textureReplace = exports("Live2D_ReplaceTexture"),
				drawCurrent = exports("Live2D_Init") && exports("Live2D_Draw"),
				callerContextModels = exports("Live2D_UsesCallerContext"),
			)
		}

		private fun extract(directory: Path, relative: String, customDir: Path? = null, platformDir: String) {
			val target = directory.resolve(relative).normalize()
			require(target.startsWith(directory)) { "Invalid Cubism runtime resource path: $relative" }
			Files.createDirectories(target.parent)

			// 1. External custom path configured via property or environment variable
			if (customDir != null) {
				val candidate = customDir.resolve(relative).normalize()
				if (Files.isRegularFile(candidate)) {
					Files.copy(candidate, target, StandardCopyOption.REPLACE_EXISTING)
					target.toFile().deleteOnExit()
					return
				}
			}

			// 2. Local filesystem paths relative to working directory
			val localCandidates = listOf(
				Path.of("cubism", platformDir, relative),
				Path.of("src", "main", "resources", "cubism", platformDir, relative),
			)
			for (localCandidate in localCandidates) {
				if (Files.isRegularFile(localCandidate)) {
					Files.copy(localCandidate, target, StandardCopyOption.REPLACE_EXISTING)
					target.toFile().deleteOnExit()
					return
				}
			}

			// 3. Classpath resource
			val resource = "/cubism/$platformDir/$relative"
			val input = CubismSdkPreviewSession::class.java.getResourceAsStream(resource)
				?: error(
					"Missing Cubism SDK 5-r.5 runtime resource: $relative. " +
						"Official Live2D SDK binaries are not distributed with PSD2Live. " +
						"Please configure CUBISM_SDK_PATH or place binaries in src/main/resources/cubism/$platformDir/. " +
						"See docs/en/guide/CUBISM_SDK_SETUP.md (also available in zh/ja) for setup instructions."
				)
			input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
			target.toFile().deleteOnExit()
		}
	}

}

/**
 * Where each motion of a model3 manifest sits, keyed by the lower-case name its file carries
 * (`model.idleCute.motion3.json` is `idlecute`): its group and its index within it.
 */
internal fun cubismMotionSlots(manifest: String): Map<String, Pair<String, Int>> {
	val motions = runCatching {
		Json.parseToJsonElement(manifest).jsonObject["FileReferences"]?.jsonObject?.get("Motions")?.jsonObject
	}.getOrNull() ?: return emptyMap()
	return buildMap {
		for ((group, entries) in motions) {
			(entries as? JsonArray)?.forEachIndexed { index, entry ->
				val file = (entry as? JsonObject)?.get("File")?.jsonPrimitive?.content ?: return@forEachIndexed
				val name = file.substringAfterLast('/').removeSuffix(".motion3.json").substringAfterLast('.')
				putIfAbsent(name.lowercase(), group to index)
			}
		}
	}
}

/** How long a motion sample may wait for the native runtime before it fails. */
private const val SAMPLE_TIMEOUT_MILLIS = 45_000L
