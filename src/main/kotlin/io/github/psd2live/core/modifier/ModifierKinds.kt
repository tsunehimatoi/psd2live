package io.github.psd2live.core.modifier

import io.github.psd2live.core.SemanticTag
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

/**
 * One adjustable value of a modifier kind: [size] components, [default] until set, within [min]..[max] when bounded.
 * [field] is false for a value edited on the canvas only, which the panel leaves out.
 */
class ModifierSetting(
	val key: String,
	val size: Int,
	val default: FloatArray,
	val min: Float = Float.NEGATIVE_INFINITY,
	val max: Float = Float.POSITIVE_INFINITY,
	/** What the value means, for the MCP schema. */
	val description: String,
	val field: Boolean = true,
) {
	init { require(default.size == size) }
}

/**
 * A choice a kind takes as text rather than numbers: a parameter ([Type.PARAMETER]), another mesh or warp
 * ([Type.TARGET]) or one of [choices]. A [required] option has no default; the others are left out when unset.
 */
class ModifierOption(val key: String, val type: Type, val description: String, val required: Boolean = false, val choices: List<String> = emptyList()) {
	enum class Type { PARAMETER, TARGET, CHOICE }
}

/** Where a kind sits in the add menu; a new kind joins one of these. */
enum class ModifierCategory(val id: String) { SYMMETRY("symmetry"), CORRECTION("correction") }

/**
 * What a kind reads while it is evaluated at one [pose] (every parameter given) on [target] (a mesh's ID or
 * `warp:<id>`): the shape the entries before it on the same target make at any pose, the target's triangles to read
 * a field between its points, and other targets' shapes as the model poses them. Shapes are canvas positions
 * (interleaved x, y, y down), each target taken through its parents at the default pose, as the sampler takes them.
 */
class ModifierContext internal constructor(
	val model: PuppetModel,
	val target: String,
	val pose: Map<String, Float>,
	private val beforeAt: (Map<String, Float>) -> FloatArray,
	private val shapes: (String, Map<String, Float>) -> FloatArray?,
) {
	private val defaults: Map<String, Float> by lazy { model.parameters.associate { it.id.raw to it.default } }

	/** [pose] over the parameters' defaults. */
	fun complete(pose: Map<String, Float>): Map<String, Float> = if (pose.keys.containsAll(defaults.keys)) pose else defaults + pose

	/** The shape the entries before this one make at [pose]. */
	fun before(pose: Map<String, Float> = this.pose): FloatArray = beforeAt(complete(pose))

	/** The target's triangles as index triples over its points. */
	val triangles: IntArray by lazy { ModifierSampler.triangles(model, target) ?: IntArray(0) }

	/** [key]'s shape at [pose] as the model poses it, without any stack; null when it is not a mesh or warp of the model. */
	fun shapeOf(key: String, pose: Map<String, Float> = this.pose): FloatArray? = shapes(key, complete(pose))

	fun trianglesOf(key: String): IntArray? = ModifierSampler.triangles(model, key)

	fun parameter(id: String): Parameter? = model.parameters.firstOrNull { it.id.raw == id }
}

/**
 * A kind of modifier: its settings and options, and what it makes of its target at one pose ([deform]). It is
 * evaluated exactly while the user edits and sampled into keyforms on commit; an export carries only the keyforms.
 * Every kind leaves the target as the entries before it made it at its [defaults], so a new modifier moves nothing
 * until it is set up.
 */
interface ModifierKind {
	val id: String
	val category: ModifierCategory
	val settings: List<ModifierSetting>
	val options: List<ModifierOption> get() = emptyList()

	/** The target's shape at [context]'s pose, from what the entries before it make; [values] are the settings there. A new array. */
	fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray

	/** Values for a new modifier on targets whose rest canvas bounds are [bounds] (min x, min y, max x, max y). */
	fun defaults(bounds: FloatArray): Map<String, FloatArray> = emptyMap()

	/** The parameters it is sampled on besides those its settings are keyed on; some depend on [model] (another target's axes). */
	fun drivers(options: Map<String, String>, model: PuppetModel?): List<String> = emptyList()

	/** Keys to sample [parameter] at besides its own and the target's: where its result changes over [own], the target's keys on it. */
	fun keys(options: Map<String, String>, parameter: Parameter, own: FloatArray): List<Float> = emptyList()

