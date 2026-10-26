package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.GeometrySession
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Studies finished Cubism models - how each is organised and how each of its parameters moves it - as text and
 * pictures, to learn rigging technique from them. PSD2LIVE_MOC3 names a .model3.json or a folder searched for
 * them; each model writes to build/tools/rig-study/<its top folder>/ (or <its top folder>/<its name>/ when the folder holds
 * several models):
 *
 * - profile.txt: parameters with their groups and use, the parts tree, the deformer tree with each object's
 *   keyform axes, meshes, masks, glue, physics, expressions, motions, pose and VTube Studio mappings;
 * - effects.txt: per parameter, how far it moves each drawable over its range, which drawables fade, recolour or
 *   change draw order, and how far its halfway poses depart from the straight blend of their ends;
 * - default.png; params-NN.png, every parameter at five values cropped to what it moves; face.png (angle X by
 *   angle Y), body.png, eyes.png, mouth.png and mesh-face.png / mesh-body.png (wireframes) when it has those.
 *
 * PSD2LIVE_STUDY_ONLY keeps the models whose path contains it (or any of a comma-separated list); PSD2LIVE_STUDY_FRAME is the side of a sweep frame.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_MOC3=<file or folder> ./gradlew test --tests '*RigStudyTool' -Ppsd2live.testHeap=16g
 */
class RigStudyTool {
	@Test fun moc3() {
		requireTools()
		val root = File(setting("PSD2LIVE_MOC3", ""))
		val only = setting("PSD2LIVE_STUDY_ONLY", "")
		val manifests = (if (root.isFile) listOf(root) else root.walkTopDown().filter { it.name.endsWith(".model3.json") }.sortedBy { it.path }.toList())
			.filter { file -> only.isEmpty() || only.split(',').any { file.path.contains(it.trim()) } }
		require(manifests.isNotEmpty()) { "Set PSD2LIVE_MOC3 to a .model3.json or a folder holding them" }
		val out = output("rig-study")
		for (manifest in manifests) {
			val top = if (root.isFile) manifest.parentFile else generateSequence(manifest.parentFile) { it.parentFile }.first { it.parentFile == root || it == root }
			val siblings = manifests.count { other -> other != manifest && other.path.startsWith(top.path + File.separator) }
			val folder = File(out, top.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48) +
				if (siblings > 0) "/" + manifest.name.removeSuffix(".model3.json") else "").apply { mkdirs() }
			val started = System.nanoTime()
			runCatching { Study(manifest, folder).run() }.onFailure {
				File(folder, "error.txt").writeText(it.stackTraceToString())
				println("failed ${manifest.path}: $it")
			}
			println("studied ${manifest.path} -> $folder in ${"%.1f".format(since(started) / 1000)} s")
		}
	}
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun text(file: File) = file.readText(Charsets.UTF_8).removePrefix("﻿")

private fun f(value: Float) = if (abs(value - Math.round(value)) < 1e-4f) Math.round(value).toString() else String.format(Locale.ROOT, "%.3g", value)

private fun pngSize(bytes: ByteArray): Pair<Int, Int> {
	fun int(at: Int) = (bytes[at].toInt() and 255 shl 24) or (bytes[at + 1].toInt() and 255 shl 16) or (bytes[at + 2].toInt() and 255 shl 8) or (bytes[at + 3].toInt() and 255)
	return int(16) to int(20)
}

/** One parameter's measured effect. */
private class Effect(
	val parameter: Parameter,
	val samples: FloatArray,
	/** Per drawable, the farthest any vertex moves from the default pose at any sample, in canvas pixels. */
	val moved: Map<String, Float>,
	val fades: List<String>,
	val reorders: List<String>,
	val recolours: List<String>,
	/** Per half of the range, the largest departure of its middle pose from the blend of its ends, over that half's travel. */
	val bends: List<Pair<String, Float>>,
	val crop: FloatArray?,
	/** The bounds of what it moves or fades, over all samples, before [crop] widens them. */
	val reach: FloatArray?,
)

/**
 * The parts of a .model3.json this study reads, parsed leniently: viewers add entries of their own (motions that
 * only name an expression, say), which the strict manifest type refuses.
 */
private class Manifest(file: File) {
	private val root = json.parseToJsonElement(text(file)).jsonObject
	private val references = root["FileReferences"]?.jsonObject ?: JsonObject(emptyMap())
	private fun string(element: JsonElement?) = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

	val moc: String = requireNotNull(string(references["Moc"])) { "No Moc in ${file.name}" }
	val textures: List<String> = (references["Textures"] as? JsonArray).orEmpty().mapNotNull(::string)
	val physics = string(references["Physics"])
	val displayInfo = string(references["DisplayInfo"])
	val pose = string(references["Pose"])
	val expressions: List<Pair<String, String>> = (references["Expressions"] as? JsonArray).orEmpty().mapNotNull { e ->
		(e as? JsonObject)?.let { o -> string(o["File"])?.let { (string(o["Name"]) ?: it) to it } }
	}
	val motions: Map<String, List<String>> = (references["Motions"] as? JsonObject).orEmpty().mapValues { (_, list) ->
		(list as? JsonArray).orEmpty().mapNotNull { m -> (m as? JsonObject)?.let { string(it["File"]) } }
	}.filterValues { it.isNotEmpty() }
	val groups: List<String> = (root["Groups"] as? JsonArray).orEmpty().mapNotNull { g ->
		(g as? JsonObject)?.let { o -> "${string(o["Name"])} (${string(o["Target"])}): ${(o["Ids"] as? JsonArray).orEmpty().mapNotNull(::string).joinToString()}" }
	}
	val hitAreas: List<String> = (root["HitAreas"] as? JsonArray).orEmpty().mapNotNull { h -> (h as? JsonObject)?.let { "${string(it["Name"])}: ${string(it["Id"])}" } }
}

