package io.github.psd2live.tools

import io.github.psd2live.core.AdaptiveMeshGenerator
import io.github.psd2live.core.CanvasDensity
import io.github.psd2live.core.MeshEdgeMode
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.MeshResolution
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshTrace
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test

/**
 * Test plates for the project page, in one visual style: synthetic stress shapes meshed under several settings,
 * with the figures (vertices, triangles, outline loops, time) printed on each panel. Writes build/tools/readme-plates/.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ReadmeShowcaseTool'
 */
class ReadmeShowcaseTool {
	private companion object { const val TIP_X = 349.0; const val TIP_Y = 349.0 }
	private object Look {
		val page = Color(0x14, 0x16, 0x1b)
		val panel = Color(0x1c, 0x1f, 0x25)
		val border = Color(0x2b, 0x2f, 0x37)
		val title = Color(0xe6, 0xeb, 0xf2)
		val meta = Color(0x8b, 0x93, 0xa1)
		val accent = Color(0x6c, 0x9e, 0xff)
		val art = Color(0xc6, 0xcc, 0xd8)
		val wire = Color(0x4f, 0x8b, 0xff, 230)
		val halo = Color(10, 12, 16, 120)
		val vertex = Color(0x9c, 0xc0, 0xff)
		val miss = Color(0xff, 0x5a, 0x4e)
		val row2 = Color(0x3d, 0xd6, 0x8c)
		val row3 = Color(0xf5, 0xb8, 0x41)
		fun font(size: Float, bold: Boolean = false) = Font("Segoe UI", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
		fun mono(size: Float) = Font("Consolas", Font.PLAIN, 1).deriveFont(size)
	}

	/** A layer [units] canvas units square drawn from a [texture]-pixel raster where [inside] is true. */
	private class Shape(val name: String, val units: Int, val texture: Int, inside: (Double, Double) -> Boolean) {
		val alpha = BooleanArray(texture * texture)
		init { for (y in 0 until texture) for (x in 0 until texture) alpha[y * texture + x] = inside((x + 0.5) / texture, (y + 0.5) / texture) }
		val raster = LayerRaster(texture, texture, ByteArray(texture * texture * 4).also { rgba ->
			for (i in alpha.indices) if (alpha[i]) { rgba[i * 4] = -56; rgba[i * 4 + 1] = -52; rgba[i * 4 + 2] = -40; rgba[i * 4 + 3] = -1 }
		})
		val layer = WorkspaceSourceLayer(LayerId(name), name, "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, units, units), 1f, false,
			LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
	}

	private class Meshed(val mesh: AdaptiveMeshGenerator.Result, val millis: Double)

	private fun mesh(shape: Shape, settings: MeshSettings): Meshed {
		val input = MeshResolution.input(CanvasDensity.canvasLayer(shape.layer), MeshTrace.TEXTURE, 1f)
		MeshResolution.mesh(input, 8, settings, null)
		val start = System.nanoTime()
		val mesh = requireNotNull(MeshResolution.mesh(input, 8, settings, null)) { "no mesh for ${shape.name}" }
		return Meshed(mesh, (System.nanoTime() - start) / 1e6)
	}

	private fun segment(u: Double, v: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
		val sx = bx - ax; val sy = by - ay
		val t = (((u - ax) * sx + (v - ay) * sy) / (sx * sx + sy * sy)).coerceIn(0.0, 1.0)
		return hypot(u - ax - sx * t, v - ay - sy * t)
	}

	/**
	 * A filigree medallion: a pierced outer ring, slotted middle ring, perforated hub, hairline spokes, and four
	 * tapering tendrils curling out to the corners - many holes, thin bridges and long tips in one layer.
	 */
	private fun medallion(u: Double, v: Double): Boolean {
		val dx = u - 0.5; val dy = v - 0.5
		val r = hypot(dx, dy); val a = atan2(dy, dx)
		// Outer ring with 20 round piercings.
		if (r in 0.36..0.44) {
			val k = Math.round((a + PI) / (2 * PI) * 20)
			val ha = k * 2 * PI / 20 - PI
			return hypot(u - (0.5 + 0.40 * cos(ha)), v - (0.5 + 0.40 * sin(ha))) > 0.022
		}
		// Middle ring cut by 28 radial slots.
		if (r in 0.22..0.27) return abs(((a + PI) / (2 * PI) * 28) % 1.0 - 0.5) > 0.16
		// Hub with seven holes.
		if (r < 0.11) {
			if (r < 0.025) return false
			return (0 until 6).none { k -> hypot(u - (0.5 + 0.068 * cos(k * PI / 3)), v - (0.5 + 0.068 * sin(k * PI / 3))) < 0.022 }
		}
		// Twelve hairline spokes, alternating straight and curved.
		for (k in 0 until 12) {
			val base = k * 2 * PI / 12
			val bend = if (k % 2 == 0) 0.0 else 0.9 * (r - 0.11)
			val da = ((a - base - bend + 3 * PI) % (2 * PI)) - PI
			if (r in 0.11..0.36 && abs(da) * r < 0.0045) return true
		}
		// Four tendrils curling from the ring towards the corners, tapering to hairline tips.
		for (q in 0 until 4) {
			val dir = PI / 4 + q * PI / 2
			fun at(t: Double): Pair<Double, Double> {
				val rr = 0.43 + t * 0.19; val ang = dir + 0.35 * sin(t * PI)
				return (0.5 + rr * cos(ang)) to (0.5 + rr * sin(ang))
			}
			if (abs(u - 0.5) < 0.2 && abs(v - 0.5) < 0.2) break
			for (s in 0 until 48) {
				val t0 = s / 48.0; val t1 = (s + 1) / 48.0
				val (ax, ay) = at(t0); val (bx, by) = at(t1)
				if (abs(u - ax) > 0.03 || abs(v - ay) > 0.03) continue
				if (segment(u, v, ax, ay, bx, by) < 0.011 * (1 - t0) + 0.0012) return true
			}
		}
		return false
	}

