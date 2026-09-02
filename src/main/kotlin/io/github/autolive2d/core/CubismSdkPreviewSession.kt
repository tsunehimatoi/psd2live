package io.github.autolive2d.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** A frame evaluated and rendered by the official Cubism 5-r.5 runtime. */
data class CubismSdkFrame(
	val image: BufferedImage,
	val parameters: Map<ParameterId, Float>,
	val animationEnabled: Boolean = true,
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
	private val latestRender = AtomicReference<QueuedRender?>()
	private val deliveryScheduled = AtomicBoolean(false)
	private val latestDelivery = AtomicReference<QueuedDelivery?>()
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
	private var lastRenderedFrameTimeNanos = 0L
	private var previousFrameWasAnimated = false

	fun load(bundle: CubismRuntimeBundle, parameters: List<ParameterId>) {
		if (closed) return
		val targetGeneration = ++generation
		loadedGeneration = -1L
		latestRender.set(null)
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
				model?.let(native::Live2D_DestroyModel)
				model = null
				stage = "materialize exported runtime family"
				val manifest = materialize(bundle)
				stage = "create Cubism model"
				val loaded = native.Live2D_CreateModel(manifest.toString())
					?: error(nativeError(native, "Cubism Core rejected the exported MOC3 model"))
				model = loaded
				parameterIds = parameters
				loadedGeneration = targetGeneration
				lastRenderedFrameTimeNanos = 0L
				previousFrameWasAnimated = false
				// The generated manifest always exposes this group. Cubism owns motion, blink and physics.
				stage = "start generated idle motion"
				native.Live2D_StartMotion(loaded, "Idle", 0, 3)
				postStatus(if (targetGeneration == generation) "ready" else null)
				scheduleRenderWorker()
			} catch (failure: Throwable) {
				postStatus("$stage: ${failure.message ?: failure.javaClass.simpleName}")
			}
		}
	}

	private fun materialize(bundle: CubismRuntimeBundle): Path {
		val directory = Files.createTempDirectory("autolive2d-preview-model-")
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

	fun render(request: RenderRequest) {
		if (closed || request.width <= 0 || request.height <= 0) return
		latestRender.set(QueuedRender(generation, request))
		scheduleRenderWorker()
	}

	private fun scheduleRenderWorker() {
		if (closed || loadedGeneration != generation || !renderWorkerScheduled.compareAndSet(false, true)) return
		executor.execute(::drainRenderRequests)
	}

	private fun drainRenderRequests() {
		try {
			while (!closed) {
				val queued = latestRender.getAndSet(null) ?: break
				if (queued.generation != generation || queued.generation != loadedGeneration) break
				renderFrame(queued)
		}
		} finally {
			renderWorkerScheduled.set(false)
			if (!closed && latestRender.get() != null && loadedGeneration == generation) scheduleRenderWorker()
		}
	}

	private fun renderFrame(queued: QueuedRender) {
		val request = queued.request
		try {
			if (closed || queued.generation != generation) return
			val native = api ?: return
			val handle = model ?: return
			if (request.animationEnabled) {
				// Feed test values into physics, then re-apply them after motion evaluation so the
				// slider remains authoritative, matching live2dConverter's native preview path.
				applyParameterValues(native, handle, request.parameterOverrides)
				native.Live2D_Update(handle, animationDeltaTime(request))
				applyPointerTracking(native, handle, request.pointerX, request.pointerY)
				applyParameterValues(native, handle, request.parameterOverrides)
			} else {
				// Do not call Update(0): Cubism may still restore the paused motion's old values.
				previousFrameWasAnimated = false
				lastRenderedFrameTimeNanos = request.frameTimeNanos
				applyParameterValues(native, handle, request.parameterOverrides)
			}
			if (request.pointerX != 0f || request.pointerY != 0f || request.parameterOverrides.isNotEmpty()) {
				native.Live2D_RefreshModel(handle)
			}

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
			)
			if (!closed && queued.generation == generation) postFrame(queued.generation, frame)
		} catch (failure: Throwable) {
			postStatus(failure.message ?: failure.javaClass.simpleName)
		}
	}

	private fun animationDeltaTime(request: RenderRequest): Float {
		val requested = request.deltaTime.coerceIn(0f, 0.1f)
		val elapsed = if (previousFrameWasAnimated && lastRenderedFrameTimeNanos > 0L) {
			((request.frameTimeNanos - lastRenderedFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
		} else {
			requested
		}
		lastRenderedFrameTimeNanos = request.frameTimeNanos
		previousFrameWasAnimated = true
		return elapsed
	}

	private fun applyParameterValues(native: Api, handle: Pointer, values: Map<ParameterId, Float>) {
		for ((id, value) in values) native.Live2D_SetParameterValue(handle, id.raw, value)
	}

	/** Mouse look intentionally excludes ParamAngleZ; roll remains owned by motion/breath. */
	private fun applyPointerTracking(native: Api, handle: Pointer, x: Float, y: Float) {
		for (binding in CUBISM_POINTER_TRACKING_BINDINGS) {
			val amount = x * binding.xScale + y * binding.yScale
			if (amount == 0f) continue
			val current = native.Live2D_GetParameterValue(handle, binding.parameterId)
			native.Live2D_SetParameterValue(handle, binding.parameterId, current + amount)
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
		latestDelivery.set(QueuedDelivery(frameGeneration, frame))
		if (deliveryScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(::deliverLatestFrame)
	}

	private fun deliverLatestFrame() {
		try {
			val delivery = latestDelivery.getAndSet(null)
			if (!closed && delivery != null && delivery.generation == generation) onFrame(delivery.frame)
		} finally {
			deliveryScheduled.set(false)
			if (!closed && latestDelivery.get() != null && deliveryScheduled.compareAndSet(false, true)) {
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
		latestRender.set(null)
		latestDelivery.set(null)
		executor.execute {
			val native = api
			if (native != null) {
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
		private val files = listOf(
			"live2d_renderer.dll",
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
			require(System.getProperty("os.name").contains("windows", ignoreCase = true)) {
				"Cubism SDK preview currently requires Windows x86-64"
			}
			val directory = Files.createTempDirectory("autolive2d-cubism-5-r5-")
			for (relative in files) extract(directory, relative)
			System.setProperty("jna.library.path", directory.toString())
			return Native.load(directory.resolve("live2d_renderer.dll").toString(), Api::class.java)
		}

		private fun extract(directory: Path, relative: String) {
			val target = directory.resolve(relative).normalize()
			require(target.startsWith(directory)) { "Invalid Cubism runtime resource path: $relative" }
			Files.createDirectories(target.parent)
			val resource = "/cubism/windows-x86_64/$relative"
			val input = CubismSdkPreviewSession::class.java.getResourceAsStream(resource)
				?: error("Missing Cubism SDK 5-r.5 runtime resource: $relative")
			input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
			target.toFile().deleteOnExit()
		}
	}

}
