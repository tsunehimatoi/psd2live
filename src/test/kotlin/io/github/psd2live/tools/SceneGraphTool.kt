package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PlacedRaster
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.umamo.format.moc3.Moc3
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The facts a semantic labelling of Live2D models starts from, one folder per model under
 * build/tools/scene/<PARTS_NAME, scene>/<PARTS_PREFIX, m><index>/:
 *
 * - scene.json: the part tree, deformer tree, parameters (with their groups), physics groups, and for each mesh its
 *   part and deformer, blend mode, masks, render index, rest box, its look (coverage, mean colour, stroke
 *   thickness) and how it answers every parameter: displacement at either end, relative to its size, and opacity;
 * - full.png: the model at rest, the longer side SCENE_SIZE (2048) pixels; meshes/<render index>.png each mesh alone
 *   (its masks applied) in the same frame, placed at the "at" offset its entry records.
 *
 * Models come from PARTS_ROOT (every *.model3.json and *.cmo3), PARTS_THREADS at a time; index.tsv lists them.
 */
class SceneGraphTool {
	private val root = File(setting("PARTS_ROOT", "D:/模型收藏/live2d"))
	private val name = setting("PARTS_NAME", "scene")
	private val prefix = setting("PARTS_PREFIX", "m")
	private val threads = setting("PARTS_THREADS", "1").toInt()
	private val limit = setting("PARTS_LIMIT", "100000").toInt()
	private val size = setting("SCENE_SIZE", "2048").toInt()