	/** Thick bands with round holes: room for two and three outline rings everywhere. */
	private fun ornament(u: Double, v: Double): Boolean {
		val dx = u - 0.5; val dy = v - 0.5; val r = hypot(dx, dy)
		if (r in 0.27..0.46) return (0 until 7).none { k -> val a = k * 2 * PI / 7; hypot(u - 0.5 - 0.365 * cos(a), v - 0.5 - 0.365 * sin(a)) < 0.045 }
		if (r < 0.15) return r > 0.05
		// A crescent bridging hub and ring.
		return hypot(u - 0.5, v - 0.30) < 0.10 && hypot(u - 0.5, v - 0.25) > 0.085 && v > 0.28
	}

	// ---- drawing ----

	private fun canvas(width: Int, height: Int): Pair<BufferedImage, Graphics2D> {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		g.color = Look.page; g.fillRect(0, 0, width, height)
		return image to g
	}

	private fun header(g: Graphics2D, x: Int, y: Int, title: String, subtitle: String) {
		g.color = Look.accent; g.fillRect(x, y + 4, 4, 30)
		g.font = Look.font(22f, bold = true); g.color = Look.title; g.drawString(title, x + 16, y + 24)
		g.font = Look.font(14f); g.color = Look.meta; g.drawString(subtitle, x + 16, y + 44)
	}

