package io.github.psd2live.tools

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.encodeCubismPreviewBundle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test

/**
 * Renders the preview bundle's model in poses with the official Cubism runtime (the bundled live2d_renderer, offscreen)
 * beside the editor's own renderer, so a pose the moc3 carries differently from the rig shows at a glance: the whole
 * figure, and the face and the mouth close up.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=tml ./gradlew test --tests '*CubismParityTool'
 * Writes build/tools/cubism-parity/<sample>-<view>.png: per pose, PSD2Live on the left, Cubism on the right. Windows only.
 */
class CubismParityTool {
	interface Renderer : Library {
		fun Live2D_InitOffscreen(): Int
		fun Live2D_CreateModelFromMemory(bundle: ByteArray, size: Long): Pointer?
		fun Live2D_SetParameterValue(handle: Pointer, parameterId: String, value: Float)
		fun Live2D_RefreshModel(handle: Pointer)
		fun Live2D_RenderToRgba(handle: Pointer, width: Int, height: Int, scale: Float, offsetX: Float, offsetY: Float, out: Pointer): Int
		fun Live2D_GetLastError(): Pointer?
	}

	@Test fun poses() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = PSD2LivePipeline().buildPreview(sample.path)
		// PSD2LIVE_PARITY_RECIPE=legacy: the rig a project saved before rig modules generates.
		val legacy = setting("PSD2LIVE_PARITY_RECIPE", "modern") == "legacy"
		val preview = if (!legacy) built
			else PSD2LivePipeline().buildPreview(built.analysis, built.config.copy(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy))
		val dll = File("src/main/resources/cubism/windows-x86_64/live2d_renderer.dll").absoluteFile
		val native = Native.load(dll.path, Renderer::class.java)
		fun failure(what: String) = native.Live2D_GetLastError()?.getString(0) ?: what
		check(native.Live2D_InitOffscreen() != 0) { failure("init") }
		val bytes = encodeCubismPreviewBundle(preview.runtimeBundle)
		val handle = native.Live2D_CreateModelFromMemory(bytes, bytes.size.toLong()) ?: error(failure("model"))
		val defaults = preview.rig.puppet.parameters.associate { it.id.raw to it.default }
		val ours = io.github.psd2live.tools.Renderer(preview, VIEW)
		val canvas = ours.canvas
		val bounds = preview.rig.sourceBoundsByDrawableId
		fun around(id: String, scale: Float): Bounds? = bounds[id]?.let { b ->
			val side = maxOf(b.width, b.height) * scale
			Bounds(b.centerX - side / 2, b.centerY - side / 2, b.centerX + side / 2, b.centerY + side / 2)
		}
		val views = listOfNotNull("figure" to canvas, around("ArtMeshFace", 1.3f)?.let { "face" to it },
			around("ArtMeshMouth", 1.6f)?.let { "mouth" to it })
		val poses = listOf("rest" to emptyMap(), "AngleX 30" to mapOf("ParamAngleX" to 30f), "AngleY 30" to mapOf("ParamAngleY" to 30f),
			"AngleX -30 Y -30" to mapOf("ParamAngleX" to -30f, "ParamAngleY" to -30f), "mouth open" to mapOf("ParamMouthOpenY" to 1f),
			"mouth half, smile" to mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.5f), "A turned" to mapOf("ParamA" to 1f, "ParamAngleX" to 20f),
			"O" to mapOf("ParamO" to 1f), "E" to mapOf("ParamE" to 1f), "open turned" to mapOf("ParamMouthOpenY" to 1f, "ParamAngleX" to 30f),
			"quarter, Y" to mapOf("ParamMouthOpenY" to 0.25f, "ParamAngleY" to 20f), "pout" to mapOf("ParamMouthForm" to -1f, "ParamMouthOpenY" to 0.3f),
			"eyes shut" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f), "glare" to mapOf("ParamBrowLY" to -1f, "ParamBrowRY" to -1f, "ParamEyeBallX" to 1f),
			// The face capture's mouth (VBridger's names), where the rig has it.
			"funnel" to mapOf("ParamMouthFunnel" to 1f), "lips pressed" to mapOf("ParamMouthPressLipOpen" to -1f),
			"teeth bared" to mapOf("ParamMouthPressLipOpen" to 1f), "smile, teeth bared" to mapOf("ParamMouthPressLipOpen" to 1f, "ParamMouthForm" to 1f),
			"jaw, lips shut" to mapOf("ParamJawOpen" to 1f), "shrug" to mapOf("ParamMouthShrug" to 1f), "pucker" to mapOf("ParamMouthPuckerWiden" to -1f),
			"mouth X" to mapOf("ParamMouthX" to 1f))
		// Cubism draws the whole canvas; a close view is cut from a large frame.
		val width = 4096
		val height = (width * canvas.height / canvas.width).toInt()
		val buffer = Memory(width.toLong() * height * 4)
		val frames = views.associate { it.first to ArrayList<Pair<String, BufferedImage>>() }
		for ((name, pose) in poses) {
			for ((id, default) in defaults) native.Live2D_SetParameterValue(handle, id, pose[id] ?: default)
			native.Live2D_RefreshModel(handle)
			check(native.Live2D_RenderToRgba(handle, width, height, 1f, 0f, 0f, buffer) != 0) { failure("render") }
			val raw = buffer.getByteArray(0, width * height * 4)
			val ints = IntArray(width * height) { i ->
				val r = raw[i * 4].toInt() and 255; val g = raw[i * 4 + 1].toInt() and 255; val b = raw[i * 4 + 2].toInt() and 255; val a = raw[i * 4 + 3].toInt() and 255
				(a shl 24) or (r shl 16) or (g shl 8) or b
			}
			val cubism = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, ints, 0, width) }
			for ((view, rect) in views) {
				val k = width / canvas.width
				val x = (rect.left * k).toInt().coerceIn(0, width - 1); val y = (rect.top * k).toInt().coerceIn(0, height - 1)
				val cut = cubism.getSubimage(x, y, (rect.width * k).toInt().coerceIn(1, width - x), (rect.height * k).toInt().coerceIn(1, height - y))
				val mine = ours.render(pose, rect)
				val scaled = BufferedImage(mine.width, mine.height, BufferedImage.TYPE_INT_ARGB).apply {
					createGraphics().run {
						setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
						color = java.awt.Color(48, 48, 48); fillRect(0, 0, mine.width, mine.height)
						drawImage(cut, 0, 0, mine.width, mine.height, null); dispose()
					}
				}
				frames.getValue(view) += "$name · PSD2Live" to mine
				frames.getValue(view) += "$name · Cubism" to scaled
			}
		}
		for ((view, list) in frames) sheet(list, File(output("cubism-parity"), "${sample.name}${if (legacy) "-legacy" else ""}-$view.png"), columns = 6)
	}

	private companion object {
		const val VIEW = 384
	}
}
