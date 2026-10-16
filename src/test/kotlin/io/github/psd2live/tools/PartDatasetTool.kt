package io.github.psd2live.tools

import io.github.psd2live.core.AtlasBudget
import io.github.psd2live.core.Cmo3ImportMode
import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Training pairs for a part-extraction model, drawn from Live2D runtime models: for each eye, a close-up of the
 * whole model and, in the same frame, each part alone (the iris without its clipping mask, so whole). Parts are
 * found by how meshes answer the standard parameters (eye ball, eye open, brow), not by their names.
 *
 * Reads every *.model3.json and *.cmo3 under PARTS_ROOT (a cmo3 renders from its own layer art, at the resolution it
 * was authored) and writes build/tools/part-dataset/<PARTS_NAME, eyes>/: probe.tsv (one
 * line per model) and <PARTS_PREFIX, m><index>/<side>_<variant>_<input|iris|white|lash|brow>.png (transparent, the
 * frame of the close-up), with samples.jsonl describing each close-up.
 *
 * PSD2LIVE_TOOLS=1 PARTS_ROOT=D:/models ./gradlew test --tests '*PartDatasetTool*' -Ppsd2live.testHeap=8g
 */
class PartDatasetTool {
	private val root = File(setting("PARTS_ROOT", "D:/模型收藏/live2d"))
	private val size = setting("PARTS_SIZE", "768").toInt()
	private val variants = setting("PARTS_VARIANTS", "3").toInt()
	private val limit = setting("PARTS_LIMIT", "100000").toInt()
	/** Output folder under part-dataset/ and the prefix of model keys, so several collections can be rendered apart. */
	private val name = setting("PARTS_NAME", "eyes")
	private val prefix = setting("PARTS_PREFIX", "m")
	private val threads = setting("PARTS_THREADS", "1").toInt()