	/** One panel: the art of [shape] over window [wx, wy, span] with [mesh] on top, and its label lines. */
	private fun panel(g: Graphics2D, x: Int, y: Int, size: Int, shape: Shape, meshed: Meshed?, wx: Double, wy: Double, span: Double,
		label: String, meta: String, line: Float = 1f, dot: Double = 0.0, rows: Boolean = false) {
		g.color = Look.panel; g.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), size.toDouble(), size + 54.0, 10.0, 10.0))
		g.color = Look.border; g.draw(RoundRectangle2D.Double(x + 0.5, y + 0.5, size - 1.0, size + 53.0, 10.0, 10.0))
		val inner = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
		val scale = size / span
		val perUnit = shape.texture.toDouble() / shape.units
		val art = Look.art.rgb
		for (py in 0 until size) for (px in 0 until size) {
			val tx = ((wx + (px + 0.5) / scale) * perUnit).toInt(); val ty = ((wy + (py + 0.5) / scale) * perUnit).toInt()
			if (tx in 0 until shape.texture && ty in 0 until shape.texture && shape.alpha[ty * shape.texture + tx]) inner.setRGB(px, py, (art and 0xffffff) or (0x55 shl 24))
		}
		val ig = inner.createGraphics()
		ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		ig.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
		meshed?.mesh?.let { mesh ->
			val p = mesh.positions
			fun sx(i: Int) = (p[i * 2] - wx) * scale
			fun sy(i: Int) = (p[i * 2 + 1] - wy) * scale
			val edges = HashSet<Long>()
			for (t in mesh.indices.indices step 3) for (k in 0..2) {
				val a = mesh.indices[t + k]; val b = mesh.indices[t + (k + 1) % 3]
				edges += min(a, b).toLong() shl 32 or max(a, b).toLong()
			}
			for ((stroke, color) in listOf(line + 1.4f to Look.halo, line to Look.wire)) {
				ig.stroke = BasicStroke(stroke); ig.color = color
				for (e in edges) { val a = (e shr 32).toInt(); val b = (e and 0xffffffff).toInt(); ig.draw(Line2D.Double(sx(a), sy(a), sx(b), sy(b))) }
			}
			if (rows) for ((loops, color) in listOf(mesh.middleLoops to Look.row2, mesh.innerLoops to Look.row3)) {
				ig.stroke = BasicStroke(line + 0.4f); ig.color = color
				for (loop in loops) for (i in loop.indices) { val a = loop[i]; val b = loop[(i + 1) % loop.size]; ig.draw(Line2D.Double(sx(a), sy(a), sx(b), sy(b))) }
			}
			if (dot > 0) for (i in 0 until p.size / 2) {
				ig.color = Look.halo; ig.fill(Ellipse2D.Double(sx(i) - dot - 1, sy(i) - dot - 1, dot * 2 + 2, dot * 2 + 2))
				ig.color = Look.vertex; ig.fill(Ellipse2D.Double(sx(i) - dot, sy(i) - dot, dot * 2, dot * 2))
			}
		}
		ig.dispose()
		g.drawImage(inner, x, y, null)
		g.font = Look.font(15f, bold = true); g.color = Look.title; g.drawString(label, x + 12, y + size + 22)
		g.font = Look.mono(12.5f); g.color = Look.meta; g.drawString(meta, x + 12, y + size + 42)
	}

	private fun stats(m: Meshed) = "V ${m.mesh.positions.size / 2}  T ${m.mesh.indices.size / 3}  loops ${m.mesh.boundaryLoops.size}  ${"%.0f".format(m.millis)} ms"

	private fun write(image: BufferedImage, name: String) {
		val out = output("readme-plates")
		ImageIO.write(image, "png", File(out, "$name.png"))
		println("wrote $name ${image.width}x${image.height}")
	}

	// ---- plates ----

	private val medallion by lazy { Shape("medallion", 400, 2000, ::medallion) }
	private val ornament by lazy { Shape("ornament", 300, 1200, ::ornament) }
	private val baseSettings = MeshSettings(maxEdgeDistance = 6f, interiorDensity = 18f)

	@Test fun meshStress() {
		requireTools()
		val shape = medallion
		val meshed = mesh(shape, baseSettings)
		val (image, g) = canvas(1600, 1000)
		header(g, 40, 30, "Topology stress test", "One synthetic layer: 20 piercings, 28 slots, 6-hole hub, 12 hairline spokes, 4 tapering tendrils  ·  texture trace, default fill")
		panel(g, 40, 110, 820, shape, meshed, 0.0, 0.0, 400.0, "Full layer", stats(meshed), line = 0.9f)
		// Close-ups: the pierced ring with its bridges, the hub, and a tendril tip.
		panel(g, 900, 110, 300, shape, meshed, 248.0, 52.0, 70.0, "Ring piercings", "20 round holes, r 8.8", line = 1.3f, dot = 2.2)
		panel(g, 1240, 110, 300, shape, meshed, 170.0, 170.0, 60.0, "Hub & spokes", "spokes 3.6 units wide", line = 1.3f, dot = 2.2)
		panel(g, 900, 494, 300, shape, meshed, 150.0, 244.0, 64.0, "Curved spokes", "hairline bridges between rings", line = 1.3f, dot = 2.2)
		panel(g, 1240, 494, 300, shape, meshed, 188.0, 60.0, 64.0, "Slot ring", "28 radial slots", line = 1.3f, dot = 2.2)
		g.font = Look.mono(13f); g.color = Look.meta
		g.drawString("layer 400 × 400 units  ·  raster 2000 × 2000 px  ·  edge spacing 6  ·  interior 18", 900, 900)
		g.drawString("each piercing, slot and gap is traced as its own outline loop (${meshed.mesh.boundaryLoops.size} loops)", 900, 924)
		g.dispose()
		write(image, "mesh-stress")
	}

	@Test fun meshParams() {
		requireTools()
		val shape = ornament
		val fills = listOf(MeshFillAlgorithm.GRADED_POISSON, MeshFillAlgorithm.ADAPTIVE_QUADTREE, MeshFillAlgorithm.CONTOUR_PAVING, MeshFillAlgorithm.TRIANGLE_FRACTAL)
		val edges = listOf(MeshEdgeMode.SINGLE, MeshEdgeMode.DOUBLE, MeshEdgeMode.TRIPLE)
		val cell = 330; val gap = 18; val left = 140; val top = 140
		val (image, g) = canvas(left + fills.size * (cell + gap) + 22, top + edges.size * (cell + 54 + gap) + 20)
		header(g, 40, 30, "Edge rows × interior fill", "Rows: outline rings (1 / 2 / 3) joined by fixed strips; columns: interior fill  ·  amber = innermost ring, green = middle ring")
		g.font = Look.font(15f, bold = true); g.color = Look.title
		val fillNames = listOf("Graded Poisson", "Adaptive quadtree", "Contour paving", "Triangle fractal")
		fills.indices.forEach { c -> g.drawString(fillNames[c], left + c * (cell + gap) + 12, top - 14) }
		val edgeNames = listOf("1 ring", "2 rings", "3 rings")
		for ((r, edge) in edges.withIndex()) {
			g.font = Look.font(15f, bold = true); g.color = Look.title
			g.drawString(edgeNames[r], 40, top + r * (cell + 54 + gap) + cell / 2)
			for ((c, fill) in fills.withIndex()) {
				val meshed = mesh(shape, MeshSettings(maxEdgeDistance = 7f, interiorDensity = 22f, edgeMode = edge, edgeWidth = 9f, fillAlgorithm = fill))
				panel(g, left + c * (cell + gap), top + r * (cell + 54 + gap), cell, shape, meshed, 0.0, 0.0, 300.0,
					"${edgeNames[r]} · ${fillNames[c]}", stats(meshed), line = 1.0f, rows = true)
			}
		}
		g.dispose()
		write(image, "mesh-params")
	}

	/** Strands and lashes a few texture pixels apart: the wrap folds gaps narrower than it into one envelope. */
	private val strands by lazy { Shape("strands", 240, 1440) { u, v ->
		(0 until 26).any { k ->
			val x0 = 0.08 + 0.84 * k / 25.0
			val t = (v - 0.08) / (0.55 + 0.3 * ((k * 7) % 5) / 4.0)
			t in 0.0..1.0 && abs(u - x0 - 0.10 * (k / 25.0 - 0.5) * t * t - 0.02 * sin(t * 6 + k)) < 0.0035 + 0.007 * (1 - t)
		} || (v in 0.04..0.09 && u in 0.07..0.93)
	} }

	@Test fun meshWrap() {
		requireTools()
		val wraps = listOf(0f, 3f, 8f, 20f)
		val cell = 360; val gap = 18
		val (image, g) = canvas(40 + wraps.size * (cell + gap) + 22, 140 + cell + 54 + 30)
		header(g, 40, 30, "Wrap: envelope across narrow gaps", "26 strands 2–10 px apart; wrap folds gaps narrower than its width into one outline, trading fidelity for vertex count")
		for ((i, wrap) in wraps.withIndex()) {
			val meshed = mesh(strands, MeshSettings(maxEdgeDistance = 8f, interiorDensity = 20f, wrap = wrap))
			panel(g, 40 + i * (cell + gap), 110, cell, strands, meshed, 0.0, 0.0, 240.0, "wrap ${"%.0f".format(wrap)}", stats(meshed), line = 0.9f)
		}
		g.dispose()
		write(image, "mesh-wrap")
	}

	// ---- generated rig ----

	/** [model] posed at [values] over canvas [rect], as an image [size] pixels on its longer side, transparent. */
	private fun pose(model: io.github.psd2live.core.RigPreviewModel, values: Map<String, Float>, rect: io.github.psd2live.core.Bounds, size: Int): BufferedImage {
		val layers = model.rig.layerIdByDrawableId.values.toSet()
		val view = io.github.psd2live.application.WorkspaceViewRenderer.modelComposite(model, "r", values, layers, emptySet(),
			io.github.psd2live.project.WorkspaceViewFrame.CanvasRect(rect), io.github.psd2live.project.WorkspaceViewBackground.TRANSPARENT,
			io.github.psd2live.project.WorkspaceViewOutputSpec(size))
		return ImageIO.read(view.png.inputStream())
	}

	/** A plain panel with [image] centred in it and two label lines. */
	private fun picture(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, image: BufferedImage, label: String, meta: String) {
		g.color = Look.panel; g.fill(RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h + 54.0, 10.0, 10.0))
		g.color = Look.border; g.draw(RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1.0, h + 53.0, 10.0, 10.0))
		val k = min(w.toDouble() / image.width, h.toDouble() / image.height)
		val iw = (image.width * k).toInt(); val ih = (image.height * k).toInt()
		g.drawImage(image, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, null)
		g.font = Look.font(15f, bold = true); g.color = Look.title; g.drawString(label, x + 12, y + h + 22)
		g.font = Look.mono(12.5f); g.color = Look.meta; g.drawString(meta, x + 12, y + h + 42)
	}

	private fun headRect(model: io.github.psd2live.core.RigPreviewModel): io.github.psd2live.core.Bounds {
		val face = model.analysis.layers.first { it.source.name == "face" }.source.bounds
		val cx = face.left + face.width / 2f; val cy = face.top + face.height * 0.42f
		val half = face.width * 1.25f
		return io.github.psd2live.core.Bounds(cx - half, cy - half, cx + half, cy + half)
	}

	private fun bodyRect(model: io.github.psd2live.core.RigPreviewModel, margin: Float = 0.04f): io.github.psd2live.core.Bounds {
		val boxes = model.analysis.layers.filter { it.opaquePixels > 0 }.map { it.source.bounds }
		val l = boxes.minOf { it.left }.toFloat(); val t = boxes.minOf { it.top }.toFloat()
		val r = boxes.maxOf { it.left + it.width }.toFloat(); val b = boxes.maxOf { it.top + it.height }.toFloat()
		val m = (b - t) * margin
		return io.github.psd2live.core.Bounds(l - m * 3, t - m, r + m * 3, b + m)
	}

	private fun samples() = listOf("tml", "ds").map { Sample(it, java.nio.file.Path.of("examples/$it/psd-input/$it.psd")) }

	@Test fun rigHead() {
		requireTools()
		val models = samples().map { io.github.psd2live.core.PSD2LivePipeline().buildPreview(it.path) }
		val cell = 230; val gap = 14; val block = 3 * cell + 2 * gap
		val (image, g) = canvas(40 + 2 * block + 60 + 40, 120 + 3 * (cell + 54 + gap) + 20)
		header(g, 40, 30, "Generated head rig: AngleX × AngleY", "Straight from import, no hand edits  ·  face warp, feature planes and hair follow from one generated deformer chain")
		for ((m, model) in models.withIndex()) {
			val rect = headRect(model)
			for ((r, y) in listOf(30f, 0f, -30f).withIndex()) for ((c, x) in listOf(-45f, 0f, 45f).withIndex()) {
				val shot = pose(model, mapOf("ParamAngleX" to x, "ParamAngleY" to y), rect, 460)
				picture(g, 40 + m * (block + 60) + c * (cell + gap), 110 + r * (cell + 54 + gap), cell, cell, shot,
					"", "X ${"%+.0f".format(x)}  Y ${"%+.0f".format(y)}")
			}
		}
		g.dispose()
		write(image, "rig-head")
	}

	@Test fun rigFace() {
		requireTools()
		val models = samples().map { io.github.psd2live.core.PSD2LivePipeline().buildPreview(it.path) }
		val poses = listOf(
			"Neutral" to emptyMap(),
			"Blink" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
			"Half lid + gaze" to mapOf("ParamEyeLOpen" to 0.55f, "ParamEyeROpen" to 0.55f, "ParamEyeBallX" to 0.8f, "ParamEyeBallY" to 0.4f),
			"Mouth open" to mapOf("ParamMouthOpenY" to 1f),
			"Smile" to mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.6f, "ParamEyeLOpen" to 0.75f, "ParamEyeROpen" to 0.75f),
			"Frown" to mapOf("ParamMouthForm" to -1f, "ParamBrowLY" to -1f, "ParamBrowRY" to -1f),
		)
		val cell = 230; val gap = 14
		val (image, g) = canvas(40 + poses.size * (cell + gap) + 26, 120 + 2 * (cell + 54 + gap) + 20)
		header(g, 40, 30, "Generated facial parameters", "Eyelids, gaze, mouth and brows as generated: lids close over the iris, the mouth opens on its own inner layers")
		for ((m, model) in models.withIndex()) {
			val head = headRect(model)
			val rect = io.github.psd2live.core.Bounds(head.left + head.width * 0.18f, head.top + head.height * 0.22f, head.right - head.width * 0.18f, head.bottom - head.height * 0.14f)
			for ((c, pose) in poses.withIndex()) {
				val shot = pose(model, pose.second, rect, 460)
				picture(g, 40 + c * (cell + gap), 110 + m * (cell + 54 + gap), cell, cell, shot, pose.first,
					pose.second.size.let { if (it == 0) "all defaults" else "$it parameters" })
			}
		}
		g.dispose()
		write(image, "rig-face")
	}

	@Test fun rigBody() {
		requireTools()
		val builds = samples().map { build(it) }
		val names = listOf("Wave", "Cheer", "Crouch", "WeightShift", "Shy", "HeadTilt")
		val w = 220; val h = 360; val gap = 14
		val (image, g) = canvas(40 + (names.size + 1) * (w + gap) + 26, 120 + 2 * (h + 54 + gap) + 20)
		header(g, 40, 30, "Automatic skeleton: preset poses", "Bones inferred from layer tags, baked into native deformers and keyforms  ·  each preset at its widest pose; same presets, two different bodies")
		for ((m, built) in builds.withIndex()) {
			val model = built.skeletal
			val params = model.rig.puppet.parameters.associateBy { it.id.raw }
			val rect = bodyRect(model)
			picture(g, 40, 110 + m * (h + 54 + gap), w, h, pose(model, emptyMap(), rect, 720), "Rest", "${built.spec.bones.size} bones")
			for ((c, name) in names.withIndex()) {
				val preset = io.github.psd2live.core.SkeletonMotions.presets.first { it.name == name }
				val tracks = preset.tracks(built.spec)
				val duration = tracks.maxOfOrNull { it.keys.last().time } ?: 0f
				fun spread(t: Float) = tracks.sumOf { tr -> val p = params[tr.parameterId]; val v = io.github.psd2live.core.MotionCurveMath.value(tr, t)
					if (p == null || p.max <= p.min) 0.0 else abs(v - p.default).toDouble() / (p.max - p.min) }
				val t = (0..40).map { duration * it / 40f }.maxByOrNull(::spread) ?: 0f
				val values = tracks.associate { it.parameterId to io.github.psd2live.core.MotionCurveMath.value(it, t) }
				picture(g, 40 + (c + 1) * (w + gap), 110 + m * (h + 54 + gap), w, h, pose(model, values, rect, 720), name,
					"t ${"%.2f".format(t)} s · ${tracks.size} tracks")
			}
		}
		g.dispose()
		write(image, "rig-body")
	}

	/**
	 * Knee bend on a long-legged figure (PSD2LIVE_KNEE_SAMPLE, a PSD): each shin swept through its range, with the
	 * knee close-ups at 0, 30, 60, 90 and 120 degrees as the joint skinning research checked them.
	 */
	@Test fun rigKnee() {
		requireTools()
		val path = java.nio.file.Path.of(setting("PSD2LIVE_KNEE_SAMPLE", "out/star_lan/star_lan.psd2live"))
		val (model, skeleton) = if (path.toString().endsWith(".psd2live", ignoreCase = true)) kotlinx.coroutines.runBlocking {
			// A saved project: its own skeleton and edits, or an automatic skeleton on its artwork when it has none.
			io.github.psd2live.project.ProjectRepository().open(path).use { opened ->
				val document = opened.history.head().snapshot
				val builder = io.github.psd2live.application.WorkspacePreviewBuilder()
				val saved = builder.build(document)
				val enabled = saved.config.rigEdits.skeleton?.takeIf { it.enabled }
				if (enabled != null) saved to enabled else {
					val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(saved.analysis, saved.rig).copy(enabled = true)
					builder.build(builder.normalizeMeshEdits(document.copy(rigEdits = document.rigEdits.copy(skeleton = spec)), saved)) to spec
				}
			}
		} else kotlinx.coroutines.runBlocking {
			// Legs and boots drawn on one layer each are split per side first, as the start screen does.
			val builder = io.github.psd2live.application.WorkspacePreviewBuilder()
			val runtime = io.github.psd2live.application.WorkspaceRuntime<io.github.psd2live.core.RigPreviewModel>({ builder.build(it) })
			io.github.psd2live.application.WorkspaceSourceImporter(runtime).importPsd(path.toAbsolutePath(), null, runtime.state.value.state)
			var captured = runtime.capture()
			var document = captured.document; var current = captured.model
			for (tag in listOf(io.github.psd2live.core.SemanticTag.LEGWEAR, io.github.psd2live.core.SemanticTag.FOOTWEAR)) {
				val layer = current.analysis.layers.firstOrNull { it.semantic.tag == tag && it.semantic.side == io.github.psd2live.core.Side.NONE } ?: continue
				val plan = io.github.psd2live.application.WorkspacePartitionEdits.componentPlan(current, layer.source.id.raw) ?: continue
				val mid = plan.components.let { c -> (c.minOf { it.centerX } + c.maxOf { it.centerX }) / 2f }
				println("${layer.source.name} islands: ${plan.components.map { it.centerX }}")
				// Cubism convention: the character's left faces the viewer's right.
				val sides = plan.components.map { if (it.centerX >= mid) "left" else "right" }
				val names = plan.components.mapIndexed { i, _ -> "${layer.source.name}-${sides[i].first()}$i" }
				val op = io.github.psd2live.application.WorkspaceDocumentOperation("source_split_components", kotlinx.serialization.json.buildJsonObject {
					put("layer_id", kotlinx.serialization.json.JsonPrimitive(layer.source.id.raw))
					put("names", kotlinx.serialization.json.JsonArray(names.map { kotlinx.serialization.json.JsonPrimitive(it) }))
					put("sides", kotlinx.serialization.json.JsonArray(sides.map { kotlinx.serialization.json.JsonPrimitive(it) }))
				})
				document = io.github.psd2live.application.WorkspacePartitionEdits.apply(op, document, current)
				current = builder.build(document)
			}
			val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(current.analysis, current.rig).copy(enabled = true)
			val skeletal = builder.build(document.copy(rigEdits = document.rigEdits.copy(skeleton = spec)))
			skeletal to spec
		}
		println("leg params: ${model.rig.puppet.parameters.map { it.id.raw }.filter { it.startsWith("ParamLeg") }}")
		val shins = skeleton.bones.filter { it.role == io.github.psd2live.core.BoneRole.SHIN }
		require(shins.isNotEmpty()) { "no shin bones: ${skeleton.bones.map { it.id }}" }
		println("shins: ${shins.map { "${it.id} ${it.parameterId} ${it.minAngle}..${it.maxAngle}" }}")
		val thighs = skeleton.bones.filter { it.role == io.github.psd2live.core.BoneRole.THIGH }
		val character = model.analysis.anchors.character
		val legs = io.github.psd2live.core.Bounds(character.left, thighs.minOf { it.headY } - character.height * 0.02f, character.right, character.bottom + character.height * 0.01f)
		val shin = shins.first()
		val thigh = skeleton.bones.single { it.id == shin.parentId }
		val w = 220; val h = 330; val gap = 14
		val radius = minOf(thigh.length, shin.length) * 0.42f
		val kh = radius * 1.25f; val kw = kh * w / h
		val knee = io.github.psd2live.core.Bounds(shin.headX - kw, shin.headY - kh, shin.headX + kw, shin.headY + kh)
		val shinAngles = listOf(0f, 60f, 120f).map { it.coerceIn(shin.minAngle, shin.maxAngle) }
		val kneeAngles = listOf(0f, 30f, 120f).map { it.coerceIn(shin.minAngle, shin.maxAngle) }
		val (image, g) = canvas(40 + 6 * (w + gap) + 26, 120 + h + 54 + 40)
		header(g, 40, 30, "Knee joint: bend", "Skeleton on a long-legged figure  ·  left: one shin bent, the other at rest; right: knee close-ups, outer cap and inner fold from the joint template")
		for ((c, angle) in shinAngles.withIndex()) picture(g, 40 + c * (w + gap), 110, w, h, pose(model, mapOf(shin.parameterId to angle), legs, 720),
			"Shin ${"%.0f".format(angle)}°", shin.parameterId)
		for ((c, angle) in kneeAngles.withIndex()) picture(g, 40 + (c + 3) * (w + gap), 110, w, h, pose(model, mapOf(shin.parameterId to angle), knee, 600),
			"Knee ${"%.0f".format(angle)}°", "close-up")
		g.dispose()
		write(image, "rig-knee")
	}

	/**
	 * Body orbit: ParamBodyAngleX / Y driven up and down, left and right, then round an ellipse (PSD2LIVE_ORBIT =
	 * "x,y" amplitudes, default 8,10; PSD2LIVE_ORBIT_PERIOD seconds a round), exported as PNG frames with physics on and off for each project in
	 * PSD2LIVE_ORBIT_PROJECTS (comma separated .psd2live paths): build/tools/readme-orbit/<name>-{on,off}/, with the
	 * simulations' pin weight maps in <name>-pins/.
	 */
	@Test fun bodyOrbit() = kotlinx.coroutines.runBlocking {
		requireTools()
		val (ax, ay) = setting("PSD2LIVE_ORBIT", "8,10").split(',').map { it.trim().toFloat() }
		// Three rounds from rest: up and down, left and right, then an ellipse that swells and settles; a short rest after.
		val period = setting("PSD2LIVE_ORBIT_PERIOD", "1.2").toFloat()
		val lead = 0.3f; val ellipse = period * 2f; val tail = 0.6f
		val duration = lead + period * 2 + ellipse + tail
		fun body(t: Float): Pair<Double, Double> {
			var u = t - lead
			if (u < 0) return 0.0 to 0.0
			if (u < period) return 0.0 to -ay * sin(2 * PI * u / period)
			u -= period
			if (u < period) return ax * sin(2 * PI * u / period) to 0.0
			u -= period
			if (u < ellipse) {
				val envelope = sin(PI * u / ellipse)
				val w = 2 * PI * u / period
				return envelope * ax * sin(w) to -envelope * ay * cos(w)
			}
			return 0.0 to 0.0
		}
		val steps = (duration * 40).toInt()
		val times = (0..steps).map { it * duration / steps }
		val clip = io.github.psd2live.core.MotionClip("ReadmeOrbit", "ReadmeOrbit", duration = duration, fadeIn = 0f, fadeOut = 0f, curves = listOf(
			io.github.psd2live.core.MotionCurve("ParamBodyAngleX", times.map { io.github.psd2live.core.MotionKey(it, body(it).first.toFloat(), io.github.psd2live.core.MotionInterpolation.LINEAR) }),
			io.github.psd2live.core.MotionCurve("ParamBodyAngleY", times.map { io.github.psd2live.core.MotionKey(it, body(it).second.toFloat(), io.github.psd2live.core.MotionInterpolation.LINEAR) })))
		for (raw in setting("PSD2LIVE_ORBIT_PROJECTS", "").split(',').filter { it.isNotBlank() }) {
			val path = java.nio.file.Path.of(raw.trim())
			val name = path.fileName.toString().substringBefore('.')
			val preview = io.github.psd2live.project.ProjectRepository().open(path).use { opened ->
				val document = opened.history.head().snapshot
				io.github.psd2live.application.WorkspacePreviewBuilder().build(document.copy(rigEdits = document.rigEdits.copy(
					motionClips = document.rigEdits.motionClips.filter { it.id != clip.id } + clip)))
			}
			for ((label, physics) in listOf("on" to "true", "off" to "false")) {
				val target = io.github.psd2live.core.ExportService.registry(preview.config)["png-sequence"]
				val options = io.github.psd2live.core.ExportService.options(target, name, preview.config,
					mapOf("clip" to clip.id, "size" to setting("PSD2LIVE_ORBIT_SIZE", "900"), "physics" to physics, "fps" to "30"))
				val dir = output("readme-orbit/$name-$label").toPath()
				dir.toFile().listFiles()?.forEach { it.delete() }
				val report = io.github.psd2live.core.ExportService.export(preview, target, options, dir)
				println("$name $label: ${report.files.size} files")
			}
			pinMap(preview.rig.puppet, preview.config.rigEdits.simEdits, output("readme-orbit/$name-on"), output("readme-orbit/$name-pins"))
		}
	}

	/**
	 * Each simulation's meshes at rest, washed in their pin weight (Blender's ramp, as the editor shows it) at twice the
	 * exported frames' pixels: build/tools/readme-orbit/<name>-pins/pins-<i>.png, the simulations listed in pins.txt.
	 */
	private fun pinMap(puppet: org.umamo.runtime.model.PuppetModel, sims: List<io.github.psd2live.core.sim.RigSimEdit>, frames: File, dir: File) {
		val first = ImageIO.read(frames.listFiles { f -> f.extension == "png" }!!.minBy { it.name })
		val zoom = 2
		val scale = zoom * first.width / puppet.canvasWidth
		val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
		val ramp = listOf(0x2A48E0, 0x22B8D8, 0x3CC84A, 0xE8D030, 0xE83A2A).map { floatArrayOf((it shr 16 and 255).toFloat(), (it shr 8 and 255).toFloat(), (it and 255).toFloat()) }
		fun heat(w: Float): Int {
			val x = w.coerceIn(0f, 1f) * (ramp.size - 1); val i = x.toInt().coerceAtMost(ramp.size - 2); val t = x - i
			val c = FloatArray(3) { ramp[i][it] + (ramp[i + 1][it] - ramp[i][it]) * t }
			return (255 shl 24) or (c[0].toInt() shl 16) or (c[1].toInt() shl 8) or c[2].toInt()
		}
		dir.listFiles()?.forEach { it.delete() }
		val notes = StringBuilder()
		for ((index, sim) in sims.withIndex()) {
			val scene = io.github.psd2live.core.sim.SimScene.build(puppet, sim)
			notes.append("$index\t${sim.kind}\t${sim.name}\t${scene.offsets.keys.joinToString(", ") { id -> puppet.drawables.first { it.id == id }.name }}\n")
			val image = BufferedImage(first.width * zoom, first.height * zoom, BufferedImage.TYPE_INT_ARGB)
			val wires = ArrayList<FloatArray>()
			for ((id, offset) in scene.offsets) {
				val mesh = puppet.drawables.first { it.id == id }.mesh ?: continue
				val p = world[id] ?: continue
				val px = FloatArray(p.size) { if (it % 2 == 0) p[it] * scale else -p[it] * scale } // world y is negated canvas y
				val w = FloatArray(mesh.vertexCount) { scene.solver.pinWeight[offset + it] }
				for (t in 0 until mesh.triangleCount) {
					val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
					val ax = px[a * 2]; val ay = px[a * 2 + 1]; val bx = px[b * 2]; val by = px[b * 2 + 1]; val cx = px[c * 2]; val cy = px[c * 2 + 1]
					val d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
					if (abs(d) < 1e-6f) continue
					for (y in max(0, min(ay, min(by, cy)).toInt())..min(image.height - 1, max(ay, max(by, cy)).toInt() + 1))
						for (x in max(0, min(ax, min(bx, cx)).toInt())..min(image.width - 1, max(ax, max(bx, cx)).toInt() + 1)) {
							val fx = x + 0.5f; val fy = y + 0.5f
							val l1 = ((by - cy) * (fx - cx) + (cx - bx) * (fy - cy)) / d
							val l2 = ((cy - ay) * (fx - cx) + (ax - cx) * (fy - cy)) / d
							val l3 = 1 - l1 - l2
							if (l1 < -1e-4f || l2 < -1e-4f || l3 < -1e-4f) continue
							image.setRGB(x, y, heat(l1 * w[a] + l2 * w[b] + l3 * w[c]))
						}
					wires += floatArrayOf(ax, ay, bx, by, cx, cy)
				}
			}
			val g = image.createGraphics()
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			g.color = Color(255, 255, 255, 60); g.stroke = BasicStroke(0.8f)
			for (t in wires) {
				g.draw(Line2D.Float(t[0], t[1], t[2], t[3])); g.draw(Line2D.Float(t[2], t[3], t[4], t[5])); g.draw(Line2D.Float(t[4], t[5], t[0], t[1]))
			}
			g.dispose()
			ImageIO.write(image, "png", File(dir, "pins-$index.png"))
		}
		File(dir, "pins.txt").writeText(notes.toString())
		println(notes)
	}
}