	@Test fun scene() {
		requireTools()
		val dir = File(output("scene"), name).apply { mkdirs() }
		val models = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".model3.json") || it.name.endsWith(".cmo3", true)) }
			.sortedBy { it.path }.take(limit).toList()
		val pool = Executors.newFixedThreadPool(threads)
		val lines = models.withIndex().map { (index, file) ->
			pool.submit<String> {
				val key = "$prefix%03d".format(index)
				val status = runCatching { describe(file, File(dir, key)) }.getOrElse { "error: ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
				println("$key $status")
				"$key\t${file.relativeTo(root).path.replace('\\', '/')}\t$status\n"
			}
		}.map { it.get() }
		pool.shutdown()
		File(dir, "index.tsv").writeText("key\tmodel\tstatus\n" + lines.joinToString(""))
	}

	private fun describe(file: File, dir: File): String {
		val (ir, pixels) = loadRig(file).let { it.ir to it.pixels }
		val session = IrGeometryEvaluator.open(ir)
		val base = session.evaluate(emptyMap())
		val order = renderOrder(ir)
		val boxes = ir.meshes.associate { mesh -> mesh.id to base.positions[mesh.id]?.let(::bounds) }

		// How each mesh answers each parameter, at either end with the rest at their defaults.
		val answers = HashMap<String, MutableMap<String, FloatArray>>()
		for (p in ir.parameters) {
			for ((end, value) in listOf(0 to p.min, 1 to p.max)) {
				if (abs(value - p.default) < 1e-4f) continue
				val pose = session.evaluate(mapOf(p.id to value))
				val shift = moved(base, pose)
				for (mesh in ir.meshes) {
					val d = shift[mesh.id] ?: 0f
					val fade = (pose.opacity[mesh.id] ?: 0f) - (base.opacity[mesh.id] ?: 0f)
					if (d < 0.5f && abs(fade) < 0.05f) continue
					// [moved px, relative to size, opacity change] at the min end, then the max end.
					val entry = answers.getOrPut(mesh.id) { LinkedHashMap() }.getOrPut(p.id) { FloatArray(6) }
					val box = boxes[mesh.id]
					entry[end * 3] = d
					entry[end * 3 + 1] = if (box == null) 0f else d / max(1f, max(box[2] - box[0], box[3] - box[1]))
					entry[end * 3 + 2] = fade
				}
			}
		}
		session.close()

		// The model at rest and each mesh alone, in one frame.
		val frame = FrameSpec.canvas(ir, size)
		val scale = frame.outputWidth / frame.width
		val rasterizer = SoftwareRasterizer(ir) { pixels[it] }
		val colors = IrColors(ir)
		val color = colors.at(emptyMap()); val parts = colors.parts(emptyMap())
		dir.mkdirs()
		File(dir, "meshes").mkdirs()
		save(rasterizer.render(base, color, frame, parts = parts).argb, frame.outputWidth, frame.outputHeight, File(dir, "full.png"))
		val ids = ir.meshes.map { it.id }
		val placed = rasterizer.renderMeshes(base, color, frame, ids, parts).let { list -> ids.zip(list).toMap() }

		val partOf = HashMap<String, String>()
		val parentPart = HashMap<String, String>()
		for (part in ir.parts) for (child in part.children) when (child) {
			is ChildRef.MeshRef -> partOf[child.id] = part.id
			is ChildRef.PartRef -> parentPart[child.id] = part.id
		}
		val groupOf = HashMap<String, String>()
		fun walk(nodes: List<ParameterNode>, group: String?) {
			for (node in nodes) when (node) {
				is ParameterNode.Param -> if (group != null) groupOf[node.id] = group
				is ParameterNode.Group -> walk(node.children, node.name)
			}
		}
		walk(ir.parameterTree, null)

		val pendulums = physics(file, ir)
		val json = buildJsonObject {
			put("model", file.relativeTo(root).path.replace('\\', '/'))
			putJsonArray("canvas") { add(ir.canvas.width); add(ir.canvas.height) }
			put("scale", scale)
			putJsonArray("parameters") {
				for (p in ir.parameters) addJsonObject {
					put("id", p.id); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default)
					groupOf[p.id]?.let { put("group", it) }
				}
			}
			putJsonArray("roles") { for (r in ir.parameterRoles) addJsonObject { put("role", r.role); putJsonArray("parameters") { r.parameters.forEach { add(it) } } } }
			putJsonArray("physics") {
				for (g in pendulums) addJsonObject {
					put("id", g.id); put("name", g.name)
					putJsonArray("inputs") { g.inputs.forEach { add(it) } }
					putJsonArray("outputs") { g.outputs.forEach { add(it) } }
				}
			}
			putJsonArray("parts") {
				for (part in ir.parts) addJsonObject {
					put("id", part.id); put("name", part.name); parentPart[part.id]?.let { put("parent", it) }
					put("visible", part.visible); put("order", part.drawOrder)
					if (part.composite.blend != ColorBlend.NORMAL) put("blend", part.composite.blend.name)
					if (part.groupMode != GroupMode.PASS_THROUGH) put("group", part.groupMode.name)
				}
			}
			putJsonArray("deformers") {
				for (d in ir.deformers) addJsonObject {
					put("id", d.id); put("name", d.name); d.parent?.let { put("parent", it) }; d.part?.let { put("part", it) }
					put("kind", if (d is Deformer.Warp) "warp" else "rotation")
				}
			}
			putJsonArray("meshes") {
				for (mesh in ir.meshes) addJsonObject {
					val index = order[mesh.id] ?: -1
					put("id", mesh.id); put("name", mesh.name); put("render", index)
					partOf[mesh.id]?.let { put("part", it) }; mesh.parent?.let { put("deformer", it) }
					if (mesh.blend != ColorBlend.NORMAL) put("blend", mesh.blend.name)
					if (mesh.maskedBy.isNotEmpty()) putJsonArray("masks") { mesh.maskedBy.forEach { add(it) } }
					if (mesh.invertMask) put("invert", true)
					put("opacity", (base.opacity[mesh.id] ?: 0f).round(3))
					boxes[mesh.id]?.let { b -> putJsonArray("box") { b.forEach { add(it.round(1)) } } }
					placed[mesh.id]?.let { raster ->
						save(raster.image.argb, raster.image.width, raster.image.height, File(dir, "meshes/$index.png"))
						putJsonArray("at") { add(raster.left); add(raster.top) }
						put("look", look(raster))
					}
					answers[mesh.id]?.let { a ->
						putJsonObject("answers") { for ((p, v) in a) putJsonArray(p) { v.forEach { add(it.round(3)) } } }
					}
				}
			}
		}
		File(dir, "scene.json").writeText(json.toString())
		return "ok ${ir.meshes.size} meshes, ${ir.parts.size} parts, ${ir.parameters.size} parameters, ${pendulums.size} physics"
	}

	private class Pendulum(val id: String, val name: String, val inputs: List<String>, val outputs: List<String>)

	/** Physics groups with the parameters they read and write: a cmo3 brings its own, a model3 names a physics3.json. */
	private fun physics(file: File, ir: RigIR): List<Pendulum> {
		if (file.name.endsWith(".cmo3", true)) return ir.physics.groups.map { g ->
			Pendulum(g.id, g.name, g.inputs.map { it.parameter }.distinct(), g.outputs.map { it.parameter }.distinct())
		}
		val name = Moc3.readModel3(file.readText()).fileReferences.physics ?: return emptyList()
		val json = File(file.parentFile, name).takeIf { it.isFile }?.let { runCatching { Moc3.readPhysics3(it.readText()) }.getOrNull() } ?: return emptyList()
		val names = json.meta.physicsDictionary.associate { it.id to it.name }
		return json.physicsSettings.map { s ->
			Pendulum(s.id, names[s.id] ?: s.id, s.input.map { it.source.id }.distinct(), s.output.map { it.destination.id }.distinct())
		}
	}

	/** Pixels with any cover, mean straight colour of the solid ones, and stroke thickness (twice area over outline). */
	private fun look(raster: PlacedRaster): JsonObject {
		val w = raster.image.width; val h = raster.image.height; val argb = raster.image.argb
		var covered = 0; var solid = 0; var edge = 0
		var r = 0.0; var g = 0.0; var b = 0.0; var alpha = 0.0
		fun opaque(x: Int, y: Int) = x in 0 until w && y in 0 until h && argb[y * w + x] ushr 24 >= 128
		for (y in 0 until h) for (x in 0 until w) {
			val c = argb[y * w + x]; val a = c ushr 24
			if (a == 0) continue
			covered++; alpha += a / 255.0
			if (a < 128) continue
			solid++
			r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
			if (!opaque(x - 1, y) || !opaque(x + 1, y) || !opaque(x, y - 1) || !opaque(x, y + 1)) edge++
		}
		return buildJsonObject {
			put("covered", covered); put("solid", solid); put("alpha", if (covered == 0) 0f else (alpha / covered).toFloat().round(3))
			if (solid > 0) putJsonArray("rgb") { add((r / solid).roundToInt()); add((g / solid).roundToInt()); add((b / solid).roundToInt()) }
			put("thickness", if (edge == 0) 0f else (2f * solid / edge).round(2))
		}
	}

	private fun bounds(p: FloatArray): FloatArray {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (i in 0 until p.size / 2) { x0 = minOf(x0, p[i * 2]); x1 = maxOf(x1, p[i * 2]); y0 = minOf(y0, p[i * 2 + 1]); y1 = maxOf(y1, p[i * 2 + 1]) }
		return floatArrayOf(x0, y0, x1, y1)
	}

	private fun Float.round(digits: Int): Float { var f = 1f; repeat(digits) { f *= 10f }; return (this * f).roundToInt() / f }

	private fun save(argb: IntArray, width: Int, height: Int, file: File) {
		val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		out.setRGB(0, 0, width, height, argb, 0, width)
		ImageIO.write(out, "png", file)
	}
}
