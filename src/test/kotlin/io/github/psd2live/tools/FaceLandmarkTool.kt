package io.github.psd2live.tools

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.fit.Landmark
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Draws the head's feature points ([io.github.psd2live.core.fit.FaceLandmarking]) over the sample's art: the face's
 * outline, every point labelled by its name, filled when the head's turn uses it and hollow when it is kept for later;
 * a second sheet zooms on the eyes and brows. Writes build/tools/face-landmarks/<sample>-head.png, -eyes.png and
 * -points.txt.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=tml ./gradlew test --tests '*FaceLandmarkTool'
 */
class FaceLandmarkTool {
	@Test fun landmarks() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = PSD2LivePipeline().buildPreview(sample.path)
		val context = RigBuilder.rigContext(built.analysis, built.config)
		val marks = context.faceLandmarks(built.config)
		val space = context.faceRig.coordinateSpace
		fun canvas(x: Float, y: Float) = space.toCanvas(x, y)
		val out = output("face-landmarks")
		File(out, "${sample.name}-points.txt").writeText(buildString {
			for (p in marks.points) { val (x, y) = canvas(p.x, p.y); appendLine("${p.id}\t${"%.1f".format(x)}\t${"%.1f".format(y)}${if (p.later) "\tlater" else ""}") }
			for ((k, v) in marks.measures) appendLine("$k = ${"%.2f".format(v)}")
		})
		val face = built.analysis.anchors.face
		val head = Bounds(face.left - face.width * 0.35f, face.top - face.height * 0.35f, face.right + face.width * 0.35f, face.bottom + face.height * 0.25f)
		val renderer = Renderer(built, 1600)
		ImageIO.write(draw(renderer, square(head), marks.points, marks.contour.map { canvas(it.first, it.second) }, ::canvas, 1600, 15f), "png", File(out, "${sample.name}-head.png"))
		// The eyes and the brows, each side zoomed.
		val frames = ArrayList<Pair<String, BufferedImage>>()
		for (side in listOf("R", "L")) {
			val pts = marks.points.filter { it.part == "eye.$side" || it.part == "brow.$side" }.map { canvas(it.x, it.y) }
			if (pts.isEmpty()) continue
			val b = Bounds(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
			val rect = square(Bounds(b.left - b.width * 0.25f, b.top - b.height * 0.25f, b.right + b.width * 0.25f, b.bottom + b.height * 0.25f))
			frames += "eye $side" to draw(renderer, rect, marks.points.filter { it.part == "eye.$side" || it.part == "brow.$side" }, emptyList(), ::canvas, 900, 13f)
		}
		if (frames.isNotEmpty()) sheet(frames, File(out, "${sample.name}-eyes.png"), columns = 2)
		// The face turn's field alone: the outline and the points carried to each pose (bright) over rest (faint).
		val turn = io.github.psd2live.core.fit.FaceTurn(marks)
		if (turn.usable) {
			val poses = listOf(15f to 0f, 30f to 0f, -30f to 0f, 0f to 30f, 0f to -30f, 30f to 30f, 30f to -30f, 45f to 0f)
			val cb = marks.contour.map { canvas(it.first, it.second) }
			val fb = Bounds(cb.minOf { it.first }, cb.minOf { it.second }, cb.maxOf { it.first }, cb.maxOf { it.second })
			val rect = square(Bounds(fb.left - fb.width * 0.15f, fb.top - fb.height * 0.1f, fb.right + fb.width * 0.15f, fb.bottom + fb.height * 0.1f))
			val turned = poses.map { (yaw, pitch) ->
				val f = turn.map(yaw, pitch)
				val moved = marks.points.map { p -> f(p.x, p.y).let { (x, y) -> p.copy(x = x, y = y) } }
				val outline = marks.contour.map { f(it.first, it.second) }.map { canvas(it.first, it.second) }
				"X$yaw Y$pitch" to drawTurn(renderer, rect, cb, outline, marks.points, moved, ::canvas, 600)
			}
			sheet(turned, File(out, "${sample.name}-turn.png"), columns = 4)
		}
	}