	/** The layer roles this kind is offered first for, in the add menu. */
	val suits: Set<SemanticTag> get() = emptySet()

	/**
	 * Values for options a new modifier on [targets] is not given: [parameters] are the document's, [keyed] those its
	 * targets are keyed on, [model] the rig they are in.
	 */
	fun defaultOptions(model: PuppetModel, targets: List<String>, parameters: List<Parameter>, keyed: Set<String>): Map<String, String> = emptyMap()
}

/** The registry of modifier kinds. */
object ModifierKinds {
	private val builtIn: List<ModifierKind> = listOf(Mirror, Follow, Corner, Turn3d)
	private val registered = java.util.concurrent.ConcurrentHashMap<String, ModifierKind>()

	fun of(id: String): ModifierKind? = builtIn.firstOrNull { it.id == id } ?: registered[id]

	val all: List<ModifierKind> get() = builtIn + registered.values.sortedBy { it.id }

	/** Adds a kind outside the built-in ones; tests use it for kinds of their own. */
	internal fun register(kind: ModifierKind) { require(builtIn.none { it.id == kind.id }); registered[kind.id] = kind }

	/** The kinds offered first for a layer of [role], then the rest, each in registry order. */
	fun forRole(role: SemanticTag?): List<ModifierKind> = all.sortedBy { if (role != null && role in it.suits) 0 else 1 }

	/**
	 * Mirrors one side of a parameter onto the other: only the source side is keyed, and the far side is the mirror
	 * image of the near one about a vertical line at `axis`. At a pose on the mirrored side the target is its shape
	 * with the parameter at its default, plus the source side's displacement there - read at the mirror point of each
	 * vertex and flipped across the line - so it holds for asymmetric meshes too: the displacement field is mirrored,
	 * not the vertices. The sides meet at the default; a parameter's two halves of unequal length map in proportion.
	 *
	 * With `source` the displacement comes from another mesh or warp instead (a left eye for the right): the target
	 * takes the source's displacement at the same value of `source_parameter` (the same parameter by default), mirrored,
	 * over the whole range.
	 */
	object Mirror : ModifierKind {
		override val id = "mirror"
		override val category = ModifierCategory.SYMMETRY
		override val settings = listOf(
			ModifierSetting("axis", 1, floatArrayOf(0f), description = "Canvas x of the vertical mirror line; a new modifier takes its targets' rest centre."),
		)
		override val options = listOf(
			ModifierOption("parameter", ModifierOption.Type.PARAMETER, "The parameter whose source side is mirrored onto its other side.", required = true),
			ModifierOption("side", ModifierOption.Type.CHOICE, "The side that is kept and mirrored: positive (above the default, the default) or negative.",
				choices = listOf("positive", "negative")),
			ModifierOption("source", ModifierOption.Type.TARGET, "Another mesh or warp:<id> whose displacement is mirrored onto the target, over the whole range."),
			ModifierOption("source_parameter", ModifierOption.Type.PARAMETER, "With source: the source's parameter read at the target parameter's value (default: the same)."),
		)
		override val suits = setOf(SemanticTag.FACE, SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEBROW, SemanticTag.FRONT_HAIR)

		override fun defaults(bounds: FloatArray) = mapOf("axis" to floatArrayOf((bounds[0] + bounds[2]) / 2))

		/** The first two-sided parameter the targets are keyed on, Angle X before any other; else any. */
		override fun defaultOptions(model: PuppetModel, targets: List<String>, parameters: List<Parameter>, keyed: Set<String>): Map<String, String> {
			val sided = parameters.filter { it.min < it.default && it.default < it.max }
			fun pick(from: List<Parameter>) = from.firstOrNull { it.id.raw == "ParamAngleX" } ?: from.firstOrNull()
			return (pick(sided.filter { it.id.raw in keyed }) ?: pick(sided))?.let { mapOf("parameter" to it.id.raw) }.orEmpty()
		}


		override fun drivers(options: Map<String, String>, model: PuppetModel?) = listOfNotNull(options["parameter"])

