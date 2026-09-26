package io.github.psd2live.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** A frame evaluated and rendered by the official Cubism 5-r.5 runtime. */
data class CubismSdkFrame(
	val image: BufferedImage,
	val parameters: Map<ParameterId, Float>,
	val animationEnabled: Boolean = true,
    val viewId: String = "",
    val cameraScale: Float = 1f,
    val cameraOffsetX: Float = 0f,
    val cameraOffsetY: Float = 0f,
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
 * Serializes access to Cubism's hidden OpenGL context on one daemon thread.  The Java SDK release is
 * Android-only, so Windows uses the matching 5-r.5 desktop Core/Framework ABI behind this JVM adapter.
 */
class CubismSdkPreviewSession(
	private val onFrame: (CubismSdkFrame) -> Unit,
	private val onStatus: (String?) -> Unit,
) : AutoCloseable {
	private interface Api : Library {
		fun Live2D_InitOffscreen(): Int
		fun Live2D_Shutdown()
		fun Live2D_CreateModel(modelFilePath: String): Pointer?
		fun Live2D_DestroyModel(handle: Pointer)
		fun Live2D_Update(handle: Pointer, deltaTime: Float)
		fun Live2D_SetDragging(handle: Pointer, x: Float, y: Float)
		fun Live2D_StartMotion(handle: Pointer, group: String, index: Int, priority: Int): Int
		fun Live2D_SetParameterValue(handle: Pointer, parameterId: String, value: Float)
		fun Live2D_GetParameterValue(handle: Pointer, parameterId: String): Float
		fun Live2D_RefreshModel(handle: Pointer)
		fun Live2D_GetParameterCount(handle: Pointer): Int
		fun Live2D_CopyParameterValues(handle: Pointer, output: Pointer, capacity: Int): Int
		fun Live2D_RenderToRgba(
			handle: Pointer,
			width: Int,
			height: Int,
			scale: Float,
			offsetX: Float,
			offsetY: Float,
			output: Pointer,
		): Int
		fun Live2D_GetLastError(): Pointer?
	}

	data class RenderRequest(
		val width: Int,
		val height: Int,
		val scale: Float,
		val offsetX: Float,
		val offsetY: Float,
		val deltaTime: Float,
		val pointerX: Float,
		val pointerY: Float,
		val animationEnabled: Boolean = true,
		val parameterOverrides: Map<ParameterId, Float>,
		val frameTimeNanos: Long = System.nanoTime(),
        val viewId: String = "",
	)

	private data class QueuedRender(
		val generation: Long,
		val request: RenderRequest,
	)

	private data class QueuedDelivery(
		val generation: Long,
		val frame: CubismSdkFrame,
	)

	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "cubism-sdk-preview").apply { isDaemon = true }
	}
	private val renderWorkerScheduled = AtomicBoolean(false)
	private val latestRender = CanvasLatestQueue<QueuedRender>()
	private val deliveryScheduled = AtomicBoolean(false)
	private val latestDelivery = CanvasLatestQueue<QueuedDelivery>()
	@Volatile private var generation = 0L
	@Volatile private var loadedGeneration = -1L
	@Volatile private var closed = false
	private var api: Api? = null
	private var model: Pointer? = null
	private var parameterIds: List<ParameterId> = emptyList()
	private var pixelMemory: Memory? = null
	private var pixelMemoryCapacity = 0L
	private var parameterMemory: Memory? = null
	private var parameterMemoryCapacity = 0
	private class NativeCanvas(val handle: Pointer) {
        var lastRenderedFrameTimeNanos = 0L
        var previousFrameWasAnimated = false
        var lastPoseRequest: RenderRequest? = null
    }
    // All handles stay on the same native GL thread, but own their animation/physics state.
    private val nativeCanvases = mutableMapOf<String, NativeCanvas>()
    private var loadedManifest: Path? = null
    private var hasIdleMotion = false
    private var motionSlots: Map<String, Pair<String, Int>> = emptyMap()

    private fun canvasHandle(native: Api, viewId: String): NativeCanvas = nativeCanvases.getOrPut(viewId) {
        // The model created while loading can serve the first view. Keeping it idle alongside
        // per-view copies used an extra full Cubism model for every preview session.
        val loaded = model
        val handle = if (loaded != null) {
            model = null
            loaded
        } else {
            val manifest = requireNotNull(loadedManifest)
            native.Live2D_CreateModel(manifest.toString())
                ?: error(nativeError(native, "Could not create canvas preview"))
        }
        if (loaded == null && hasIdleMotion) native.Live2D_StartMotion(handle, "Idle", 0, 1)
        NativeCanvas(handle)
    }

	fun load(bundle: CubismRuntimeBundle, parameters: List<ParameterId>) {
		if (closed) return
		val targetGeneration = ++generation
		loadedGeneration = -1L
		latestRender.clear()
		postStatus(null)
		executor.execute {
			if (closed || targetGeneration != generation) return@execute
			var stage = "load native library"
			try {
				val native = api ?: CubismNativeRuntime.load().also {
					stage = "initialize offscreen Cubism runtime"
					require(it.Live2D_InitOffscreen() != 0) { nativeError(it, "Cubism runtime initialization failed") }
					api = it
				}
				stage = "dispose previous Cubism model"
				nativeCanvases.values.forEach { native.Live2D_DestroyModel(it.handle) }
                nativeCanvases.clear()
                loadedManifest = null
                model?.let(native::Live2D_DestroyModel)
				model = null
				stage = "materialize exported runtime family"
				val manifest = materialize(bundle)
                loadedManifest = manifest
				stage = "create Cubism model"
				val loaded = native.Live2D_CreateModel(manifest.toString())
					?: error(nativeError(native, "Cubism Core rejected the exported MOC3 model"))
				model = loaded
				parameterIds = parameters
				loadedGeneration = targetGeneration
				val manifestText = bundle.assets.firstOrNull { it.path.endsWith(".model3.json") }?.bytes?.decodeToString()
				val hasIdle = manifestText?.contains("\"Idle\"") == true
                hasIdleMotion = hasIdle
				motionSlots = manifestText?.let(::cubismMotionSlots).orEmpty()
				if (hasIdle) {
					stage = "start generated idle motion"
					native.Live2D_StartMotion(loaded, "Idle", 0, 1)
				}
				postStatus(if (targetGeneration == generation) "ready" else null)
				scheduleRenderWorker()
			} catch (failure: Throwable) {
				postStatus("$stage: ${failure.message ?: failure.javaClass.simpleName}")
			}
		}
	}

	/**
	 * Starts the motion named [name] on [viewId]'s model. A name is a motion file's, so a loop preset
	 * exported into the idle group is found there; a name no file carries is tried as a group.
	 */
	fun startMotion(name: String, priority: Int = 3, viewId: String = "") {
		if (closed) return
		executor.execute {
			if (closed || loadedGeneration != generation) return@execute
			val native = api ?: return@execute
			val handle = canvasHandle(native, viewId).handle
			val (group, index) = motionSlots[name.lowercase()] ?: (name to 0)
			native.Live2D_StartMotion(handle, group, index, priority)
		}
	}

    /** Evaluate a disposable exported model on this session's native thread; the live model is untouched. */
    fun sampleMotion(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, group: String,
                     frames: Int, fps: Int): java.util.concurrent.CompletableFuture<List<Map<ParameterId, Float>>> {
        require(frames in 1..1201 && fps in 15..120)
        val result = java.util.concurrent.CompletableFuture<List<Map<ParameterId, Float>>>()
        if (closed) { result.completeExceptionally(IllegalStateException("Cubism session closed")); return result }
        executor.execute {
            try {
                check(!closed) { "Cubism session closed" }
                val native = api ?: CubismNativeRuntime.load().also {
                    require(it.Live2D_InitOffscreen() != 0) { nativeError(it, "Cubism runtime initialization failed") }
                    api = it
                }
                val handle = native.Live2D_CreateModel(materialize(bundle).toString())
                    ?: error(nativeError(native, "Cubism rejected the observation model"))
                try {
                    require(native.Live2D_StartMotion(handle, group, 0, 3) != 0) { "Observation motion could not start" }
                    val samples = ArrayList<Map<ParameterId, Float>>(frames)
                    repeat(frames) { index ->
                        native.Live2D_Update(handle, if (index == 0) 0f else 1f / fps)
                        samples += parameters.associateWith { native.Live2D_GetParameterValue(handle, it.raw) }
                    }
                    result.complete(samples)
                } finally { native.Live2D_DestroyModel(handle) }
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return result
    }

	private fun materialize(bundle: CubismRuntimeBundle): Path {
		val directory = Files.createTempDirectory("psd2live-preview-model-")
		for (asset in bundle.assets) {
			val target = directory.resolve(asset.path.replace('/', java.io.File.separatorChar)).normalize()
			require(target.startsWith(directory)) { "Invalid Cubism model asset path: ${asset.path}" }
			Files.createDirectories(target.parent)
			Files.write(target, asset.bytes)
			target.toFile().deleteOnExit()
		}
		directory.toFile().deleteOnExit()
		return directory.resolve(bundle.manifestPath.replace('/', java.io.File.separatorChar)).toAbsolutePath().normalize()
	}

    fun removeView(viewId: String) {
        latestRender.remove(viewId)
        latestDelivery.remove(viewId)
        if (!closed) executor.execute {
            nativeCanvases.remove(viewId)?.let { api?.Live2D_DestroyModel(it.handle) }
        }
    }

	fun render(request: RenderRequest) {
		if (closed || request.width <= 0 || request.height <= 0) return
		latestRender.put(request.viewId, QueuedRender(generation, request))
		scheduleRenderWorker()
	}

	private fun scheduleRenderWorker() {
		if (closed || loadedGeneration != generation || !renderWorkerScheduled.compareAndSet(false, true)) return
		executor.execute(::drainRenderRequests)
	}

	private fun drainRenderRequests() {
		try {
			while (!closed) {
				val queued = latestRender.poll() ?: break
				if (queued.generation != generation || queued.generation != loadedGeneration) break
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
			if (closed || queued.generation != generation) return
			val native = api ?: return
			val canvas = canvasHandle(native, request.viewId)
            val handle = canvas.handle
            val reusePose = request.animationEnabled && canvas.lastPoseRequest?.let { previous ->
                previous.animationEnabled && previous.frameTimeNanos == request.frameTimeNanos &&
                    previous.pointerX == request.pointerX && previous.pointerY == request.pointerY &&
                    previous.parameterOverrides == request.parameterOverrides
            } == true
			val needsRefresh: Boolean
            if (reusePose) {
                needsRefresh = false
            } else if (request.animationEnabled) {
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
				applyParameterValues(native, handle, request.parameterOverrides)
				// Paused previews cannot advance Cubism's smoothed drag manager. Apply the static
				// look offsets directly so mouse tracking remains useful while inspecting a pose.
				applyPausedPointerTracking(
					native,
					handle,
					request.pointerX,
					request.pointerY,
					request.parameterOverrides,
				)
				needsRefresh = true
			}
			if (needsRefresh) native.Live2D_RefreshModel(handle)
            if (!reusePose) canvas.lastPoseRequest = request

			val pixelCount = Math.multiplyExact(Math.multiplyExact(request.width, request.height), 4)
			val output = ensurePixelMemory(pixelCount.toLong())
			val ok = native.Live2D_RenderToRgba(
				handle,
				request.width,
				request.height,
				request.scale,
				request.offsetX,
				request.offsetY,
				output,
			)
			if (ok == 0) error(nativeError(native, "Cubism frame rendering failed"))
			val frame = CubismSdkFrame(
				image = rgbaImage(request.width, request.height, output),
				parameters = copyParameterValues(native, handle),
				animationEnabled = request.animationEnabled,
                viewId = request.viewId,
                cameraScale = request.scale,
                cameraOffsetX = request.offsetX,
                cameraOffsetY = request.offsetY,
			)
			if (!closed && queued.generation == generation) postFrame(queued.generation, frame)
		} catch (failure: Throwable) {
			postStatus(failure.message ?: failure.javaClass.simpleName)
		}
	}

	private fun animationDeltaTime(canvas: NativeCanvas, request: RenderRequest): Float {
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

	private fun applyParameterValues(native: Api, handle: Pointer, values: Map<ParameterId, Float>) {
		for ((id, value) in values) native.Live2D_SetParameterValue(handle, id.raw, value)
	}

	private fun applyAnimatedVerticalTracking(native: Api, handle: Pointer, y: Float) {
		if (y == 0f) return
		for (binding in CUBISM_POINTER_TRACKING_BINDINGS) {
			val amount = y * binding.yScale
			if (amount == 0f) continue
			val current = native.Live2D_GetParameterValue(handle, binding.parameterId)
			native.Live2D_SetParameterValue(handle, binding.parameterId, current + amount)
		}
	}

	/** Mouse look intentionally excludes ParamAngleZ; roll remains owned by motion/breath. */
	private fun applyPausedPointerTracking(
		native: Api,
		handle: Pointer,
		x: Float,
		y: Float,
		baseValues: Map<ParameterId, Float>,
	) {
		for (binding in CUBISM_POINTER_TRACKING_BINDINGS) {
			val amount = x * binding.xScale + y * binding.yScale
			val base = baseValues[ParameterId(binding.parameterId)] ?: 0f
			native.Live2D_SetParameterValue(handle, binding.parameterId, base + amount)
		}
	}

	private fun ensurePixelMemory(requiredBytes: Long): Memory {
		val existing = pixelMemory
		if (existing != null && pixelMemoryCapacity >= requiredBytes) return existing
		existing?.close()
		return Memory(requiredBytes).also {
			pixelMemory = it
			pixelMemoryCapacity = requiredBytes
		}
	}

	private fun copyParameterValues(native: Api, handle: Pointer): Map<ParameterId, Float> {
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

	private fun rgbaImage(width: Int, height: Int, rgba: Memory): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val argb = (image.raster.dataBuffer as DataBufferInt).data
		val rgbaInts = rgba.getByteBuffer(0, argb.size.toLong() * Int.SIZE_BYTES)
			.order(java.nio.ByteOrder.LITTLE_ENDIAN)
			.asIntBuffer()
		rgbaInts.get(argb)
		for (index in argb.indices) {
			val abgr = argb[index]
			argb[index] = (abgr and 0xff00ff00.toInt()) or
				((abgr and 0x000000ff) shl 16) or
				((abgr and 0x00ff0000) ushr 16)
		}
		return image
	}

	private fun postFrame(frameGeneration: Long, frame: CubismSdkFrame) {
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

	private fun nativeError(native: Api, fallback: String): String =
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
		executor.execute {
			val native = api
			if (native != null) {
				nativeCanvases.values.forEach { native.Live2D_DestroyModel(it.handle) }
                nativeCanvases.clear()
                loadedManifest = null
                model?.let(native::Live2D_DestroyModel)
				model = null
				native.Live2D_Shutdown()
			}
			pixelMemory?.close()
			pixelMemory = null
			parameterMemory?.close()
			parameterMemory = null
		}
		executor.shutdown()
	}

	private object CubismNativeRuntime {
		private fun isWindows(): Boolean =
			System.getProperty("os.name").contains("windows", ignoreCase = true)

		private fun isLinux(): Boolean =
			System.getProperty("os.name").contains("linux", ignoreCase = true)

		private fun requireAmd64(): String {
			val arch = System.getProperty("os.arch").orEmpty().lowercase()
			if (arch != "amd64" && arch != "x86_64") {
				throw UnsupportedOperationException(
					"Cubism SDK preview requires x86_64/amd64. " +
						"Unsupported os.arch=$arch (os.name=${System.getProperty("os.name")})"
				)
			}
			return arch
		}

		private fun getPlatformDir(): String {
			requireAmd64()
			return when {
				isWindows() -> "windows-x86_64"
				isLinux() -> "linux-x86_64"
				else -> throw UnsupportedOperationException(
					"Cubism SDK preview currently requires Windows or Linux x86-64. " +
						"Platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
				)
			}
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

		fun load(): Api {
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
			return Native.load(directory.resolve(libraryName).toString(), Api::class.java)
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
