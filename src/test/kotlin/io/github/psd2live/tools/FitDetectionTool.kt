package io.github.psd2live.tools

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.fit.EyeFit
import io.github.psd2live.core.fit.EyeFitting
import io.github.psd2live.core.fit.EyeRig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import kotlin.test.Test

/**
 * What the feature fit reads off the sample PSD2LIVE_SAMPLE (docs/zh/spec/RIG_PRESETS.md), drawn over the art: per eye
 * the corners (inner green, outer red), the upper and lower lid edges, the drawn closed line (magenta) and the upper
 * lid at each openness key of the fitted eye (from yellow open to blue closed). Writes build/tools/fit-detection/<sample>.png
 * and prints why an eye was left to the legacy rig.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=tml ./gradlew test --tests '*FitDetectionTool'
 */
class FitDetectionTool {
	@Test fun detect() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val model = PSD2LivePipeline().buildPreview(sample.path)
		val result = EyeFitting.fit(model.analysis.layers, model.analysis.anchors.faceCenterX) { x, y -> x to y }
		result.reasons.forEach { (side, reason) -> println("FIT ${sample.name} $side left to the legacy rig: $reason") }
		val frames = result.fits.map { (side, fit) ->
			val (u0, v0) = -0.35f to -0.9f
			val a = fit.toCanvas(u0, v0); val b = fit.toCanvas(1.35f, 0.7f)
			val rect = Bounds(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
			val image = Renderer(model, 640).render(emptyMap(), rect)
			val g = image.createGraphics()
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			fun px(u: Float, v: Float): Pair<Double, Double> {
				val (x, y) = fit.toCanvas(u, v)
				return ((x - rect.left) / rect.width * image.width).toDouble() to ((y - rect.top) / rect.height * image.height).toDouble()
			}
			fun curve(values: List<Float>, color: Color, width: Float) {
				val path = Path2D.Double()
				values.forEachIndexed { k, v -> val (x, y) = px(EyeFit.u(k), v); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
				g.color = color; g.stroke = BasicStroke(width); g.draw(path)
			}
			val rig = EyeRig(fit, 0.15f, 1f, 0.6f)
			for (open in rig.keys.reversed()) {
				val t = 1f - open
				curve(rig.upper(open), Color(1f - t, 1f - 0.4f * t, t, 0.9f), 2f)
			}
			curve(fit.upper, Color.WHITE, 3f); curve(fit.lower, Color.CYAN, 3f)
			fit.closed?.let { curve(it, Color.MAGENTA, 2f) }
			for ((u, color) in listOf(0f to Color.GREEN, 1f to Color.RED)) {
				val (x, y) = px(u, 0f)
				g.color = color; g.fillOval((x - 6).toInt(), (y - 6).toInt(), 12, 12)
			}
			g.dispose()
			"$side width ${"%.0f".format(fit.width)}px" to image
		}
		for (layer in model.analysis.layers.filter { it.semantic.tag.name.endsWith("HAIR") || it.semantic.tag.name == "HEADWEAR" })
			println("HAIR ${sample.name} ${layer.source.name} ${layer.semantic.tag} role ${layer.semantic.role} bounds ${layer.bounds} opaque ${layer.opaquePixels}")
		for (layer in model.analysis.layers.filter { it.semantic.tag in io.github.psd2live.core.fit.FittedMouth.TAGS })
			println("MOUTH ${sample.name} ${layer.source.name} ${layer.semantic.tag} variant ${layer.semantic.variant} role ${layer.semantic.role} " +
				"bounds ${layer.bounds} opaque ${layer.opaquePixels} order ${layer.source.order}")
		val mouth = io.github.psd2live.core.fit.MouthFitting.fit(model.analysis.layers, result.level) { x, y -> x to y }
		mouth.reason?.let { println("FIT ${sample.name} mouth left to the legacy rig: $it") }
		val mouthFrames = listOfNotNull(mouth.fit?.let { fit ->
			val a = fit.toCanvas(-0.3f, -0.6f); val b = fit.toCanvas(1.3f, 0.9f)
			val rect = Bounds(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
			val image = Renderer(model, 640).render(mapOf("ParamMouthOpenY" to 1f), rect)
			val g = image.createGraphics()
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			fun px(u: Float, v: Float): Pair<Double, Double> {
				val (x, y) = fit.toCanvas(u, v)
				return ((x - rect.left) / rect.width * image.width).toDouble() to ((y - rect.top) / rect.height * image.height).toDouble()
			}
			fun curve(values: List<Float>, color: Color) {
				val path = Path2D.Double()
				values.forEachIndexed { k, v -> val (x, y) = px(io.github.psd2live.core.fit.MouthFit.u(k), v); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
				g.color = color; g.stroke = BasicStroke(2f); g.draw(path)
			}
			curve(fit.upper, Color.WHITE); curve(fit.lower, Color.CYAN)
			g.dispose()
			"mouth width ${"%.0f".format(fit.width)}px opening ${"%.2f".format(fit.opening)}" to image
		})
		if (frames.isNotEmpty() || mouthFrames.isNotEmpty()) sheet(frames + mouthFrames, output("fit-detection").resolve("${sample.name}.png"), columns = frames.size + mouthFrames.size)
	}
}