		override fun keys(options: Map<String, String>, parameter: Parameter, own: FloatArray): List<Float> {
			if (options["source"] != null || parameter.id.raw != options["parameter"]) return emptyList()
			// Each key on the kept side has its image on the mirrored one.
			return own.map { mirrored(parameter, it) }
		}

		/** [value]'s image across [p]'s default, its two halves mapped in proportion. */
		internal fun mirrored(p: Parameter, value: Float): Float = when {
			value > p.default && p.default > p.min -> p.default - (value - p.default) / (p.max - p.default) * (p.default - p.min)
			value < p.default && p.max > p.default -> p.default + (p.default - value) / (p.default - p.min) * (p.max - p.default)
			else -> p.default
		}

		override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
			val axis = values.getValue("axis")[0]
			val parameter = options["parameter"]?.let(context::parameter) ?: return context.before()
			val id = parameter.id.raw
			val v = context.pose[id] ?: parameter.default
			val rest = context.pose + (id to parameter.default)
			val base = context.before(rest)
			val source = options["source"]
			val (points, field, triangles) = if (source == null) {
				val positive = options["side"] != "negative"
				val mirroredSide = if (positive) v < parameter.default - ModifierValue.EPS else v > parameter.default + ModifierValue.EPS
				if (!mirroredSide) return context.before()
				val kept = context.before(context.pose + (id to mirrored(parameter, v)))
				Triple(base, FloatArray(base.size) { kept[it] - base[it] }, context.triangles)
			} else {
				val read = options["source_parameter"] ?: id
				val sourceDefault = context.parameter(read)?.default ?: return context.before()
				val at = context.shapeOf(source, context.pose + (read to v)) ?: return context.before()
				val still = context.shapeOf(source, context.pose + (read to sourceDefault)) ?: return context.before()
				Triple(still, FloatArray(at.size) { at[it] - still[it] }, context.trianglesOf(source) ?: IntArray(0))
			}
			val reader = PointField(points, triangles)
			val out = base.copyOf()
			val d = FloatArray(2)
			for (i in 0 until base.size / 2) {
				reader.sample(field, 2 * axis - base[i * 2], base[i * 2 + 1], d)
				out[i * 2] = base[i * 2] - d[0]
				out[i * 2 + 1] = base[i * 2 + 1] + d[1]
			}
			return out
		}
	}
}

/** The first of [ids] that [parameters] has and [keyed] keys, else the first it has. */
private fun preferred(parameters: List<Parameter>, keyed: Set<String>, vararg ids: String): String? =
	ids.firstOrNull { id -> id in keyed && parameters.any { it.id.raw == id } } ?: ids.firstOrNull { id -> parameters.any { it.id.raw == id } }

/**
 * Follows another mesh or warp (the host) as it moves: each point is bound at rest to where it sits on the host's
 * triangles, and takes the host's displacement there at every pose, so line art, shading and accessories go with the
 * shape they are drawn over without being keyed again. It reads the host as its own keyforms pose it.
 */
object Follow : ModifierKind {
	override val id = "follow"
	override val category = ModifierCategory.SYMMETRY
	override val settings = listOf(ModifierSetting("amount", 1, floatArrayOf(1f), 0f, 1f, "How much of the host's movement it takes, 0-1."))
	override val options = listOf(ModifierOption("host", ModifierOption.Type.TARGET, "The mesh or warp:<id> it follows.", required = true))
	override val suits = setOf(SemanticTag.FACE_DETAIL, SemanticTag.EYEWEAR, SemanticTag.HEADWEAR, SemanticTag.NECKWEAR, SemanticTag.OBJECTS)