	@Test fun eyes() {
		requireTools()
		val out = output("part-dataset")
		val eyesDir = File(out, name).apply { mkdirs() }
		val models = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".model3.json") || it.name.endsWith(".cmo3", true)) }
			.sortedBy { it.path }.take(limit).toList()
		renderAll(models, eyesDir, "meshes\tball\topen\tbrow\teyes\tstatus", ::renderModel)
	}

	/**
	 * Mouth and hair pairs. A mouth close-up with its lips and lines, and its inside (teeth, tongue, the mouth's own
	 * colour) whole, unclipped like the iris; a head shot with the front, side and back hair, each whole. The mouth is
	 * what answers the mouth parameters, the hair what answers parameters whose ids hold HairFront, HairSide, HairBack.
	 * Writes part-dataset/<PARTS_NAME, head>/<key>/c_<m|h><variant>_<input|lips|inside|front|side|back>.png.
	 */
	@Test fun head() {
		requireTools()
		val dir = File(output("part-dataset"), setting("PARTS_NAME", "head")).apply { mkdirs() }
		val models = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".model3.json") || it.name.endsWith(".cmo3", true)) }
			.sortedBy { it.path }.take(limit).toList()
		renderAll(models, dir, "mouth\thair\tstatus", ::renderHead)
	}

	/** Models render on PARTS_THREADS threads; each keeps its own lines, written back in model order. */
	private fun renderAll(models: List<File>, dir: File, columns: String, render: (File, File, String, StringBuilder) -> String) {
		val pool = Executors.newFixedThreadPool(threads)
		val results = models.withIndex().map { (index, file) ->
			pool.submit<Pair<String, String>> {
				val key = "$prefix%03d".format(index)
				val samples = StringBuilder()
				val line = runCatching { render(file, File(dir, key), key, samples) }
					.getOrElse { "error: ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
				println("$key ${file.relativeTo(root).path}: $line")
				"$index\t${file.relativeTo(root).path}\t$line\n" to samples.toString()
			}
		}.map { it.get() }
		pool.shutdown()
		File(dir, "probe.tsv").writeText("index\tpath\t$columns\n" + results.joinToString("") { it.first })
		File(dir, "samples.jsonl").writeText(results.joinToString("") { it.second })
	}

	/** Parameter ids, part names and mesh names (with what masks them) of PARTS_DUMP, for finding parts by name. */
	@Test fun dump() {
		requireTools()
		val file = File(setting("PARTS_DUMP", ""))
		if (!file.isFile) return
		val ir = loadRig(file).ir
		val text = StringBuilder()
		text.append("parameters: ").append(ir.parameters.joinToString { "${it.id}(${it.name})" }).append('\n')
		val parts = ir.parts.associateBy { it.id }
		for (mesh in ir.meshes) text.append("${mesh.id}\t${mesh.name}\tpart=${mesh.parent?.let { parts[it]?.name ?: it }}\tmasks=${mesh.maskedBy}\n")
		File(output("part-dataset"), "dump.txt").writeText(text.toString())
	}

	/** For PARTS_DUMP: every visible mesh near something that follows the eye ball, with its order and responses. */
	@Test fun debugEyes() {
		requireTools()
		val file = File(setting("PARTS_DUMP", ""))
		if (!file.isFile) return
		val ir = loadRig(file).ir
		val ballX = param(ir, "ParamEyeBallX", "PARAM_EYE_BALL_X")!!
		val openL = param(ir, "ParamEyeLOpen", "PARAM_EYE_L_OPEN")!!
		val openR = param(ir, "ParamEyeROpen", "PARAM_EYE_R_OPEN")!!
		val session = IrGeometryEvaluator.open(ir)
		val base = session.evaluate(emptyMap())
		val ball = moved(base, session.evaluate(mapOf(ballX.id to ballX.max)))
		val close = moved(base, session.evaluate(mapOf(openL.id to openL.min, openR.id to openR.min)))
		val order = renderOrder(ir)
		val iris = base.positions.keys.filter { (ball[it] ?: 0f) > 1f && (base.opacity[it] ?: 0f) > 0.05f }
		val irisBox = box(base, iris)
		val text = StringBuilder("iris ${iris.size}: $iris\n")
		for (mesh in ir.meshes) {
			val b = box(base, listOf(mesh.id))
			if (b.empty || abs(b.cy - irisBox.cy) > irisBox.h * 2 || b.cx < irisBox.x0 - irisBox.w * 0.5f || b.cx > irisBox.x1 + irisBox.w * 0.5f) continue
			text.append("${mesh.id}\t${mesh.name}\torder=${base.drawOrder[mesh.id]}\trender=${order[mesh.id]}\topacity=${base.opacity[mesh.id]}\t" +
				"ball=${ball[mesh.id]}\tclose=${close[mesh.id]}\tsize=${b.w.toInt()}x${b.h.toInt()}\tmasks=${mesh.maskedBy}\n")
		}
		File(output("part-dataset"), "debug_eyes.txt").writeText(text.toString())
	}

	private class Box(var x0: Float = Float.MAX_VALUE, var y0: Float = Float.MAX_VALUE, var x1: Float = -Float.MAX_VALUE, var y1: Float = -Float.MAX_VALUE) {
		fun add(p: FloatArray) { for (i in 0 until p.size / 2) { x0 = min(x0, p[i * 2]); x1 = max(x1, p[i * 2]); y0 = min(y0, p[i * 2 + 1]); y1 = max(y1, p[i * 2 + 1]) } }
		val w get() = x1 - x0
		val h get() = y1 - y0
		val cx get() = (x0 + x1) / 2
		val cy get() = (y0 + y1) / 2
		val empty get() = x1 < x0
		/** This box scaled by [factor] about its centre. */
		fun grown(factor: Float) = Box(cx - w * factor / 2, cy - h * factor / 2, cx + w * factor / 2, cy + h * factor / 2)
	}

	private fun box(pose: PoseGeometry, ids: Collection<String>) = Box().apply { ids.forEach { id -> pose.positions[id]?.let(::add) } }

	/** The first of [ids] the rig has; failing that, a parameter whose id holds one of them (ParamLookEyeBallX). */
	private fun param(ir: RigIR, vararg ids: String) = ids.firstNotNullOfOrNull { id -> ir.parameters.firstOrNull { it.id.equals(id, ignoreCase = true) } }
		?: ids.firstNotNullOfOrNull { id -> ir.parameters.firstOrNull { it.id.contains(id.removePrefix("Param"), ignoreCase = true) } }

	private fun renderModel(file: File, dir: File, key: String, samples: StringBuilder): String {
		val (ir, pixels) = loadRig(file).let { it.ir to it.pixels }
		val ballX = param(ir, "ParamEyeBallX", "PARAM_EYE_BALL_X")
		val ballY = param(ir, "ParamEyeBallY", "PARAM_EYE_BALL_Y")
		val openL = param(ir, "ParamEyeLOpen", "PARAM_EYE_L_OPEN")
		val openR = param(ir, "ParamEyeROpen", "PARAM_EYE_R_OPEN")
		val browL = listOf("ParamBrowLY", "PARAM_BROW_L_Y", "ParamBrowLForm", "ParamBrowLAngle").mapNotNull { param(ir, it) }
		val browR = listOf("ParamBrowRY", "PARAM_BROW_R_Y", "ParamBrowRForm", "ParamBrowRAngle").mapNotNull { param(ir, it) }
		val head = "${ir.meshes.size}\t${ballX != null && ballY != null}\t${openL != null && openR != null}\t${browL.isNotEmpty() && browR.isNotEmpty()}"
		if (ballX == null || ballY == null || openL == null || openR == null) return "$head\t0\tmissing eye parameters"
		val session = IrGeometryEvaluator.open(ir)
		val base = session.evaluate(emptyMap())
		val visible = base.opacity.filter { it.value > 0.05f }.keys
		fun response(values: Map<String, Float>) = moved(base, session.evaluate(values))
		val ball = listOf(mapOf(ballX.id to ballX.max), mapOf(ballX.id to ballX.min), mapOf(ballY.id to ballY.max), mapOf(ballY.id to ballY.min))
			.map(::response).reduce { a, b -> a.mapValues { (k, v) -> max(v, b[k] ?: 0f) } }
		val closeL = response(mapOf(openL.id to openL.min))
		val closeR = response(mapOf(openR.id to openR.min))
		val brows = (browL + browR).associateWith { p -> listOf(response(mapOf(p.id to p.max)), response(mapOf(p.id to p.min))).reduce { a, b -> a.mapValues { (k, v) -> max(v, b[k] ?: 0f) } } }
		fun relative(id: String, move: Float): Float { val b = box(base, listOf(id)); return move / max(1f, max(b.w, b.h)) }
		// Iris, pupil and highlights: whatever follows the eye ball, and follows it closely: some rigs let the lids
		// and lashes trail the eye ball a little, and those belong to the lids.
		val moving = visible.filter { id -> val m = ball[id] ?: 0f; m > 1f && relative(id, m) > 0.06f }
		val fastest = moving.maxOfOrNull { ball[it] ?: 0f } ?: 0f
		val followers = moving.filter { (ball[it] ?: 0f) >= fastest * 0.25f }
		if (followers.isEmpty()) return "$head\t0\tno mesh follows the eye ball"
		// One eye's iris, pupil and highlights overlap; followers that touch, transitively, are one eye. Rigs also let
		// decorative eyes follow the eye ball (on a hood, a mascot, a hair clip), and those have no lids: an eye is a
		// group with lids around it that close with eye-open, and a face has at most two.
		val groups = followers.associateWith { mutableSetOf(it) }.toMutableMap()
		for ((i, a) in followers.withIndex()) for (b in followers.drop(i + 1)) {
			val p = box(base, listOf(a)).grown(1.25f); val q = box(base, listOf(b)).grown(1.25f)
			if (p.x0 <= q.x1 && q.x0 <= p.x1 && p.y0 <= q.y1 && q.y0 <= p.y1 && groups[a] !== groups[b]) {
				val merged = groups.getValue(a).apply { addAll(groups.getValue(b)) }
				merged.forEach { groups[it] = merged }
			}
		}
		val scored = groups.values.distinctBy { System.identityHashCode(it) }.map { group ->
			val strongest = group.maxOf { ball[it] ?: 0f }
			val eyeBall = group.filter { (ball[it] ?: 0f) >= strongest * 0.5f }.sortedBy { box(base, listOf(it)).cx }
			val b = box(base, eyeBall)
			val lids = visible.filter { id -> id !in eyeBall && box(base, listOf(id)).let { abs(it.cx - b.cx) < b.w * 1.5f + 4f && abs(it.cy - b.cy) < b.h * 2f + 4f } }
				.sumOf { max(closeL[it] ?: 0f, closeR[it] ?: 0f).toDouble() }
			eyeBall to lids
		}
		// Rigs that blink by swapping closed-eye art in move no lids; with at most two groups, those are the eyes.
		val best = scored.maxOf { it.second }
		val clusters = (if (best <= 2.0 && scored.size <= 2) scored else scored.filter { it.second > 2.0 && it.second >= best * 0.25 })
			.sortedByDescending { it.second }.take(2).map { it.first }.sortedBy { box(base, it).cx }
		if (clusters.isEmpty()) return "$head\t0\tno eye ball with lids around it"
		val iris = clusters.flatten()
		val meshes = ir.meshes.associateBy { it.id }
		val unmaskedIr = ir.copy(meshes = ir.meshes.map { if (it.id in iris) it.copy(maskedBy = emptyList()) else it })
		val rasterizer = SoftwareRasterizer(ir) { pixels[it] }
		val unmasked = SoftwareRasterizer(unmaskedIr) { pixels[it] }
		val colors = IrColors(ir)
		val random = Random(file.path.hashCode())
		var written = 0
		val order = renderOrder(ir)
		for (eyeBall in clusters) {
			val irisBox = box(base, eyeBall)
			val irisBottom = eyeBall.minOf { order[it] ?: 0 }
			val irisTop = eyeBall.maxOf { order[it] ?: 0 }
			fun sized(id: String, minW: Float, maxW: Float, maxH: Float) = box(base, listOf(id)).let { b ->
				abs(b.cx - irisBox.cx) < irisBox.w * 0.8f && abs(b.cy - irisBox.cy) < irisBox.h * 0.8f &&
					b.w >= irisBox.w * minW && b.w <= irisBox.w * maxW && b.h <= irisBox.h * maxH }
			// Highlights some models keep still while the iris moves: small, drawn above the iris and lying inside it
			// (eyeliner and lash tips above the iris stick out of it, or close with the lids).
			fun inside(id: String) = box(base, listOf(id)).let { b ->
				b.x0 >= irisBox.x0 - irisBox.w * 0.1f && b.x1 <= irisBox.x1 + irisBox.w * 0.1f &&
					b.y0 >= irisBox.y0 - irisBox.h * 0.1f && b.y1 <= irisBox.y1 + irisBox.h * 0.1f }
			val highlights = visible.filter { id -> id !in iris && (order[id] ?: 0) > irisTop && (ball[id] ?: 0f) <= 1f && sized(id, 0f, 0.5f, 0.5f) && inside(id) &&
				max(closeL[id] ?: 0f, closeR[id] ?: 0f) <= 1f }
			val cluster = eyeBall + highlights
			// The eye white clips the iris; models that do not clip stack the lids over the iris instead, and their
			// eye white is drawn right under the iris, a little larger than it.
			// A mask much larger than the iris is the face or skin clipping it, not the eye white.
			val white = eyeBall.flatMap { meshes[it]?.maskedBy.orEmpty() }.toSet().filter { it !in iris && it in visible && sized(it, 0.9f, 3.5f, 3f) }.ifEmpty {
				visible.filter { id -> id !in iris && (order[id] ?: 0) < irisBottom && (ball[id] ?: 0f) <= 1f && sized(id, 0.9f, 3.5f, 3f) }
					.sortedByDescending { order[it] ?: 0 }.take(2)
			}
			if (white.isEmpty()) continue
			val whiteBox = box(base, white)
			// The eye-open parameter that moves this eye's lids most.
			val near = { id: String -> val b = box(base, listOf(id)); abs(b.cx - whiteBox.cx) < whiteBox.w * 1.2f && abs(b.cy - whiteBox.cy) < whiteBox.h * 1.6f + 4f }
			val lidL = visible.filter { it !in iris && it !in white && near(it) }.sumOf { (closeL[it] ?: 0f).toDouble() }
			val lidR = visible.filter { it !in iris && it !in white && near(it) }.sumOf { (closeR[it] ?: 0f).toDouble() }
			val close = if (lidL >= lidR) closeL else closeR
			val side = if (lidL >= lidR) "l" else "r"
			val lash = visible.filter { it !in iris && it !in cluster && it !in white && near(it) && (close[it] ?: 0f) > 1f && relative(it, close[it] ?: 0f) > 0.04f }
			val sideBrows = (if (side == "l") browL else browR)
			val brow = visible.filter { id -> id !in iris && id !in cluster && id !in white && id !in lash && sideBrows.any { (brows[it]?.get(id) ?: 0f) > 1f } &&
				box(base, listOf(id)).let { b -> b.cy < whiteBox.cy && abs(b.cx - whiteBox.cx) < whiteBox.w * 1.5f } }
			val density = texels(ir, base, cluster + white)
			// Eye close-ups frame the eye ball and its white, so the iris fills a good part of the picture; the brow
			// gets close-ups of its own that take in the eye below it.
			val eyeRegion = box(base, cluster + white)
			val browRegion = box(base, white + brow)
			val framings = (0 until variants).map { "$it" to eyeRegion } +
				(if (brow.isEmpty()) emptyList() else (0 until (variants + 1) / 2).map { "b$it" to browRegion })
			for ((v, region) in framings) {
				val first = v == "0" || v == "b0"
				// Each close-up frames its region a little differently; it renders at the texture's own density (at
				// most [size] pixels), so the parts are never enlarged past what the artist painted.
				val span = max(region.w, region.h) * (if (first) 1.6f else random.nextDouble(1.4, 2.0).toFloat())
				val shift = if (first) 0f else span * 0.08f
				val cx = region.cx + random.nextDouble(-1.0, 1.0).toFloat() * shift
				val cy = region.cy + random.nextDouble(-1.0, 1.0).toFloat() * shift
				val pixels = min(size, (span * density).toInt()).coerceAtLeast(64)
				val frame = FrameSpec(cx - span / 2, cy - span / 2, span, span, pixels, pixels)
				val upsample = (pixels / span) / max(1e-3f, density)
				val values = if (first) emptyMap() else mapOf(
					ballX.id to random.nextDouble(-0.7, 0.7).toFloat() * ballX.max,
					ballY.id to random.nextDouble(-0.6, 0.4).toFloat() * ballY.max,
					openL.id to random.nextDouble(0.85, 1.0).toFloat() * openL.max,
					openR.id to random.nextDouble(0.85, 1.0).toFloat() * openR.max)
				val pose = session.evaluate(values)
				val color = colors.at(values); val parts = colors.parts(values)
				val name = "${side}_$v"
				dir.mkdirs()
				save(rasterizer.render(pose, color, frame, parts = parts), File(dir, "${name}_input.png"))
				if (region === eyeRegion) {
					save(unmasked.render(pose, color, frame, only = cluster.toSet(), parts = parts), File(dir, "${name}_iris.png"))
					save(rasterizer.render(pose, color, frame, only = white.toSet(), parts = parts), File(dir, "${name}_white.png"))
					if (lash.isNotEmpty()) save(rasterizer.render(pose, color, frame, only = lash.toSet(), parts = parts), File(dir, "${name}_lash.png"))
				} else {
					save(rasterizer.render(pose, color, frame, only = brow.toSet(), parts = parts), File(dir, "${name}_brow.png"))
				}
				samples.append("""{"key":"$key","model":"${file.relativeTo(root).path.replace("\\", "/")}","side":"$side","variant":"$v",""" +
					""""span":$span,"pixels":$pixels,"upsample":$upsample,"iris":${cluster.size},"white":${white.size},"lash":${lash.size},"brow":${brow.size}}""").append('\n')
				written++
			}
		}
		session.close()
		return "$head\t${clusters.size}\t${if (written == 0) "no eye white found" else "ok $written"}"
	}

	private fun renderHead(file: File, dir: File, key: String, samples: StringBuilder): String {
		val (ir, pixels) = loadRig(file).let { it.ir to it.pixels }
		val session = IrGeometryEvaluator.open(ir)
		val base = session.evaluate(emptyMap())
		val visible = base.opacity.filter { it.value > 0.05f }.keys
		val meshes = ir.meshes.associateBy { it.id }
		fun response(values: Map<String, Float>) = moved(base, session.evaluate(values))
		/** Each mesh's largest displacement over both ends of every one of [params]. */
		fun swing(params: List<Parameter>): Map<String, Float> = params.flatMap { p -> listOf(response(mapOf(p.id to p.max)), response(mapOf(p.id to p.min))) }
			.fold(emptyMap()) { a, b -> (a.keys + b.keys).associateWith { k -> max(a[k] ?: 0f, b[k] ?: 0f) } }
		fun relative(id: String, move: Float): Float { val b = box(base, listOf(id)); return move / max(1f, max(b.w, b.h)) }
		val colors = IrColors(ir)
		val random = Random(file.path.hashCode())
		val rasterizer = SoftwareRasterizer(ir) { pixels[it] }
		var mouthNote = "-"; var hairNote = "-"
		dir.mkdirs()

		fun write(v: String, region: Box, density: Float, scale: () -> Float, values: () -> Map<String, Float>,
				  parts: Map<String, Pair<SoftwareRasterizer, Set<String>>>, counts: String) {
			val first = v.endsWith("0")
			val span = max(region.w, region.h) * (if (first) 1f else scale())
			val shift = if (first) 0f else span * 0.06f
			val cx = region.cx + random.nextDouble(-1.0, 1.0).toFloat() * shift
			val cy = region.cy + random.nextDouble(-1.0, 1.0).toFloat() * shift
			val size = min(size, (span * density).toInt()).coerceAtLeast(64)
			val frame = FrameSpec(cx - span / 2, cy - span / 2, span, span, size, size)
			val pose = values().let { it to session.evaluate(it) }
			val color = colors.at(pose.first); val composites = colors.parts(pose.first)
			save(rasterizer.render(pose.second, color, frame, parts = composites), File(dir, "c_${v}_input.png"))
			for ((part, render) in parts) save(render.first.render(pose.second, color, frame, only = render.second, parts = composites), File(dir, "c_${v}_$part.png"))
			samples.append("""{"key":"$key","model":"${file.relativeTo(root).path.replace("\\", "/")}","side":"c","variant":"$v",""" +
				""""span":$span,"pixels":$size,"upsample":${(size / span) / max(1e-3f, density)},$counts}""").append('\n')
		}

		// The mouth: what moves most, for its size, with the mouth parameters, and what lies close around it (the jaw
		// and face some rigs open with the mouth are far larger). Its inside is what the mouth's own meshes clip (teeth,
		// tongue) and the clipping meshes themselves; the rest are the lips and lines.
		val open = param(ir, "ParamMouthOpenY", "PARAM_MOUTH_OPEN_Y")
		val form = param(ir, "ParamMouthForm", "PARAM_MOUTH_FORM")
		if (open != null) {
			val move = swing(listOfNotNull(open, form))
			val movers = visible.filter { id -> val m = move[id] ?: 0f; m > 1f && relative(id, m) > 0.04f }
			val strongest = movers.maxOfOrNull { relative(it, move[it] ?: 0f) } ?: 0f
			val core = box(base, movers.filter { relative(it, move[it] ?: 0f) >= strongest * 0.5f })
			val mouth = movers.filter { id -> box(base, listOf(id)).let { b ->
				abs(b.cx - core.cx) < core.w * 1.2f + 4f && abs(b.cy - core.cy) < core.h * 1.5f + 4f && b.w <= core.w * 2.5f + 8f } }
			val clipped = visible.filter { id -> meshes[id]?.maskedBy.orEmpty().any { it in mouth } }
			val inside = (mouth.filter { id -> clipped.any { meshes[it]?.maskedBy?.contains(id) == true } } + clipped).toSet()
			val lips = mouth.filter { it !in inside }.toSet()
			if (mouth.isNotEmpty() && lips.isNotEmpty()) {
				val unmasked = SoftwareRasterizer(ir.copy(meshes = ir.meshes.map { if (it.id in clipped) it.copy(maskedBy = emptyList()) else it })) { pixels[it] }
				val region = box(base, mouth)
				val density = texels(ir, base, mouth)
				val parts = buildMap {
					put("lips", rasterizer to lips)
					if (inside.isNotEmpty()) put("inside", unmasked to inside)
				}
				// The mouth region is framed loosely; the mouth is open enough to show the inside, the first framing a
				// fixed, clearly open mouth.
				for (i in 0 until variants) write("m$i", region.grown(1.8f), density, { random.nextDouble(0.85, 1.35).toFloat() }, {
					buildMap {
						put(open.id, open.max * (if (i == 0) 0.7f else random.nextDouble(0.3, 1.0).toFloat()))
						if (form != null && i > 0) put(form.id, form.min + (form.max - form.min) * random.nextFloat())
					}
				}, parts, """"lips":${lips.size},"inside":${inside.size}""")
				mouthNote = "ok ${lips.size}+${inside.size}"
			} else mouthNote = "no mouth found"
		}

		// The hair: meshes answering the hair parameters, each in the group whose parameters move it most.
		val groups = mapOf("front" to listOf("HairFront", "HAIR_FRONT"), "side" to listOf("HairSide", "HAIR_SIDE"), "back" to listOf("HairBack", "HAIR_BACK"))
			.mapValues { (_, names) -> ir.parameters.filter { p -> names.any { p.id.contains(it, ignoreCase = true) } } }
			.filterValues { it.isNotEmpty() }
		if (groups.isNotEmpty()) {
			val moves = groups.mapValues { (_, params) -> swing(params) }
			val hair = visible.mapNotNull { id ->
				moves.mapValues { it.value[id] ?: 0f }.filter { (_, m) -> m > 2f && relative(id, m) > 0.01f }.maxByOrNull { it.value }?.let { id to it.key }
			}.groupBy({ it.second }, { it.first }).mapValues { it.value.toSet() }
			if ("front" in hair) {
				val all = hair.values.flatten()
				val region = box(base, all)
				val density = texels(ir, base, hair.getValue("front").toList())
				val parts = hair.mapValues { rasterizer to it.value }
				val params = groups.values.flatten()
				for (i in 0 until variants) write("h$i", region, density, { random.nextDouble(1.05, 1.3).toFloat() }, {
					if (i == 0) emptyMap() else params.associate { it.id to (it.min + (it.max - it.min) * (0.5f + random.nextDouble(-0.3, 0.3).toFloat())) }
				}, parts, hair.entries.joinToString(",") { "\"${it.key}\":${it.value.size}" })
				hairNote = "ok " + hair.entries.joinToString("+") { "${it.key}${it.value.size}" }
			} else hairNote = "no front hair found"
		} else hairNote = "no hair parameters"
		session.close()
		return "$mouthNote\t$hairNote\t${if (mouthNote.startsWith("ok") || hairNote.startsWith("ok")) "ok" else "none"}"
	}

	/** Texels per canvas pixel across [ids] at [pose]: the square root of their texture area over their posed area. */
	private fun texels(ir: RigIR, pose: PoseGeometry, ids: List<String>): Float {
		var uvArea = 0.0; var posedArea = 0.0
		for (mesh in ir.meshes) {
			if (mesh.id !in ids) continue
			val g = mesh.geometry ?: continue
			val page = ir.textures.pages.getOrNull(ir.textures.bindings[mesh.id] ?: mesh.page) ?: continue
			val uv = g.uvs.shared(); val p = pose.positions[mesh.id] ?: continue; val idx = g.indices.shared()
			for (t in 0 until idx.size / 3) {
				val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
				uvArea += abs(area(uv[a * 2] * page.width, uv[a * 2 + 1] * page.height, uv[b * 2] * page.width, uv[b * 2 + 1] * page.height, uv[c * 2] * page.width, uv[c * 2 + 1] * page.height))
				posedArea += abs(area(p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1]))
			}
		}
		return if (posedArea <= 0.0) 0f else sqrt(uvArea / posedArea).toFloat()
	}

	private fun area(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double =
		((bx - ax).toDouble() * (cy - ay) - (cx - ax).toDouble() * (by - ay)) / 2

	private fun save(image: RasterImage, file: File) {
		val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
		out.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
		ImageIO.write(out, "png", file)
	}
}
