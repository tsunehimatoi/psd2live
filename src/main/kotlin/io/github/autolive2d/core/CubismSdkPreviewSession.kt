package io.github.autolive2d.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
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
	)

	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "cubism-sdk-preview").apply { isDaemon = true }
	}
	private val renderQueued = AtomicBoolean(false)
	@Volatile private var generation = 0L
	@Volatile private var closed = false
	private var api: Api? = null
	private var model: Pointer? = null
	private var parameterIds: List<ParameterId> = emptyList()

	fun load(bundle: CubismRuntimeBundle, parameters: List<ParameterId>) {
		if (closed) return
		val targetGeneration = ++generation
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
				// The generated manifest always exposes this group. Cubism owns motion, blink and physics.
				stage = "start generated idle motion"
				native.Live2D_StartMotion(loaded, "Idle", 0, 3)
				postStatus(if (targetGeneration == generation) "ready" else null)
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
		if (closed || request.width <= 0 || request.height <= 0 || !renderQueued.compareAndSet(false, true)) return
		val targetGeneration = generation
		executor.execute {
			try {
				if (closed || targetGeneration != generation) return@execute
				val native = api ?: return@execute
				val handle = model ?: return@execute
				val deltaTime = if (request.animationEnabled) request.deltaTime.coerceIn(0f, 0.1f) else 0f
				native.Live2D_Update(handle, deltaTime)
				applyPointerTracking(native, handle, request.pointerX, request.pointerY)
				for ((id, value) in request.parameterOverrides) {
					native.Live2D_SetParameterValue(handle, id.raw, value)
				}
				if (request.pointerX != 0f || request.pointerY != 0f || request.parameterOverrides.isNotEmpty()) {
					native.Live2D_RefreshModel(handle)
				}

				val pixelCount = Math.multiplyExact(Math.multiplyExact(request.width, request.height), 4)
				val pixelMemory = Memory(pixelCount.toLong())
				val ok = native.Live2D_RenderToRgba(
					handle,
					request.width,
					request.height,
					request.scale,
					request.offsetX,
					request.offsetY,
					pixelMemory,
				)
				if (ok == 0) error(nativeError(native, "Cubism frame rendering failed"))
				val values = copyParameterValues(native, handle)
				val pixels = pixelMemory.getByteArray(0, pixelCount)
				val frame = CubismSdkFrame(rgbaImage(request.width, request.height, pixels), values)
				if (!closed && targetGeneration == generation) SwingUtilities.invokeLater { onFrame(frame) }
			} catch (failure: Throwable) {
				postStatus(failure.message ?: failure.javaClass.simpleName)
			} finally {
				renderQueued.set(false)
			}
		}
	}

	/** Mirrors live2dview's inspector look tracking, intentionally without ParamAngleZ. */
	private fun applyPointerTracking(native: Api, handle: Pointer, x: Float, y: Float) {
		if (x == 0f && y == 0f) return
		fun add(id: String, amount: Float) {
			if (amount == 0f) return
			val current = native.Live2D_GetParameterValue(handle, id)
			native.Live2D_SetParameterValue(handle, id, current + amount)
		}
		add("ParamAngleX", x * 30f)
		add("ParamAngleY", y * 30f)
		add("ParamBodyAngleX", x * 10f)
		add("ParamEyeBallX", x)
		add("ParamEyeBallY", y)
	}

	private fun copyParameterValues(native: Api, handle: Pointer): Map<ParameterId, Float> {
		val count = native.Live2D_GetParameterCount(handle).coerceAtLeast(0)
		if (count == 0) return emptyMap()
		val outputMemory = Memory(count.toLong() * Float.SIZE_BYTES)
		val copied = native.Live2D_CopyParameterValues(handle, outputMemory, count).coerceIn(0, count)
		val output = outputMemory.getFloatArray(0, copied)
		return buildMap(minOf(copied, parameterIds.size)) {
			for (index in 0 until minOf(copied, parameterIds.size)) put(parameterIds[index], output[index])
		}
	}

	private fun rgbaImage(width: Int, height: Int, rgba: ByteArray): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		val argb = IntArray(width * height)
		for (index in argb.indices) {
			val offset = index * 4
			val red = rgba[offset].toInt() and 0xff
			val green = rgba[offset + 1].toInt() and 0xff
			val blue = rgba[offset + 2].toInt() and 0xff
			val alpha = rgba[offset + 3].toInt() and 0xff
			argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
		}
		image.setRGB(0, 0, width, height, argb, 0, width)
		return image
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
		executor.execute {
			val native = api
			if (native != null) {
				model?.let(native::Live2D_DestroyModel)
				model = null
				native.Live2D_Shutdown()
			}
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