	override fun drivers(options: Map<String, String>, model: PuppetModel?): List<String> {
		val host = options["host"] ?: return emptyList()
		model ?: return emptyList()
		return if (host.startsWith(ModifierTarget.WARP)) (model.deformers.firstOrNull { it.id.raw == host.removePrefix(ModifierTarget.WARP) } as? org.umamo.runtime.model.Deformer.Warp)
			?.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }
		else model.drawables.firstOrNull { it.id.raw == host }?.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }
	}

	/** The smallest other mesh whose rest bounds hold the targets' centre: what they are drawn over. */
	override fun defaultOptions(model: PuppetModel, targets: List<String>, parameters: List<Parameter>, keyed: Set<String>): Map<String, String> {
		val own = targets.mapNotNull { ModifierSampler.restCanvas(model, it) }.reduceOrNull { a, b -> a + b } ?: return emptyMap()
		val cx = (0 until own.size / 2).map { own[it * 2] }.average().toFloat()
		val cy = (0 until own.size / 2).map { own[it * 2 + 1] }.average().toFloat()
		val host = model.drawables.filter { it.mesh != null && it.id.raw !in targets }.mapNotNull { d ->
			val r = ModifierSampler.restCanvas(model, d.id.raw) ?: return@mapNotNull null
			val xs = (0 until r.size / 2).map { r[it * 2] }; val ys = (0 until r.size / 2).map { r[it * 2 + 1] }
			if (cx !in xs.min()..xs.max() || cy !in ys.min()..ys.max()) null else d.id.raw to (xs.max() - xs.min()) * (ys.max() - ys.min())
		}.minByOrNull { it.second }?.first
		return host?.let { mapOf("host" to it) }.orEmpty()
	}

	override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
		val before = context.before()
		val host = options["host"] ?: return before
		val amount = values.getValue("amount")[0]
		val rest = context.model.parameters.associate { it.id.raw to it.default }
		val hostRest = context.shapeOf(host, rest) ?: return before
		val hostNow = context.shapeOf(host) ?: return before
		val reader = PointField(hostRest, context.trianglesOf(host) ?: IntArray(0))
		val field = FloatArray(hostRest.size) { hostNow[it] - hostRest[it] }
		val at = context.before(rest)
		val d = FloatArray(2)
		return FloatArray(before.size).also { out ->
			for (i in 0 until before.size / 2) {
				reader.sample(field, at[i * 2], at[i * 2 + 1], d)
				out[i * 2] = before[i * 2] + amount * d[0]; out[i * 2 + 1] = before[i * 2 + 1] + amount * d[1]
			}
		}
	}
}

/**
 * Corrects the corner poses of two parameters keyed one at a time: where both are off their defaults, the shape becomes
 * what the two make on their own added together - `S(x, 0) + S(0, y) − S(0, 0)` - instead of what the corner cells
 * held, scaled by `amount` (1 exactly the sum, more pushes past it). On either axis alone it changes nothing.
 */
object Corner : ModifierKind {
	override val id = "corner"
	override val category = ModifierCategory.CORRECTION
	override val settings = listOf(ModifierSetting("amount", 1, floatArrayOf(1f), 0f, 2f, "How far the corners go toward the two axes' sum: 0 none, 1 exactly."))
	override val options = listOf(
		ModifierOption("x", ModifierOption.Type.PARAMETER, "The first parameter.", required = true),
		ModifierOption("y", ModifierOption.Type.PARAMETER, "The second parameter.", required = true),
	)
	override val suits = setOf(SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.HEADWEAR)

	override fun drivers(options: Map<String, String>, model: PuppetModel?) = listOfNotNull(options["x"], options["y"])

	override fun defaultOptions(model: PuppetModel, targets: List<String>, parameters: List<Parameter>, keyed: Set<String>): Map<String, String> {
		val x = preferred(parameters, keyed, "ParamAngleX", "ParamBodyAngleX") ?: return emptyMap()
		val y = preferred(parameters, keyed, "ParamAngleY", "ParamBodyAngleY") ?: return emptyMap()
		return mapOf("x" to x, "y" to y)
	}

	override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
		val now = context.before()
		val px = options["x"]?.let(context::parameter) ?: return now
		val py = options["y"]?.let(context::parameter) ?: return now
		val x = context.pose[px.id.raw] ?: px.default
		val y = context.pose[py.id.raw] ?: py.default
		if (kotlin.math.abs(x - px.default) <= ModifierValue.EPS || kotlin.math.abs(y - py.default) <= ModifierValue.EPS) return now
		val amount = values.getValue("amount")[0]
		val onX = context.before(context.pose + (py.id.raw to py.default))
		val onY = context.before(context.pose + (px.id.raw to px.default))
		val neither = context.before(context.pose + (px.id.raw to px.default) + (py.id.raw to py.default))
		return FloatArray(now.size) { now[it] + amount * (onX[it] + onY[it] - neither[it] - now[it]) }
	}
}