	private fun drawTurn(renderer: Renderer, rect: Bounds, rest: List<Pair<Float, Float>>, outline: List<Pair<Float, Float>>, before: List<Landmark>,
	                     after: List<Landmark>, canvas: (Float, Float) -> Pair<Float, Float>, size: Int): BufferedImage {
		val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.drawImage(renderer.render(emptyMap(), rect), 0, 0, size, size, null)
		g.color = Color(0, 0, 0, 170); g.fillRect(0, 0, size, size)
		val k = size / rect.width
		fun px(x: Float) = ((x - rect.left) * k).toDouble()
		fun py(y: Float) = ((y - rect.top) * k).toDouble()
		for ((line, colour) in listOf(rest to Color(120, 160, 255, 140), outline to Color(255, 230, 60))) {
			g.color = colour; g.stroke = BasicStroke(if (colour.alpha == 255) 2f else 1.2f)
			for (i in line.indices) { val a = line[i]; val b = line[(i + 1) % line.size]; g.draw(Line2D.Double(px(a.first), py(a.second), px(b.first), py(b.second))) }
		}
		for ((p, q) in before.zip(after)) {
			if (p.later || !(p.part.startsWith("face") || p.part.startsWith("eye") || p.part.startsWith("nose") || p.part.startsWith("mouth") || p.part.startsWith("brow"))) continue
			val (x0, y0) = canvas(p.x, p.y); val (x1, y1) = canvas(q.x, q.y)
			g.color = Color(255, 255, 255, 90); g.draw(Line2D.Double(px(x0), py(y0), px(x1), py(y1)))
			g.color = colour(p.part); g.fill(Ellipse2D.Double(px(x1) - 3, py(y1) - 3, 6.0, 6.0))
		}
		g.dispose()
		return img
	}

	private fun square(b: Bounds): Bounds {
		val side = maxOf(b.width, b.height)
		return Bounds(b.centerX - side / 2, b.centerY - side / 2, b.centerX + side / 2, b.centerY + side / 2)
	}

	private fun colour(part: String): Color = when {
		part.startsWith("eye") -> Color(60, 230, 120)
		part.startsWith("brow") -> Color(255, 150, 40)
		part.startsWith("nose") -> Color(255, 255, 255)
		part.startsWith("mouth") -> Color(255, 70, 90)
		part.startsWith("ear") -> Color(190, 110, 255)
		part.startsWith("hair") -> Color(70, 190, 255)
		else -> Color(255, 230, 60)
	}

	private fun draw(renderer: Renderer, rect: Bounds, points: List<Landmark>, contour: List<Pair<Float, Float>>,
	                 canvas: (Float, Float) -> Pair<Float, Float>, size: Int, font: Float): BufferedImage {
		val art = renderer.render(emptyMap(), rect)
		val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		g.drawImage(art, 0, 0, size, size, null)
		g.color = Color(0, 0, 0, 110); g.fillRect(0, 0, size, size)
		val k = size / rect.width
		fun px(x: Float) = ((x - rect.left) * k).toDouble()
		fun py(y: Float) = ((y - rect.top) * k).toDouble()
		if (contour.size > 2) {
			g.color = Color(255, 230, 60, 200); g.stroke = BasicStroke(1.5f)
			for (i in contour.indices) { val a = contour[i]; val b = contour[(i + 1) % contour.size]; g.draw(Line2D.Double(px(a.first), py(a.second), px(b.first), py(b.second))) }
		}
		g.font = Font(Font.SANS_SERIF, Font.PLAIN, font.toInt())
		val r = size / 160.0
		for (p in points) {
			val (x, y) = canvas(p.x, p.y)
			val c = colour(p.part)
			g.color = Color.BLACK; g.fill(Ellipse2D.Double(px(x) - r - 1.5, py(y) - r - 1.5, 2 * r + 3, 2 * r + 3))
			g.color = c; g.stroke = BasicStroke(2f)
			if (p.later) g.draw(Ellipse2D.Double(px(x) - r, py(y) - r, 2 * r, 2 * r)) else g.fill(Ellipse2D.Double(px(x) - r, py(y) - r, 2 * r, 2 * r))
			val label = p.id.removePrefix(p.part + ".").ifEmpty { p.id }
			g.color = Color(0, 0, 0, 180); g.drawString(label, (px(x) + r + 2).toFloat() + 1, (py(y) - r).toFloat() + 1)
			g.color = c; g.drawString(label, (px(x) + r + 2).toFloat(), (py(y) - r).toFloat())
		}
		g.dispose()
		return img
	}
}
