package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Training pairs for the general split: for every labelled node of every model, a close-up of the model, the
 * node's visible area in it (the hint), and the node alone, whole, in place. Labels come from
 * SCENE_DIR (build/tools/scene/<SCENE_NAME>)/<key>/labels.json (label.py), the key found by model path in its index.tsv.
 *
 * Nodes: each tag (paired ones split by side of the face) and, within a tag, each piece one deformer moves when
 * the tag has several (a strand of hair, one ornament). A node's meshes are drawn alone with the clips that move
 * with them (shading on its part); a clip that moves against them (an eye white round an iris) is a window, and
 * the node is drawn whole without it.
 *
 * SUBNODES, when set, is a jsonl of finer nodes named by a reviewer ({"model", "node", "tag", "prompt", "meshes"}: a
 * skirt's lace trim, a sleeve cuff); up to SUBNODES_PER_MODEL of them are drawn like the others, their prompt carried
 * into samples.jsonl.
 *
 * Writes build/tools/nodes/<PARTS_NAME, nodes>/<key>/<node>_<variant>_{input,hint,target}.png and samples.jsonl.
 */
class NodeDatasetTool {
	private val root = File(setting("PARTS_ROOT", "D:/模型收藏/live2d"))
	private val name = setting("PARTS_NAME", "nodes")
	private val sceneName = setting("SCENE_NAME", "scene")
	private val prefix = setting("PARTS_PREFIX", "m")
	private val threads = setting("PARTS_THREADS", "1").toInt()
	private val limit = setting("PARTS_LIMIT", "100000").toInt()
	private val size = setting("PARTS_SIZE", "1024").toInt()
	private val variants = setting("PARTS_VARIANTS", "2").toInt()
	/** Nodes per model at most, rare tags first, so a model with forty strands does not drown the rest. */
	private val perModel = setting("NODES_PER_MODEL", "40").toInt()
	private val subPerModel = setting("SUBNODES_PER_MODEL", "20").toInt()
	/** The labels file in each scene folder (describe_labels.py writes labels_described.json); labels.json without it. */
	private val labelsName = setting("PARTS_LABELS", "labels.json")
	/** Close-ups narrower than this many texels take in more of the surroundings instead of being dropped (0: off). */
	private val minFrame = setting("PARTS_MIN_FRAME", "0").toInt()

	private val paired = setOf("irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg",
		"handwear", "legwear", "footwear")
	private val pieced = setOf("front_hair", "side_hair", "back_hair", "headwear", "objects", "neckwear", "topwear", "bottomwear", "tail", "wings")
	private val skipped = setOf("unknown", "background", "effect")

	@Test fun nodes() {
		requireTools()
		val out = File(output("nodes"), name).apply { mkdirs() }
		val scenes = File(setting("SCENE_DIR", File(output("scene"), sceneName).path))
		// Labels by model path (index.tsv), so a scene made on another machine, which may list files in another
		// order, still matches.
		val keyOf = File(scenes, "index.tsv").takeIf { it.isFile }?.readLines()?.drop(1)?.mapNotNull { line ->
			line.split('\t').takeIf { it.size >= 2 }?.let { it[1] to it[0] } }?.toMap().orEmpty()
		val subnodes = System.getenv("SUBNODES")?.let { File(it) }?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
			?.map { Json.parseToJsonElement(it).jsonObject }?.groupBy { it["model"]!!.jsonPrimitive.content }.orEmpty()
		val models = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".model3.json") || it.name.endsWith(".cmo3", true)) }
			.sortedBy { it.path }.take(limit).toList()
		val pool = Executors.newFixedThreadPool(threads)
		val results = models.withIndex().map { (index, file) ->
			pool.submit<Pair<String, String>> {
				val key = "$prefix%03d".format(index)
				val samples = StringBuilder()
				val model = file.relativeTo(root).path.replace('\\', '/')
				val folder = File(scenes, keyOf[model] ?: key)
				val labels = File(folder, labelsName).takeIf { it.isFile } ?: File(folder, "labels.json")
				val status = if (!labels.isFile) "no labels" else
					runCatching { render(file, labels, File(out, key), key, samples, subnodes[model].orEmpty()) }.getOrElse { "error: ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
				println("$key $status")
				"$key\t${file.relativeTo(root).path.replace('\\', '/')}\t$status\n" to samples.toString()
			}
		}.map { it.get() }
		pool.shutdown()
		File(out, "index.tsv").writeText("key\tmodel\tstatus\n" + results.joinToString("") { it.first })
		File(out, "samples.jsonl").writeText(results.joinToString("") { it.second })
	}

	private class Node(val id: String, val tag: String, val side: String, val piece: Int, val pieces: Int, val meshes: Set<String>,
		val prompt: String? = null)

	private fun render(file: File, labelFile: File, dir: File, key: String, samples: StringBuilder, subnodes: List<JsonObject>): String {
		val (ir, pixels) = loadRig(file).let { it.ir to it.pixels }
		val labelDoc = Json.parseToJsonElement(labelFile.readText()).jsonObject
		val labels = labelDoc["labels"]!!.jsonObject
		// Nodes grouped by the reviewer's own per-mesh tags need no node review.
		val described = labelDoc["source"]?.jsonPrimitive?.content == "describe"
		val tagOf = HashMap<String, String>()
		for ((id, value) in labels) {
			val lab = value.jsonObject
			val confidence = lab["confidence"]!!.jsonPrimitive.float
			val tag = lab["tag"]!!.jsonPrimitive.content
			if (confidence >= 0.3f && tag !in skipped) tagOf[id] = tag
		}
		val session = IrGeometryEvaluator.open(ir)
		val base = session.evaluate(emptyMap())
		val visible = base.opacity.filter { it.value > 0.05f }.keys
		val meshes = ir.meshes.associateBy { it.id }
		// Cubism's skinning cuts one drawn layer into segments, one per bone, under a part named "<layer>_Skinning":
		// the segments of one such part are one piece.
		val skinOf = HashMap<String, String>()
		for (part in ir.parts) if (part.name.endsWith("_Skinning", ignoreCase = true))
			for (child in part.children) if (child is ChildRef.MeshRef) skinOf[child.id] = part.id
		fun box(ids: Collection<String>): FloatArray {
			var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
			for (id in ids) base.positions[id]?.let { p -> for (i in 0 until p.size / 2) { x0 = min(x0, p[i * 2]); x1 = max(x1, p[i * 2]); y0 = min(y0, p[i * 2 + 1]); y1 = max(y1, p[i * 2 + 1]) } }
			return floatArrayOf(x0, y0, x1, y1)
		}
		val faceIds = tagOf.filterValues { it == "face" }.keys.filter { it in visible }
		val centre = if (faceIds.isNotEmpty()) box(faceIds).let { (it[0] + it[2]) / 2 } else ir.canvas.width / 2

		// Nodes: tags, paired ones by side; pieces of a tag by deformer when it has several.
		val nodes = ArrayList<Node>()
		val byTag = tagOf.filterKeys { it in visible }.entries.groupBy({ it.value }, { it.key })
		for ((tag, ids) in byTag) {
			val sides = if (tag in paired) ids.groupBy { id -> box(listOf(id)).let { if ((it[0] + it[2]) / 2 < centre) "l" else "r" } }
				else mapOf("c" to ids)
			for ((side, sideIds) in sides) {
				nodes += Node("${tag}_$side", tag, side, 0, 1, sideIds.toSet())
				if (tag in pieced) {
					val pieces = sideIds.groupBy { skinOf[it] ?: meshes[it]?.parent ?: it }.values.filter { piece ->
						box(piece).let { b -> (b[2] - b[0]) * (b[3] - b[1]) > 0 } }
					if (pieces.size >= 2) pieces.sortedBy { box(it)[0] }.forEachIndexed { i, piece ->
						nodes += Node("${tag}_${side}_p$i", tag, side, i + 1, pieces.size, piece.toSet())
					}
				}
			}
		}
		// Rare tags first; within a tag, whole nodes before pieces.
		val tagCount = nodes.groupingBy { it.tag }.eachCount()
		val taken = nodes.map { it.meshes }.toSet()
		val finer = subnodes.mapNotNull { j ->
			val ids = j["meshes"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it in visible }.toSet()
			if (ids.isEmpty() || ids in taken) null
			else Node(j["node"]!!.jsonPrimitive.content, j["tag"]!!.jsonPrimitive.content, "c", 0, 1, ids, j["prompt"]!!.jsonPrimitive.content)
		}.take(subPerModel)
		val chosen = nodes.sortedWith(compareBy({ it.piece > 0 }, { tagCount[it.tag] ?: 0 })).take(perModel) + finer

		// A clip is a window when some parameter moves the clipped mesh and its mask apart.
		val windows = HashSet<Pair<String, String>>()
		val clipped = ir.meshes.filter { it.maskedBy.isNotEmpty() }
		if (clipped.isNotEmpty()) for (p in ir.parameters) for (value in listOf(p.min, p.max)) {
			if (abs(value - p.default) < 1e-4f) continue
			val pose = session.evaluate(mapOf(p.id to value))
			for (mesh in clipped) for (mask in mesh.maskedBy) {
				if (mesh.id to mask in windows) continue
				if (relativeShift(base, pose, mesh.id, mask) > 0.04f) windows += mesh.id to mask
			}
		}

		val rasterizer = SoftwareRasterizer(ir) { pixels[it] }
		val colors = IrColors(ir)
		val random = Random(file.path.hashCode())
		val standard = Regex("(?i)(angle|eyeball|eye_ball|eye.?open|mouth|breath|hair|skirt|ribbon|bust|body)")
		var written = 0
		dir.mkdirs()
		for (node in chosen) {
			// The node drawn whole: clips moving against it are dropped for its meshes.
			val dropped = node.meshes.filter { id -> meshes[id]?.maskedBy?.any { m -> m !in node.meshes && (id to m) in windows } == true }.toSet()
			val target = if (dropped.isEmpty()) rasterizer else SoftwareRasterizer(ir.copy(meshes = ir.meshes.map { m ->
				if (m.id in dropped) m.copy(maskedBy = m.maskedBy.filter { (m.id to it) !in windows }) else m })) { pixels[it] }
			val region = box(node.meshes)
			val density = texelDensity(ir, base, node.meshes)
			for (v in 0 until variants) {
				val first = v == 0
				val values = if (first) emptyMap() else ir.parameters.filter { standard.containsMatchIn(it.id) }.associate { p ->
					val span = (p.max - p.min) * 0.35f
					p.id to (p.default + random.nextDouble(-1.0, 1.0).toFloat() * span).coerceIn(p.min, p.max)
				}
				val pose = if (first) base else session.evaluate(values)
				val nodeBox = if (first) region else posedBox(pose, node.meshes)
				var span = max(nodeBox[2] - nodeBox[0], nodeBox[3] - nodeBox[1]) * (if (first) 1.3f else random.nextDouble(1.15, 2.0).toFloat()) + 8f
				if (minFrame > 0 && density > 0f && span * density < minFrame) span = minFrame / density
				val shift = if (first) 0f else span * 0.08f
				val cx = (nodeBox[0] + nodeBox[2]) / 2 + random.nextDouble(-1.0, 1.0).toFloat() * shift
				val cy = (nodeBox[1] + nodeBox[3]) / 2 + random.nextDouble(-1.0, 1.0).toFloat() * shift
				val px = min(size, (span * density).toInt()).coerceAtLeast(64)
				if (px < 96) continue
				val frame = FrameSpec(cx - span / 2, cy - span / 2, span, span, px, px)
				val color = colors.at(values); val parts = colors.parts(values)
				val input = rasterizer.render(pose, color, frame, parts = parts)
				val others = rasterizer.render(pose, color, frame, only = (visible - node.meshes), parts = parts)
				val alone = target.render(pose, color, frame, only = node.meshes, parts = parts)
				val hint = difference(input, others)
				if (coverage(alone) < 0.002f) continue
				val stem = "${node.id}_$v"
				save(input, File(dir, "${stem}_input.png"))
				save(hint, File(dir, "${stem}_hint.png"))
				save(alone, File(dir, "${stem}_target.png"))
				samples.append("""{"key":"$key","model":"${file.relativeTo(root).path.replace("\\", "/")}","node":"${node.id}","tag":"${node.tag}",""" +
					""""side":"${node.side}","piece":${node.piece},"pieces":${node.pieces},"variant":$v,"pixels":$px,"meshes":${node.meshes.size},""" +
					""""windows":${dropped.size},"visible":${coverage(hint, white = true)},"area":${coverage(alone)}""" +
					(node.prompt?.let { ""","prompt":${Json.encodeToString(String.serializer(), it)}""" } ?: "") +
					(if (described) ""","described":true""" else "") + "}").append('\n')
				written++
			}
		}
		session.close()
		return "ok ${chosen.size} nodes, $written samples"
	}

	/** How far [mesh] moves relative to [mask] between two poses, for the mask's size. */
	private fun relativeShift(a: PoseGeometry, b: PoseGeometry, mesh: String, mask: String): Float {
		val pa = a.positions[mesh] ?: return 0f; val pb = b.positions[mesh] ?: return 0f
		val ma = a.positions[mask] ?: return 0f; val mb = b.positions[mask] ?: return 0f
		fun centre(p: FloatArray): Pair<Float, Float> { var x = 0f; var y = 0f; val n = p.size / 2; for (i in 0 until n) { x += p[i * 2]; y += p[i * 2 + 1] }; return x / n to y / n }
		val (ax, ay) = centre(pa); val (bx, by) = centre(pb); val (cx, cy) = centre(ma); val (dx, dy) = centre(mb)
		val rel = kotlin.math.hypot(((bx - ax) - (dx - cx)).toDouble(), ((by - ay) - (dy - cy)).toDouble()).toFloat()
		var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (i in 0 until ma.size / 2) { x0 = min(x0, ma[i * 2]); x1 = max(x1, ma[i * 2]); y0 = min(y0, ma[i * 2 + 1]); y1 = max(y1, ma[i * 2 + 1]) }
		return rel / max(1f, max(x1 - x0, y1 - y0))
	}

	private fun posedBox(pose: PoseGeometry, ids: Collection<String>): FloatArray {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (id in ids) pose.positions[id]?.let { p -> for (i in 0 until p.size / 2) { x0 = min(x0, p[i * 2]); x1 = max(x1, p[i * 2]); y0 = min(y0, p[i * 2 + 1]); y1 = max(y1, p[i * 2 + 1]) } }
		return floatArrayOf(x0, y0, x1, y1)
	}

	/** Texels per canvas unit over [ids]: frames render at this density at most, never enlarging the art. */
	private fun texelDensity(ir: RigIR, pose: PoseGeometry, ids: Set<String>): Float {
		var uvArea = 0.0; var posedArea = 0.0
		for (mesh in ir.meshes) {
			if (mesh.id !in ids) continue
			val g = mesh.geometry ?: continue
			val page = ir.textures.pages.getOrNull(ir.textures.bindings[mesh.id] ?: mesh.page) ?: continue
			val uv = g.uvs.shared(); val p = pose.positions[mesh.id] ?: continue; val idx = g.indices.shared()
			for (t in 0 until idx.size / 3) {
				val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
				uvArea += abs(tri(uv[a * 2] * page.width, uv[a * 2 + 1] * page.height, uv[b * 2] * page.width, uv[b * 2 + 1] * page.height, uv[c * 2] * page.width, uv[c * 2 + 1] * page.height))
				posedArea += abs(tri(p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1]))
			}
		}
		return if (posedArea <= 0.0) 1f else kotlin.math.sqrt(uvArea / posedArea).toFloat()
	}

	private fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0

	/** White where the two renders differ: the node's visible pixels. */
	private fun difference(a: RasterImage, b: RasterImage): RasterImage {
		val out = IntArray(a.argb.size)
		for (i in out.indices) {
			val x = a.argb[i]; val y = b.argb[i]
			var d = 0
			for (s in intArrayOf(0, 8, 16, 24)) d = max(d, abs(((x ushr s) and 255) - ((y ushr s) and 255)))
			out[i] = if (d > 24) -1 else 0xFF000000.toInt()
		}
		return RasterImage(a.width, a.height, out)
	}

	/** Share of pixels with real cover (a target), or white (a hint). */
	private fun coverage(image: RasterImage, white: Boolean = false): Float {
		var n = 0
		for (c in image.argb) if (if (white) c == -1 else (c ushr 24) > 32) n++
		return n.toFloat() / image.argb.size
	}

	private fun save(image: RasterImage, file: File) {
		val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
		out.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
		ImageIO.write(out, "png", file)
	}
}