/**
 * Turns its targets as a solid in depth: each point at (x − cx, y − cy, z), z the depth plus a dome of `curvature`,
 * is turned about the vertical axis by the first parameter (Angle X by default) and about the horizontal one by the
 * second, then seen in perspective from `distance` pixels; the difference from how it is seen unturned is the
 * displacement. Things in front move further and the far side narrows, as a turned head, hat or held object does.
 */
object Turn3d : ModifierKind {
	override val id = "turn3d"
	override val category = ModifierCategory.CORRECTION
	override val settings = listOf(
		ModifierSetting("center", 2, floatArrayOf(0f, 0f), description = "The turn's centre [x, y] on the canvas; a new modifier takes its targets' rest centre."),
		ModifierSetting("depth", 1, floatArrayOf(0f), -10_000f, 10_000f, "How far in front of the centre the targets sit, canvas pixels."),
		ModifierSetting("curvature", 1, floatArrayOf(0f), 0f, 1f, "How much the targets bulge toward the viewer at their middle, as part of their radius."),
		ModifierSetting("angle", 1, floatArrayOf(30f), -90f, 90f, "Degrees turned at the parameters' ends."),
		ModifierSetting("distance", 1, floatArrayOf(3000f), 100f, 100_000f, "How far the viewer is, canvas pixels: nearer draws more perspective."),
		ModifierSetting("radius", 1, floatArrayOf(100f), 1f, 100_000f, "The radius the curvature is measured on; a new modifier takes its targets'."),
	)
	override val options = listOf(
		ModifierOption("x", ModifierOption.Type.PARAMETER, "The parameter turning about the vertical axis.", required = true),
		ModifierOption("y", ModifierOption.Type.PARAMETER, "The parameter turning about the horizontal axis (up and down)."),
	)
	override val suits = setOf(SemanticTag.HEADWEAR, SemanticTag.EARWEAR, SemanticTag.EYEWEAR, SemanticTag.OBJECTS, SemanticTag.EARS)

	override fun defaults(bounds: FloatArray) = mapOf(
		"center" to floatArrayOf((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2),
		"radius" to floatArrayOf(maxOf(1f, maxOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2)),
	)

	override fun drivers(options: Map<String, String>, model: PuppetModel?) = listOfNotNull(options["x"], options["y"])

	override fun defaultOptions(model: PuppetModel, targets: List<String>, parameters: List<Parameter>, keyed: Set<String>): Map<String, String> =
		listOfNotNull(preferred(parameters, keyed, "ParamAngleX")?.let { "x" to it }, preferred(parameters, keyed, "ParamAngleY")?.let { "y" to it }).toMap()

	override fun deform(context: ModifierContext, values: Map<String, FloatArray>, options: Map<String, String>): FloatArray {
		val before = context.before()
		val center = values.getValue("center")
		val depth = values.getValue("depth")[0]
		val curvature = values.getValue("curvature")[0]
		val angle = values.getValue("angle")[0]
		val distance = values.getValue("distance")[0].toDouble()
		val radius = values.getValue("radius")[0].toDouble()
		fun turn(id: String?): Double {
			val p = id?.let(context::parameter) ?: return 0.0
			val v = context.pose[p.id.raw] ?: p.default
			val t = if (v >= p.default) (v - p.default) / (p.max - p.default).coerceAtLeast(1e-6f) else (v - p.default) / (p.default - p.min).coerceAtLeast(1e-6f)
			return Math.toRadians((t * angle).toDouble())
		}
		val yaw = turn(options["x"])
		val pitch = -turn(options["y"])
		if (yaw == 0.0 && pitch == 0.0) return before
		val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw); val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
		return FloatArray(before.size).also { out ->
			for (i in 0 until before.size / 2) {
				val x = (before[i * 2] - center[0]).toDouble()
				val y = (before[i * 2 + 1] - center[1]).toDouble()
				val r2 = (x * x + y * y) / (radius * radius)
				val z = depth + curvature * radius * (1 - r2).coerceAtLeast(0.0)
				// The point is drawn where its unturned view puts it; the turn moves it by the ratio of the two views.
				val seen = distance / (distance - z)
				val x1 = x * cy + z * sy
				val z1 = -x * sy + z * cy
				val y2 = y * cp - z1 * sp
				val z2 = y * sp + z1 * cp
				val scale = distance / (distance - z2)
				out[i * 2] = (center[0] + x1 * scale / seen).toFloat()
				out[i * 2 + 1] = (center[1] + y2 * scale / seen).toFloat()
			}
		}
	}
}

