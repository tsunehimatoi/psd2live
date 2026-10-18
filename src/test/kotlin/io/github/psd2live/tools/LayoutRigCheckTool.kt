package io.github.psd2live.tools

import io.github.psd2live.application.LayoutManifest
import io.github.psd2live.application.WorkspaceViewRenderer
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * A Layout manifest or a PSD (PSD2LIVE_LAYOUT, comma-separated for several) generated as the editor would, without hand
 * edits, and posed for a live-use check: the face (blink, gaze, mouth, brows), the head turning (AngleX × AngleY, Z)
 * and the body (BodyAngleX, Z, breath). Reference layers are left out, as generation leaves them out. Prints each
 * layer's classification, so a tag the generator did not read shows. PSD2LIVE_EXPORT=1 also exports the model
 * (moc3, model3.json, motions, physics, cmo3) to <name>-export/. Writes build/tools/layout-rig-check/<name>-*.png.
 */
class LayoutRigCheckTool {
	@Test fun check() {
		requireTools()
		val out = File("build/tools/layout-rig-check").apply { mkdirs() }
		for (path in setting("PSD2LIVE_LAYOUT", "").split(',').filter { it.isNotBlank() }) {
			val manifest = Path.of(path).toAbsolutePath()
			val model = if (manifest.toString().endsWith(".psd")) PSD2LivePipeline().buildPreview(manifest) else {
				val read = LayoutManifest.read(manifest)
				val source = LayoutManifest.source(read.copy(layers = read.layers.filter { !it.reference }))
				PSD2LivePipeline().buildPreview(source.art, PipelineConfig(layerOverrides = source.overrides))
			}
			val name = if (manifest.toString().endsWith(".psd")) manifest.fileName.toString().removeSuffix(".psd") else manifest.parent.fileName.toString()
			if (setting("PSD2LIVE_EXPORT", "") == "1") {
				// The model as the editor exports it (moc3, model3.json, motions, physics; cmo3 to edit in Cubism).
				val result = PSD2LivePipeline().run(model.analysis.source, name, File(out, "$name-export").toPath(), model.config)
				println("$name: exported ${result.exportedFiles.size} files")
			}
			File(out, "$name-layers.txt").printWriter().use { w ->
				for (layer in model.analysis.layers) w.println("${layer.source.name}\t${layer.semantic}\t${layer.source.bounds}")
				w.println("parameters: " + model.rig.puppet.parameters.joinToString { it.id.raw })
			}
			val head = headRect(model)
			sheet(model, head, 360, "$name-face", listOf(
				"neutral" to emptyMap(),
				"blink" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
				"half lid, gaze" to mapOf("ParamEyeLOpen" to 0.5f, "ParamEyeROpen" to 0.5f, "ParamEyeBallX" to 0.8f, "ParamEyeBallY" to 0.4f),
				"mouth open" to mapOf("ParamMouthOpenY" to 1f),
				"smile talk" to mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.6f, "ParamEyeLOpen" to 0.75f, "ParamEyeROpen" to 0.75f),
				"frown" to mapOf("ParamMouthForm" to -1f, "ParamBrowLY" to -1f, "ParamBrowRY" to -1f),
			), out)
			sheet(model, head, 360, "$name-head", listOf(
				"X -30" to mapOf("ParamAngleX" to -30f), "X +30" to mapOf("ParamAngleX" to 30f),
				"Y +30" to mapOf("ParamAngleY" to 30f), "Y -30" to mapOf("ParamAngleY" to -30f),
				"X+30 Y+30" to mapOf("ParamAngleX" to 30f, "ParamAngleY" to 30f), "Z +30" to mapOf("ParamAngleZ" to 30f),
			), out)
			sheet(model, bodyRect(model), 640, "$name-body", listOf(
				"neutral" to emptyMap(),
				"body X -10" to mapOf("ParamBodyAngleX" to -10f), "body X +10" to mapOf("ParamBodyAngleX" to 10f),
				"body Z +10" to mapOf("ParamBodyAngleZ" to 10f), "head X+30, body X+10" to mapOf("ParamAngleX" to 30f, "ParamBodyAngleX" to 10f),
				"breath" to mapOf("ParamBreath" to 1f),
			), out)
			println("$name: ${model.analysis.layers.size} layers, sheets in ${out.path}")
		}
	}

	private fun sheet(model: RigPreviewModel, rect: Bounds, size: Int, name: String, poses: List<Pair<String, Map<String, Float>>>, out: File) {
		val shots = poses.map { (label, values) -> label to pose(model, values, rect, size) }
		val w = shots.maxOf { it.second.width }; val h = shots.maxOf { it.second.height }
		val image = BufferedImage(shots.size * w, h + 24, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		g.color = Color(236, 236, 236); g.fillRect(0, 0, image.width, image.height)
		g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
		for ((i, shot) in shots.withIndex()) {
			g.drawImage(shot.second, i * w, 24, null)
			g.color = Color.DARK_GRAY; g.drawString(shot.first, i * w + 8, 17)
		}
		g.dispose()
		ImageIO.write(image, "png", File(out, "$name.png"))
	}

	private fun pose(model: RigPreviewModel, values: Map<String, Float>, rect: Bounds, size: Int): BufferedImage {
		val layers = model.rig.layerIdByDrawableId.values.toSet()
		val view = WorkspaceViewRenderer.modelComposite(model, "r", values, layers, emptySet(), WorkspaceViewFrame.CanvasRect(rect),
			WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(size))
		val shot = ImageIO.read(view.png.inputStream())
		val flat = BufferedImage(shot.width, shot.height, BufferedImage.TYPE_INT_RGB)
		flat.createGraphics().apply { color = Color(236, 236, 236); fillRect(0, 0, shot.width, shot.height); drawImage(shot, 0, 0, null); dispose() }
		return flat
	}

	private fun headRect(model: RigPreviewModel): Bounds {
		val face = model.analysis.layers.first { it.source.name == "face" }.source.bounds
		val cx = face.left + face.width / 2f; val cy = face.top + face.height * 0.45f
		val half = face.width * 1.1f
		return Bounds(cx - half, cy - half, cx + half, cy + half)
	}

	private fun bodyRect(model: RigPreviewModel): Bounds {
		val boxes = model.analysis.layers.filter { it.opaquePixels > 0 }.map { it.source.bounds }
		val l = boxes.minOf { it.left }.toFloat(); val t = boxes.minOf { it.top }.toFloat()
		val r = boxes.maxOf { it.left + it.width }.toFloat(); val b = boxes.maxOf { it.top + it.height }.toFloat()
		val m = (b - t) * 0.04f
		return Bounds(l - m, t - m, r + m, b + m)
	}
}