private class Study(private val manifestFile: File, private val out: File) {
	private val dir = manifestFile.parentFile
	private val refs = Manifest(manifestFile)
	private val cdi = refs.displayInfo?.let { File(dir, it) }?.takeIf { it.isFile }?.let { runCatching { Moc3.readCdi3(text(it)) }.getOrNull() }
	private val model: PuppetModel = Moc3Import.fromMocDocument(Moc3.read(File(dir, refs.moc).readBytes()), cdi)
	private val ir: RigIR = PuppetIr.toIr(model,
		pages = refs.textures.map { File(dir, it).readBytes() }.map { png -> pngSize(png).let { (w, h) -> TexturePage(w, h, Bytes.wrap(png)) } },
		bindings = model.drawables.associate { it.id.raw to it.texturePage.coerceAtLeast(0) })
	private val rasterizer by lazy { SoftwareRasterizer(ir) }
	private val colors = IrColors(ir)
	private val sessions = ThreadLocal.withInitial<GeometrySession> { IrGeometryEvaluator.open(ir) }
	private val defaults = model.parameters.associate { it.id.raw to it.default }
	private val drawableById = model.drawables.associateBy { it.id.raw }
	private val label = cdi?.drawables.orEmpty().associate { it.id to it.name }
	private val groupOf: Map<String, String> = buildMap {
		fun walk(nodes: List<ParameterNode>, path: String) {
			for (node in nodes) when (node) {
				is ParameterNode.Param -> put(node.id.raw, path)
				is ParameterNode.Group -> walk(node.children, if (path.isEmpty()) node.name else "$path/${node.name}")
			}
		}
		walk(model.parameterTree, "")
	}
	private val base: PoseGeometry = pose(defaults)
	/** The default pose's extent without its outliers: the 0.5th to 99.5th percentile of the shown vertices. */
	private val figure: FloatArray = run {
		val shown = model.drawables.map { it.id.raw }.filter { (base.opacity[it] ?: 1f) > 0.01f }.mapNotNull { base.positions[it] }
		val xs = shown.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 0 } }.sorted()
		val ys = shown.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 1 } }.sorted()
		if (xs.isEmpty()) floatArrayOf(0f, 0f, model.canvasWidth, model.canvasHeight)
		else floatArrayOf(xs[xs.size / 200], ys[ys.size / 200], xs[xs.size - 1 - xs.size / 200], ys[ys.size - 1 - ys.size / 200])
	}
	private val figureHeight = figure[3] - figure[1]
	private val frameSide = setting("PSD2LIVE_STUDY_FRAME", "200").toInt()

	fun run() {
		if (setting("PSD2LIVE_STUDY_REVIEW", "") == "1") { review(); return }
		File(out, "profile.txt").writeText(profile())
		val effects = model.parameters.parallelStream().map(::measure).toList()
		File(out, "effects.txt").writeText(describe(effects))
		ImageIO.write(render(defaults, figure.expanded(0.02f), 1400), "png", File(out, "default.png"))
		sweeps(effects)
		grids(effects)
		wireframes(effects)
	}

	private fun pose(values: Map<String, Float>): PoseGeometry = sessions.get().evaluate(values)

	private fun name(id: String) = drawableById[id]?.name?.takeIf { it.isNotBlank() && it != id }?.let { "$it($id)" } ?: label[id]?.let { "$it($id)" } ?: id

	private fun bounds(geometry: PoseGeometry, ids: Collection<String>): FloatArray? {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (id in ids) {
			val p = geometry.positions[id] ?: continue
			var i = 0
			while (i < p.size) { x0 = min(x0, p[i]); x1 = max(x1, p[i]); y0 = min(y0, p[i + 1]); y1 = max(y1, p[i + 1]); i += 2 }
		}
		return if (x0 > x1) null else floatArrayOf(x0, y0, x1, y1)
	}

	private fun FloatArray.expanded(by: Float, minSide: Float = 0f): FloatArray {
		val cx = (this[0] + this[2]) / 2; val cy = (this[1] + this[3]) / 2
		val side = max(max(this[2] - this[0], this[3] - this[1]) * (1 + 2 * by), minSide)
		return floatArrayOf(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
	}

	private fun render(values: Map<String, Float>, box: FloatArray, size: Int, geometry: PoseGeometry = pose(values)): BufferedImage {
		val w = box[2] - box[0]; val h = box[3] - box[1]
		val scale = size / max(w, h)
		val pw = max(1, (w * scale).toInt()); val ph = max(1, (h * scale).toInt())
		val frame = FrameSpec(box[0], box[1], w, h, pw, ph, background = 0xFFFFFFFF.toInt())
		val raster = rasterizer.render(geometry, colors.at(values), frame, null, colors.parts(values))
		return BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, pw, ph, raster.argb, 0, pw) }
	}

	// ---- profile ----

	private fun axes(grid: KeyformGrid<*>?): String = grid?.axes?.joinToString(" x ") { axis -> "${axis.parameterId.raw}[${axis.keys.joinToString(",") { f(it) }}]" } ?: "-"

	private fun channels(grids: org.umamo.runtime.model.ChannelGrids): String = grids.gridsByChannel.entries
		.filter { (_, grid) -> grid.cells.map { it.form }.distinct().size > 1 }
		.joinToString(" ") { (channel, grid) -> "${channel.name.lowercase()}{${axes(grid)}}" }

	private fun profile(): String = buildString {
		val drawables = model.drawables
		val vertices = drawables.sumOf { it.mesh?.vertexCount ?: 0 }
		val warps = model.deformers.filterIsInstance<Deformer.Warp>()
		val rotations = model.deformers.filterIsInstance<Deformer.Rotation>()
		appendLine("# ${manifestFile.name}")
		appendLine("canvas ${f(model.canvasWidth)} x ${f(model.canvasHeight)}, figure bounds ${figure.joinToString { f(it) }} (height ${f(figureHeight)} px)")
		appendLine("parameters ${model.parameters.size}, parts ${model.parts.size}, warps ${warps.size}, rotations ${rotations.size}, drawables ${drawables.size}, vertices $vertices, triangles ${drawables.sumOf { it.mesh?.triangleCount ?: 0 }}")
		appendLine("masked drawables ${drawables.count { it.maskedBy.isNotEmpty() }}, glues ${model.glues.size}, textures ${refs.textures.size} (${ir.textures.pages.joinToString { "${it.width}x${it.height}" }})")
		appendLine("blend modes: ${drawables.groupingBy { it.blendMode.name }.eachCount()}")
		appendLine("keyform cells: drawables ${drawables.sumOf { it.geometryGrid?.cells?.size ?: 0 }}, warps ${warps.sumOf { it.geometryGrid?.cells?.size ?: 0 }}, rotations ${rotations.sumOf { it.geometryGrid?.cells?.size ?: 0 }}")
		appendLine("blend shapes: drawables ${drawables.sumOf { it.blendShapes.size }}, warps ${warps.sumOf { it.blendShapes.size }}, rotations ${rotations.sumOf { it.blendShapes.size }}, parts ${model.parts.sumOf { it.blendShapes.size }}")
		appendLine("parameter links (2D): ${model.parameterLinks.joinToString { "${it.horizontal.raw}+${it.vertical.raw}" }}")
		cdi?.combinedParameters?.takeIf { it.isNotEmpty() }?.let { appendLine("combined parameters: ${it.joinToString { pair -> pair.joinToString("+") }}") }
		refs.groups.forEach { appendLine("group $it") }
		refs.hitAreas.forEach { appendLine("hit area $it") }

		appendLine()
		appendLine("## Parameters (id 'name' [min def max] group | used by: deformer geometry, drawable geometry, channels, blend shapes | keys)")
		class Use { var deformers = 0; var drawables = 0; var channels = 0; var blends = 0; val keys = sortedSetOf<Float>() }
		val use = model.parameters.associate { it.id.raw to Use() }
		fun count(grid: KeyformGrid<*>?, bump: (Use) -> Unit) = grid?.axes?.forEach { axis -> use[axis.parameterId.raw]?.let { bump(it); axis.keys.forEach(it.keys::add) } }
		for (d in model.deformers) {
			when (d) { is Deformer.Warp -> count(d.geometryGrid) { it.deformers++ }; is Deformer.Rotation -> count(d.geometryGrid) { it.deformers++ } }
			d.channelGrids.gridsByChannel.values.forEach { g -> if (g.cells.map { it.form }.distinct().size > 1) count(g) { it.channels++ } }
			val shapes = when (d) { is Deformer.Warp -> d.blendShapes.map { it.parameterId }; is Deformer.Rotation -> d.blendShapes.map { it.parameterId } }
			shapes.forEach { use[it.raw]?.let { u -> u.blends++ } }
		}
		for (d in drawables) {
			count(d.geometryGrid) { it.drawables++ }
			d.channelGrids.gridsByChannel.values.forEach { g -> if (g.cells.map { it.form }.distinct().size > 1) count(g) { it.channels++ } }
			d.blendShapes.forEach { use[it.parameterId.raw]?.let { u -> u.blends++; it.keys.forEach(u.keys::add) } }
		}
		for (p in model.parts) {
			p.channelGrids.gridsByChannel.values.forEach { g -> if (g.cells.map { it.form }.distinct().size > 1) count(g) { it.channels++ } }
			p.blendShapes.forEach { use[it.parameterId.raw]?.let { u -> u.blends++ } }
		}
		for (p in model.parameters) {
			val u = use.getValue(p.id.raw)
			appendLine("${p.id.raw} '${p.name}' [${f(p.min)} ${f(p.default)} ${f(p.max)}]${if (p.kind.name != "NORMAL") " ${p.kind}" else ""}${if (p.repeat) " repeat" else ""} ${groupOf[p.id.raw] ?: ""} | ${u.deformers} ${u.drawables} ${u.channels} ${u.blends} | ${u.keys.joinToString(",") { f(it) }}")
		}

		appendLine()
		appendLine("## Parts tree (part 'name' / drawable 'name' verts)")
		val partById = model.parts.associateBy { it.id }
		fun walk(children: List<OrgChild>, depth: Int) {
			for (child in children) when (child) {
				is OrgChild.Part -> partById[child.id]?.let { part ->
					val keyed = channels(part.channelGrids)
					appendLine("${"  ".repeat(depth)}+ ${part.id.raw} '${part.name}'${if (!part.isVisible) " hidden" else ""}${if (keyed.isNotEmpty()) " $keyed" else ""}")
					walk(part.children, depth + 1)
				}
				is OrgChild.Drawable -> drawableById[child.id.raw]?.let { appendLine("${"  ".repeat(depth)}- ${name(it.id.raw)} ${it.mesh?.vertexCount ?: 0}v") }
			}
		}
		walk(model.rootChildren, 0)

		appendLine()
		appendLine("## Deformer tree (W warp rows x cols / R rotation; keyform axes; channels) and the drawables under each")
		val childrenOf = model.deformers.groupBy { it.parent?.raw }
		val drawablesOf = drawables.groupBy { it.parentDeformerId?.raw }
		fun drawableLine(d: Drawable, depth: Int) {
			val area = baseArea(d)
			val density = if (area > 0) d.mesh!!.vertexCount / area * 10_000 else 0.0
			val extra = buildList {
				add(axes(d.geometryGrid))
				channels(d.channelGrids).takeIf { it.isNotEmpty() }?.let(::add)
				if (d.blendShapes.isNotEmpty()) add("blend{${d.blendShapes.joinToString { "${it.parameterId.raw}[${it.keys.joinToString(",") { k -> f(k) }}]" }}}")
				if (d.maskedBy.isNotEmpty()) add("${if (d.invertMask) "inverse-" else ""}masked by ${d.maskedBy.joinToString { name(it.raw) }}")
				if (d.blendMode.name != "Normal" && d.blendMode.name != "NORMAL") add("blend ${d.blendMode.name}")
				if (d.culling) add("culling")
			}
			appendLine("${"  ".repeat(depth)}- ${name(d.id.raw)} ${d.mesh?.vertexCount ?: 0}v ${d.mesh?.triangleCount ?: 0}t ${String.format(Locale.ROOT, "%.1f", density)}v/100px² ${extra.joinToString(" ")}")
		}
		fun deformer(d: Deformer, depth: Int) {
			val head = when (d) {
				is Deformer.Warp -> "W ${d.id.raw} '${d.name}' ${d.rows}x${d.columns}${if (d.isQuadTransform) " quad" else ""} ${axes(d.geometryGrid)}${if (d.blendShapes.isNotEmpty()) " blend{${d.blendShapes.joinToString { it.parameterId.raw }}}" else ""}"
				is Deformer.Rotation -> "R ${d.id.raw} '${d.name}' base ${f(d.baseAngle)} ${axes(d.geometryGrid)} ${rotationRange(d)}${if (d.blendShapes.isNotEmpty()) " blend{${d.blendShapes.joinToString { it.parameterId.raw }}}" else ""}"
			}
			val keyed = channels(d.channelGrids)
			appendLine("${"  ".repeat(depth)}$head${if (keyed.isNotEmpty()) " $keyed" else ""}")
			childrenOf[d.id.raw].orEmpty().forEach { deformer(it, depth + 1) }
			drawablesOf[d.id.raw].orEmpty().forEach { drawableLine(it, depth + 1) }
		}
		childrenOf[null].orEmpty().forEach { deformer(it, 0) }
		drawablesOf[null].orEmpty().forEach { drawableLine(it, 0) }

		appendLine()
		appendLine("## Keyform axis signatures (parameters keyed together -> objects)")
		val signatures = HashMap<String, MutableList<String>>()
		fun sign(grid: KeyformGrid<*>?, what: String) { grid?.axes?.takeIf { it.isNotEmpty() }?.let { a -> signatures.getOrPut(a.joinToString(" x ") { "${it.parameterId.raw}/${it.keys.size}" }) { mutableListOf() } += what } }
		model.deformers.forEach { d -> when (d) { is Deformer.Warp -> sign(d.geometryGrid, "W ${d.id.raw}"); is Deformer.Rotation -> sign(d.geometryGrid, "R ${d.id.raw}") } }
		drawables.forEach { sign(it.geometryGrid, "M ${it.id.raw}") }
		signatures.entries.sortedByDescending { it.value.size }.forEach { (sig, objects) -> appendLine("${objects.size}x  $sig  e.g. ${objects.take(6).joinToString()}") }

		if (model.glues.isNotEmpty()) {
			appendLine()
			appendLine("## Glue")
			model.glues.forEach { g -> appendLine("${name(g.meshA.raw)} + ${name(g.meshB.raw)}: ${g.pairs.size} pairs, intensity ${f(g.intensity)} ${channels(g.channelGrids)}") }
		}

		refs.physics?.let { File(dir, it) }?.takeIf { it.isFile }?.let { appendLine(); append(physics(it)) }
		refs.expressions.takeIf { it.isNotEmpty() }?.let { list ->
			appendLine()
			appendLine("## Expressions")
			list.forEach { (name, file) -> appendLine("$name: ${expression(File(dir, file))}") }
		}
		refs.motions.takeIf { it.isNotEmpty() }?.let { groups ->
			appendLine()
			appendLine("## Motions")
			groups.forEach { (group, list) -> list.forEach { file -> append(motion(group, File(dir, file))) } }
		}
		refs.pose?.let { File(dir, it) }?.takeIf { it.isFile }?.let { appendLine(); appendLine("## Pose"); appendLine(runCatching { text(it) }.getOrDefault("")) }
		dir.listFiles { file -> file.name.endsWith(".vtube.json") }?.firstOrNull()?.let { appendLine(); append(vtube(it)) }
	}

	private fun rotationRange(d: Deformer.Rotation): String {
		val forms = d.geometryGrid?.cells?.map { it.form } ?: return ""
		return "angle ${f(forms.minOf { it.angle })}..${f(forms.maxOf { it.angle })} scale ${f(forms.minOf { it.scale })}..${f(forms.maxOf { it.scale })}"
	}

	private fun baseArea(d: Drawable): Double {
		val p = base.positions[d.id.raw] ?: return 0.0
		val idx = d.mesh?.indices ?: return 0.0
		var area = 0.0
		var i = 0
		while (i + 2 < idx.size) {
			val a = idx[i] * 2; val b = idx[i + 1] * 2; val c = idx[i + 2] * 2
			area += abs((p[b] - p[a]).toDouble() * (p[c + 1] - p[a + 1]) - (p[c] - p[a]).toDouble() * (p[b + 1] - p[a + 1])) / 2
			i += 3
		}
		return area
	}

	private fun num(e: JsonElement?): String = (e as? JsonPrimitive)?.floatOrNull?.let { f(it) } ?: e.toString()

	private fun physics(file: File): String = buildString {
		val root = runCatching { json.parseToJsonElement(text(file)).jsonObject }.getOrElse { return "## Physics unreadable: $it\n" }
		val meta = root["Meta"]?.jsonObject
		val names = meta?.get("PhysicsDictionary")?.jsonArray.orEmpty().associate { it.jsonObject["Id"]?.jsonPrimitive?.content to it.jsonObject["Name"]?.jsonPrimitive?.content }
		appendLine("## Physics (${meta?.get("PhysicsSettingCount")} settings, fps ${num(meta?.get("Fps"))}, gravity ${meta?.get("EffectiveForces")?.jsonObject?.get("Gravity")}, wind ${meta?.get("EffectiveForces")?.jsonObject?.get("Wind")})")
		for (setting in root["PhysicsSettings"]?.jsonArray.orEmpty()) {
			val s = setting.jsonObject
			val id = s["Id"]?.jsonPrimitive?.content
			appendLine("- $id '${names[id] ?: ""}'")
			s["Input"]?.jsonArray?.forEach { i -> val o = i.jsonObject; appendLine("    in  ${o["Source"]?.jsonObject?.get("Id")?.jsonPrimitive?.content} w=${num(o["Weight"])} ${o["Type"]?.jsonPrimitive?.content}${if (o["Reflect"]?.jsonPrimitive?.booleanOrNull == true) " reflect" else ""}") }
			s["Output"]?.jsonArray?.forEach { i -> val o = i.jsonObject; appendLine("    out ${o["Destination"]?.jsonObject?.get("Id")?.jsonPrimitive?.content} v=${o["VertexIndex"]} scale=${num(o["Scale"])} w=${num(o["Weight"])} ${o["Type"]?.jsonPrimitive?.content}${if (o["Reflect"]?.jsonPrimitive?.booleanOrNull == true) " reflect" else ""}") }
			val vertices = s["Vertices"]?.jsonArray.orEmpty().map { it.jsonObject }
			appendLine("    chain " + vertices.joinToString(" | ") { v -> val p = v["Position"]?.jsonObject; "(${num(p?.get("X"))},${num(p?.get("Y"))}) m=${num(v["Mobility"])} d=${num(v["Delay"])} a=${num(v["Acceleration"])} r=${num(v["Radius"])}" })
			val n = s["Normalization"]?.jsonObject
			appendLine("    norm pos ${n?.get("Position")?.jsonObject?.values?.joinToString("/") { num(it) }} angle ${n?.get("Angle")?.jsonObject?.values?.joinToString("/") { num(it) }}")
		}
	}

	private fun expression(file: File): String = runCatching {
		val root = json.parseToJsonElement(text(file)).jsonObject
		root["Parameters"]?.jsonArray.orEmpty().joinToString { p -> val o = p.jsonObject; "${o["Id"]?.jsonPrimitive?.content}=${num(o["Value"])}${o["Blend"]?.jsonPrimitive?.content?.let { " $it" } ?: ""}" }
	}.getOrElse { "unreadable ($it)" }

	private fun motion(group: String, file: File): String = buildString {
		val root = runCatching { json.parseToJsonElement(text(file)).jsonObject }.getOrElse { return "$group ${file.name}: unreadable\n" }
		val meta = root["Meta"]?.jsonObject
		appendLine("### $group ${file.name}: ${num(meta?.get("Duration"))} s at ${num(meta?.get("Fps"))} fps, loop ${meta?.get("Loop")}, ${meta?.get("CurveCount")} curves")
		for (curve in root["Curves"]?.jsonArray.orEmpty().take(120)) {
			val c = curve.jsonObject
			val seg = c["Segments"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
			if (seg.size < 2) continue
			val values = mutableListOf(seg[1]); val times = mutableListOf(seg[0]); val types = sortedSetOf<String>()
			var i = 2
			while (i < seg.size) {
				when (seg[i].toInt()) {
					1 -> { types += "bezier"; if (i + 6 < seg.size) { times += seg[i + 5]; values += seg[i + 6] }; i += 7 }
					2 -> { types += "step"; if (i + 2 < seg.size) { times += seg[i + 1]; values += seg[i + 2] }; i += 3 }
					3 -> { types += "inverse-step"; if (i + 2 < seg.size) { times += seg[i + 1]; values += seg[i + 2] }; i += 3 }
					else -> { types += "linear"; if (i + 2 < seg.size) { times += seg[i + 1]; values += seg[i + 2] }; i += 3 }
				}
			}
			appendLine("  ${c["Target"]?.jsonPrimitive?.content} ${c["Id"]?.jsonPrimitive?.content}: ${values.size} points ${types.joinToString("/")} value ${f(values.min())}..${f(values.max())} keys@ ${times.zip(values).take(10).joinToString(" ") { (t, v) -> "${f(t)}:${f(v)}" }}${if (values.size > 10) " ..." else ""}")
		}
	}

	private fun vtube(file: File): String = buildString {
		val root = runCatching { json.parseToJsonElement(text(file)).jsonObject }.getOrElse { return "" }
		appendLine("## VTube Studio parameter mappings (input [in range] -> output [out range], smoothing)")
		for (p in root["ParameterSettings"]?.jsonArray.orEmpty()) {
			val o = p.jsonObject
			appendLine("  ${o["Input"]?.jsonPrimitive?.content} [${num(o["InputRangeLower"])}, ${num(o["InputRangeUpper"])}] -> ${o["OutputLive2D"]?.jsonPrimitive?.content} [${num(o["OutputRangeLower"])}, ${num(o["OutputRangeUpper"])}] s=${num(o["Smoothing"])}${if (o["ClampInput"]?.jsonPrimitive?.booleanOrNull == true) " clamp" else ""}")
		}
	}

	// ---- effects ----

	private fun samplesOf(p: Parameter): FloatArray {
		val lo = p.min; val hi = p.max; val d = p.default.coerceIn(lo, hi)
		return if (d - lo < (hi - lo) * 0.05f || hi - d < (hi - lo) * 0.05f) floatArrayOf(lo, lo + (hi - lo) * 0.25f, lo + (hi - lo) * 0.5f, lo + (hi - lo) * 0.75f, hi)
		else floatArrayOf(lo, (lo + d) / 2, d, (d + hi) / 2, hi)
	}

	private fun measure(p: Parameter): Effect {
		val samples = samplesOf(p)
		val poses = samples.map { pose(defaults + (p.id.raw to it)) }
		val moved = HashMap<String, Float>()
		for ((id, rest) in base.positions) {
			var far = 0f
			for (g in poses) {
				val q = g.positions[id] ?: continue
				var i = 0
				while (i < min(q.size, rest.size)) { far = max(far, hypot(q[i] - rest[i], q[i + 1] - rest[i + 1])); i += 2 }
			}
			if (far > 0.05f) moved[id] = far
		}
		val fading = model.drawables.filter { d -> poses.map { it.opacity[d.id.raw] ?: 1f }.let { ops -> ops.max() - ops.min() > 0.02f } }
		val fades = fading.map { d -> "${name(d.id.raw)} ${poses.joinToString("/") { f(it.opacity[d.id.raw] ?: 1f) }}" }
		val reorders = model.drawables.mapNotNull { d ->
			val orders = poses.map { it.drawOrder[d.id.raw] ?: 0f }
			if (orders.max() - orders.min() > 0.5f) "${name(d.id.raw)} ${orders.joinToString("/") { f(it) }}" else null
		}
		val colours = samples.map { colors.at(defaults + (p.id.raw to it)) }
		val recolours = model.drawables.mapNotNull { d ->
			val c = colours.map { it[d.id.raw] }
			if (c.distinctBy { it?.multiply.toString() + it?.screen.toString() }.size > 1) name(d.id.raw) else null
		}
		val halves = if (samples[2] == p.default.coerceIn(p.min, p.max) && samples[0] != samples[2]) listOf("low" to Triple(0, 1, 2), "high" to Triple(2, 3, 4))
			else listOf("whole" to Triple(0, 2, 4), "first quarter-to-three" to Triple(1, 2, 3))
		val bends = halves.map { (label, ijk) ->
			val (a, m, b) = ijk
			var dev = 0f; var travel = 0f
			for (id in base.positions.keys) {
				val pa = poses[a].positions[id] ?: continue; val pm = poses[m].positions[id] ?: continue; val pb = poses[b].positions[id] ?: continue
				var i = 0
				while (i < min(pa.size, min(pm.size, pb.size))) {
					dev = max(dev, hypot(pm[i] - (pa[i] + pb[i]) / 2, pm[i + 1] - (pa[i + 1] + pb[i + 1]) / 2))
					travel = max(travel, hypot(pb[i] - pa[i], pb[i + 1] - pa[i + 1]))
					i += 2
				}
			}
			label to if (travel > 0.5f) dev / travel else 0f
		}
		val farthest = moved.values.maxOrNull() ?: 0f
		val involved = moved.filter { it.value > max(1.5f, farthest * 0.15f) }.keys + fading.map { it.id.raw }
		var reach: FloatArray? = null
		if (involved.isNotEmpty()) for (g in poses + base) bounds(g, involved)?.let { b ->
			reach = reach?.let { floatArrayOf(min(it[0], b[0]), min(it[1], b[1]), max(it[2], b[2]), max(it[3], b[3])) } ?: b
		}
		return Effect(p, samples, moved, fades, reorders, recolours, bends, reach?.expanded(0.12f, figureHeight * 0.08f), reach)
	}

	private fun describe(effects: List<Effect>): String = buildString {
		appendLine("Displacements are in canvas pixels; the figure is ${f(figureHeight)} px tall. 'bend' is the largest departure of a half's middle pose from the straight blend of its ends, over that half's largest travel: 0 is linear, above ~0.05 there are inner keys or a nonlinear chain.")
		for (e in effects) {
			val p = e.parameter
			val farthest = e.moved.values.maxOrNull() ?: 0f
			appendLine()
			appendLine("## ${p.id.raw} '${p.name}' [${f(p.min)} ${f(p.default)} ${f(p.max)}] ${groupOf[p.id.raw] ?: ""}")
			appendLine("samples ${e.samples.joinToString(" ") { f(it) }}; farthest ${f(farthest)} px (${String.format(Locale.ROOT, "%.2f", farthest / figureHeight * 100)}% of figure); drawables moved >1px: ${e.moved.count { it.value > 1f }}; bend ${e.bends.joinToString { (k, v) -> "$k ${String.format(Locale.ROOT, "%.3f", v)}" }}")
			e.moved.entries.sortedByDescending { it.value }.take(14).takeIf { it.isNotEmpty() }?.let { top -> appendLine("moves: " + top.joinToString(", ") { "${name(it.key)} ${f(it.value)}" }) }
			if (e.fades.isNotEmpty()) appendLine("opacity: " + e.fades.take(24).joinToString("; ") + if (e.fades.size > 24) " ... (${e.fades.size})" else "")
			if (e.reorders.isNotEmpty()) appendLine("draw order: " + e.reorders.take(24).joinToString("; ") + if (e.reorders.size > 24) " ... (${e.reorders.size})" else "")
			if (e.recolours.isNotEmpty()) appendLine("colour: " + e.recolours.take(24).joinToString("; ") + if (e.recolours.size > 24) " ... (${e.recolours.size})" else "")
		}
	}

	// ---- pictures ----

	/** [text] on a dark strip across the top of [image], or its bottom with [bottom]. */
	private fun caption(image: BufferedImage, text: String, size: Float = 13f, bottom: Boolean = false) {
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		g.font = Font("Microsoft YaHei", Font.PLAIN, 1).deriveFont(size)
		val strip = (size * 1.5f).toInt()
		val top = if (bottom) image.height - strip else 0
		g.color = Color(0, 0, 0, 170)
		g.fillRect(0, top, image.width, strip)
		g.color = Color.WHITE
		g.drawString(text, 3f, top + size * 1.15f)
		g.dispose()
	}

	private fun sweeps(effects: List<Effect>) {
		val side = frameSide
		effects.filter { it.crop != null }.chunked(10).forEachIndexed { sheet, chunk ->
			val tiles = chunk.parallelStream().map { e -> e.samples.map { v -> render(defaults + (e.parameter.id.raw to v), e.crop!!, side) } }.toList()
			val image = BufferedImage(side * 5, side * chunk.size, BufferedImage.TYPE_INT_RGB)
			val g = image.createGraphics()
			g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
			chunk.forEachIndexed { row, e ->
				tiles[row].forEachIndexed { col, tile ->
					if (col == 0) caption(tile, "${e.parameter.id.raw} ${e.parameter.name}", 12f)
					caption(tile, f(e.samples[col]), 12f, bottom = true)
					g.drawImage(tile, col * side, row * side, null)
				}
				g.color = Color.LIGHT_GRAY
				g.drawLine(0, row * side, image.width, row * side)
			}
			g.dispose()
			ImageIO.write(image, "png", File(out, "params-%02d.png".format(sheet + 1)))
		}
	}

	private val ids = model.parameters.associateBy { it.id.raw.lowercase() }
	private fun find(vararg candidates: String): Parameter? {
		for (c in candidates) ids[c.lowercase()]?.let { return it }
		val wanted = candidates.map { it.replace(" ", "").lowercase() }.toSet()
		return model.parameters.firstOrNull { it.name.replace(" ", "").lowercase() in wanted }
	}

	private val angleX = find("ParamAngleX", "PARAM_ANGLE_X", "角度X", "AngleX")
	private val angleY = find("ParamAngleY", "PARAM_ANGLE_Y", "角度Y", "AngleY")
	private val bodyX = find("ParamBodyAngleX", "PARAM_BODY_ANGLE_X", "体の回転X", "身体旋转X", "身体X", "BodyAngleX")
	private val bodyY = find("ParamBodyAngleY", "PARAM_BODY_ANGLE_Y", "体の回転Y", "身体旋转Y", "身体Y", "BodyAngleY")
	private val eyeOpen = find("ParamEyeLOpen", "PARAM_EYE_L_OPEN", "左目開閉", "左眼开闭", "左眼睁闭")
	private val eyeOpenRight = find("ParamEyeROpen", "PARAM_EYE_R_OPEN", "右目開閉", "右眼开闭", "右眼睁闭")
	private val eyeSmile = find("ParamEyeLSmile", "PARAM_EYE_L_SMILE", "左目笑顔", "左眼微笑")
	private val mouthOpen = find("ParamMouthOpenY", "PARAM_MOUTH_OPEN_Y", "口開閉", "嘴张开和闭合", "嘴巴开闭")
	private val mouthForm = find("ParamMouthForm", "PARAM_MOUTH_FORM", "口変形", "嘴变形", "嘴巴变形")

	private fun grid(name: String, across: Parameter?, down: Parameter?, box: FloatArray?, side: Int) {
		if (across == null || box == null) return
		val xs = samplesOf(across).let { floatArrayOf(it[0], it[2], it[4]) }.let { if (across.max - across.min > 0 && (across.default == across.min || across.default == across.max)) floatArrayOf(across.min, (across.min + across.max) / 2, across.max) else it }
		val ys = down?.let { d -> floatArrayOf(d.min, d.default.coerceIn(d.min, d.max), d.max).distinct().toFloatArray().let { v -> if (v.size < 3) floatArrayOf(d.min, (d.min + d.max) / 2, d.max) else v } } ?: floatArrayOf(Float.NaN)
		val cells = ys.flatMap { y -> xs.map { x -> x to y } }
		val frames = cells.parallelStream().map { (x, y) ->
			val values = defaults + (across.id.raw to x) + (if (down != null) mapOf(down.id.raw to y) else emptyMap())
			val image = render(values, box, side)
			caption(image, "${across.id.raw}=${f(x)}${if (down != null) "  ${down.id.raw}=${f(y)}" else ""}", 13f)
			image
		}.toList()
		val sheet = BufferedImage(side * xs.size, side * ys.size, BufferedImage.TYPE_INT_RGB)
		val g = sheet.createGraphics()
		g.color = Color.WHITE; g.fillRect(0, 0, sheet.width, sheet.height)
		frames.forEachIndexed { i, image -> g.drawImage(image, i % xs.size * side, i / xs.size * side, null) }
		g.dispose()
		ImageIO.write(sheet, "png", File(out, "$name.png"))
	}

	/**
	 * The head, from where the eyes and the mouth move: their joint bounds span roughly the middle of the face, so
	 * the head is a square a little over twice their size, centred a little above them. Without them, what angle X
	 * moves.
	 */
	private fun head(byId: Map<String, Effect>): FloatArray? {
		val reaches = listOfNotNull(eyeOpen, eyeOpenRight, mouthOpen).mapNotNull { byId[it.id.raw]?.reach }
		if (reaches.isEmpty()) return angleX?.let { byId[it.id.raw]?.crop }
		val x0 = reaches.minOf { it[0] }; val y0 = reaches.minOf { it[1] }; val x1 = reaches.maxOf { it[2] }; val y1 = reaches.maxOf { it[3] }
		val side = max(x1 - x0, y1 - y0) * 2.3f
		val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2 - side * 0.08f
		return floatArrayOf(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
	}

	private fun grids(effects: List<Effect>) {
		val byId = effects.associateBy { it.parameter.id.raw }
		val head = head(byId)
		grid("face", angleX, angleY, head, 420)
		grid("body", bodyX, bodyY, figure.expanded(0.03f), 520)
		grid("eyes", eyeOpen, eyeSmile, eyeOpen?.let { byId[it.id.raw]?.crop?.expanded(0.25f) }, 360)
		grid("mouth", mouthForm, mouthOpen, (mouthOpen ?: mouthForm)?.let { byId[it.id.raw]?.crop?.expanded(0.6f) }, 300)
	}

	/**
	 * PSD2LIVE_STUDY_REVIEW=1: only the eyes in the poses the generated rig is reviewed in (`RigReviewTool`), both
	 * eyes together, closing in steps, widened past 1 where the model can, and looking up and down: `review-eye.png`.
	 */
	private fun review() {
		val left = eyeOpen ?: return
		val byId = listOfNotNull(left, eyeOpenRight).associate { it.id.raw to measure(it) }
		val crops = byId.values.mapNotNull { it.crop }
		if (crops.isEmpty()) return
		val box = floatArrayOf(crops.minOf { it[0] }, crops.minOf { it[1] }, crops.maxOf { it[2] }, crops.maxOf { it[3] }).let { b ->
			val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2; val w = (b[2] - b[0]) * 1.3f; val h = max((b[3] - b[1]) * 2.2f, w * 0.4f)
			floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
		}
		fun eyes(value: Float) = listOfNotNull(left, eyeOpenRight).associate { it.id.raw to value }
		val ballY = model.parameters.firstOrNull { it.id.raw == "ParamEyeBallY" }
		val poses = listOf(1f, 0.75f, 0.5f, 0.25f, 0.1f, 0f).map { "open ${f(it)}" to eyes(it) } +
			(if (left.max > 1f) listOf("open ${f(left.max)}" to eyes(left.max)) else emptyList()) +
			(if (ballY != null) listOf("look up" to mapOf(ballY.id.raw to ballY.max), "look down" to mapOf(ballY.id.raw to ballY.min)) else emptyList())
		val frames = poses.map { (name, values) -> render(defaults + values, box, 420).also { caption(it, name, 13f) } }
		val sheet = BufferedImage(frames.sumOf { it.width }, frames.maxOf { it.height }, BufferedImage.TYPE_INT_RGB)
		val g = sheet.createGraphics()
		var x = 0
		for (frame in frames) { g.drawImage(frame, x, 0, null); x += frame.width }
		g.dispose()
		ImageIO.write(sheet, "png", File(out, "review-eye.png"))
	}

	private fun wireframes(effects: List<Effect>) {
		val head = head(effects.associateBy { it.parameter.id.raw })
		head?.let { wire("mesh-face", it, 1600) }
		wire("mesh-body", figure.expanded(0.02f), 1800)
	}

	private fun wire(name: String, box: FloatArray, size: Int) {
		val image = render(defaults, box, size, base)
		val scale = image.width / (box[2] - box[0])
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.stroke = BasicStroke(0.6f)
		for (d in model.drawables) {
			val p = base.positions[d.id.raw] ?: continue
			val idx = d.mesh?.indices ?: continue
			if ((base.opacity[d.id.raw] ?: 1f) < 0.05f) continue
			val hue = (d.id.raw.hashCode() and 0xffff) / 65535f
			g.color = Color(Color.HSBtoRGB(hue, 0.9f, 0.8f) and 0xffffff or (0x90 shl 24), true)
			var i = 0
			while (i + 2 < idx.size) {
				for ((a, b) in listOf(idx[i] to idx[i + 1], idx[i + 1] to idx[i + 2], idx[i + 2] to idx[i])) {
					g.drawLine(((p[a * 2] - box[0]) * scale).toInt(), ((p[a * 2 + 1] - box[1]) * scale).toInt(), ((p[b * 2] - box[0]) * scale).toInt(), ((p[b * 2 + 1] - box[1]) * scale).toInt())
				}
				i += 3
			}
		}
		g.dispose()
		ImageIO.write(image, "png", File(out, "$name.png"))
	}
}