/**
 * A field known at [points] (interleaved x, y), read anywhere: inside one of [triangles] it is interpolated
 * barycentrically, elsewhere it is the nearest point's. Triangles are bucketed on a grid so a read looks at a few.
 */
internal class PointField(private val points: FloatArray, private val triangles: IntArray) {
	private val count = points.size / 2
	private val minX: Float; private val minY: Float; private val cell: Float; private val columns: Int; private val rows: Int
	private val buckets: Array<IntArray>

	init {
		var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
		for (i in 0 until count) { x0 = minOf(x0, points[i * 2]); x1 = maxOf(x1, points[i * 2]); y0 = minOf(y0, points[i * 2 + 1]); y1 = maxOf(y1, points[i * 2 + 1]) }
		if (count == 0) { x0 = 0f; y0 = 0f; x1 = 1f; y1 = 1f }
		minX = x0; minY = y0
		val side = maxOf(x1 - x0, y1 - y0, 1e-3f)
		val n = kotlin.math.sqrt((triangles.size / 3).toDouble()).toInt().coerceIn(1, 64)
		cell = side / n
		columns = ((x1 - x0) / cell).toInt() + 1; rows = ((y1 - y0) / cell).toInt() + 1
		val lists = Array(columns * rows) { ArrayList<Int>() }
		for (t in 0 until triangles.size / 3) {
			var tx0 = Float.MAX_VALUE; var ty0 = Float.MAX_VALUE; var tx1 = -Float.MAX_VALUE; var ty1 = -Float.MAX_VALUE
			for (k in 0..2) { val p = triangles[t * 3 + k]; tx0 = minOf(tx0, points[p * 2]); tx1 = maxOf(tx1, points[p * 2]); ty0 = minOf(ty0, points[p * 2 + 1]); ty1 = maxOf(ty1, points[p * 2 + 1]) }
			for (cy in row(ty0)..row(ty1)) for (cx in column(tx0)..column(tx1)) lists[cy * columns + cx] += t
		}
		buckets = Array(lists.size) { lists[it].toIntArray() }
	}

	private fun column(x: Float) = ((x - minX) / cell).toInt().coerceIn(0, columns - 1)
	private fun row(y: Float) = ((y - minY) / cell).toInt().coerceIn(0, rows - 1)

	/** [field] (two values per point) at ([x], [y]) into [out]. */
	fun sample(field: FloatArray, x: Float, y: Float, out: FloatArray) {
		if (count == 0) { out[0] = 0f; out[1] = 0f; return }
		val inside = x >= minX && y >= minY && x <= minX + columns * cell && y <= minY + rows * cell
		if (inside) for (t in buckets[row(y) * columns + column(x)]) {
			val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
			val ax = points[a * 2]; val ay = points[a * 2 + 1]
			val bx = points[b * 2] - ax; val by = points[b * 2 + 1] - ay
			val cx = points[c * 2] - ax; val cy = points[c * 2 + 1] - ay
			val det = bx * cy - by * cx
			if (kotlin.math.abs(det) < 1e-9f) continue
			val px = x - ax; val py = y - ay
			val u = (px * cy - py * cx) / det; val v = (bx * py - by * px) / det
			if (u < -1e-4f || v < -1e-4f || u + v > 1 + 1e-4f) continue
			val w = 1 - u - v
			out[0] = w * field[a * 2] + u * field[b * 2] + v * field[c * 2]
			out[1] = w * field[a * 2 + 1] + u * field[b * 2 + 1] + v * field[c * 2 + 1]
			return
		}
		var best = 0; var distance = Float.MAX_VALUE
		for (i in 0 until count) {
			val dx = points[i * 2] - x; val dy = points[i * 2 + 1] - y
			val d = dx * dx + dy * dy
			if (d < distance) { distance = d; best = i }
		}
		out[0] = field[best * 2]; out[1] = field[best * 2 + 1]
	}
}
