package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind

/**
 * One value of a modifier's setting: a constant, or keyed on parameters as a keyform grid is - a dense product of
 * [axes] (axis 0 fastest) with one value per combination, multilinear between keys and held past the ends. Every
 * value has the setting's component count ([ModifierSetting.size]).
 */
sealed interface ModifierValue {
	val size: Int

	/** The parameters this value is keyed on; none for a constant. */
	val parameters: List<String>

	/** This value where [pose] gives each parameter's value. */
	fun at(pose: (String) -> Float): FloatArray

	data class Constant(val value: FloatArray) : ModifierValue {
		override val size get() = value.size
		override val parameters get() = emptyList<String>()
		override fun at(pose: (String) -> Float) = value.copyOf()
		override fun equals(other: Any?) = other is Constant && value.contentEquals(other.value)
		override fun hashCode() = value.contentHashCode()
		override fun toString() = "Constant(${value.joinToString()})"
	}

	/** Keyed on [axes]; [values] has one entry per combination of their keys, axis 0 fastest. */
	class Keyed(val axes: List<Axis>, val values: List<FloatArray>) : ModifierValue {
		class Axis(val parameter: String, val keys: FloatArray) {
			init {
				require(parameter.isNotBlank()) { "A keyed setting names its parameter" }
				require(keys.isNotEmpty() && keys.all(Float::isFinite) && keys.asList().zipWithNext().all { (a, b) -> a < b }) {
					"Keys of $parameter must be finite and increasing"
				}
			}
			override fun equals(other: Any?) = other is Axis && parameter == other.parameter && keys.contentEquals(other.keys)
			override fun hashCode() = parameter.hashCode() * 31 + keys.contentHashCode()
		}

		init {
			require(axes.isNotEmpty() && axes.map { it.parameter }.distinct().size == axes.size) { "A keyed setting names each parameter once" }
			require(values.size == axes.fold(1) { n, a -> n * a.keys.size }) { "A keyed setting has one value per key combination" }
			require(values.isNotEmpty() && values.all { it.size == values.first().size && it.all(Float::isFinite) }) { "Keyed values must be finite and of one size" }
		}

		override val size get() = values.first().size
		override val parameters get() = axes.map { it.parameter }

		override fun at(pose: (String) -> Float): FloatArray {
			val out = FloatArray(size)
			// Each axis contributes the two keys around its value with their weights.
			val lower = IntArray(axes.size)
			val fraction = FloatArray(axes.size)
			for ((a, axis) in axes.withIndex()) {
				val keys = axis.keys
				val v = pose(axis.parameter)
				when {
					keys.size == 1 || v <= keys.first() -> { lower[a] = 0; fraction[a] = 0f }
					v >= keys.last() -> { lower[a] = keys.size - 2; fraction[a] = 1f }
					else -> {
						val i = keys.indexOfLast { it <= v }.coerceAtMost(keys.size - 2)
						lower[a] = i
						fraction[a] = (v - keys[i]) / (keys[i + 1] - keys[i])
					}
				}
			}
			for (corner in 0 until (1 shl axes.size)) {
				var weight = 1f
				var index = 0
				var stride = 1
				for ((a, axis) in axes.withIndex()) {
					val upper = (corner shr a) and 1 == 1
					val k = if (axis.keys.size == 1) 0 else lower[a] + if (upper) 1 else 0
					weight *= if (axis.keys.size == 1) (if (upper) 0f else 1f) else if (upper) fraction[a] else 1 - fraction[a]
					index += k * stride
					stride *= axis.keys.size
				}
				if (weight == 0f) continue
				val value = values[index]
				for (c in out.indices) out[c] += value[c] * weight
			}
			return out
		}

		/** This value with [value] at [key] (a value per parameter of [axes] plus any new ones), adding axes and keys as needed. */
		fun withKey(key: Map<String, Float>, value: FloatArray): Keyed = keyed(axes, values, key, value)

		/** This value without the keys at [key]'s values; null when no key is left, so the setting becomes a constant. */
		fun withoutKey(key: Map<String, Float>): ModifierValue? {
			var next: Keyed = this
			for ((parameter, at) in key) {
				val a = next.axes.indexOfFirst { it.parameter == parameter }
				if (a < 0) continue
				val axis = next.axes[a]
				val k = axis.keys.indexOfFirst { kotlin.math.abs(it - at) <= EPS }
				if (k < 0) continue
				if (axis.keys.size == 1) {
					// The last key of an axis: the value no longer varies along it.
					val axes = next.axes.filterIndexed { i, _ -> i != a }
					if (axes.isEmpty()) return Constant(next.values.first().copyOf())
					next = Keyed(axes, next.values.filterIndexed { i, _ -> coordinate(next.axes, i)[a] == 0 })
					continue
				}
				val keys = axis.keys.filterIndexed { i, _ -> i != k }.toFloatArray()
				val axes = next.axes.toMutableList().also { it[a] = Axis(parameter, keys) }
				next = Keyed(axes, next.values.filterIndexed { i, _ -> coordinate(next.axes, i)[a] != k })
			}
			return next
		}

		override fun equals(other: Any?) = other is Keyed && axes == other.axes && values.size == other.values.size &&
			values.indices.all { values[it].contentEquals(other.values[it]) }
		override fun hashCode() = axes.hashCode() * 31 + values.fold(0) { h, v -> h * 31 + v.contentHashCode() }
		override fun toString() = "Keyed(${axes.joinToString { "${it.parameter}=${it.keys.joinToString()}" }}; ${values.joinToString { it.joinToString() }})"
	}

	companion object {
		/** Keys this close are one key, as the evaluator snaps a value onto a keyform key. */
		const val EPS = 0.001f

		private fun coordinate(axes: List<Keyed.Axis>, index: Int): IntArray {
			var rest = index
			return IntArray(axes.size) { a -> (rest % axes[a].keys.size).also { rest /= axes[a].keys.size } }
		}

		/**
		 * [value] keyed at [key]: on an existing grid of [axes] and [values] (or none, from a constant), each named
		 * parameter becomes an axis with that key, the cells a new key adds take what the grid shows there, and the
		 * cell at [key] takes [value]; along the axes the key leaves out, every key takes it.
		 */
		internal fun keyed(axes: List<Keyed.Axis>, values: List<FloatArray>, key: Map<String, Float>, value: FloatArray): Keyed {
			require(key.isNotEmpty()) { "A key names at least one parameter" }
			val current: (Map<String, Float>) -> FloatArray =
				if (axes.isEmpty()) { _ -> values.first().copyOf() } else { pose -> Keyed(axes, values).at { pose[it] ?: Float.NaN } }
			val nextAxes = axes.map { axis ->
				val at = key[axis.parameter]
				if (at == null || axis.keys.any { kotlin.math.abs(it - at) <= EPS }) axis
				else Keyed.Axis(axis.parameter, (axis.keys.toList() + at).sorted().toFloatArray())
			} + key.filterKeys { p -> axes.none { it.parameter == p } }.map { (p, at) -> Keyed.Axis(p, floatArrayOf(at)) }
			val count = nextAxes.fold(1) { n, a -> n * a.keys.size }
			val nextValues = List(count) { i ->
				val c = coordinate(nextAxes, i)
				val pose = nextAxes.withIndex().associate { (a, axis) -> axis.parameter to axis.keys[c[a]] }
				val onKey = key.all { (p, at) -> kotlin.math.abs((pose[p] ?: Float.NaN) - at) <= EPS }
				if (onKey) value.copyOf() else current(pose)
			}
			return Keyed(nextAxes, nextValues)
		}

		fun toJson(value: ModifierValue): JsonElement = when (value) {
			is Constant -> floats(value.value)
			is Keyed -> buildJsonObject {
				putJsonArray("axes") {
					value.axes.forEach { a -> addJsonObject { put("parameter", a.parameter); put("keys", floats(a.keys)) } }
				}
				put("values", JsonArray(value.values.map(::floats)))
			}
		}

		fun fromJson(element: JsonElement): ModifierValue = when (element) {
			is JsonArray -> Constant(element.map { it.jsonPrimitive.float }.toFloatArray())
			is JsonPrimitive -> Constant(floatArrayOf(element.float))
			is JsonObject -> Keyed(
				element.getValue("axes").jsonArray.map { a ->
					a.jsonObject.let { Keyed.Axis(it.getValue("parameter").jsonPrimitive.content, it.getValue("keys").jsonArray.map { k -> k.jsonPrimitive.float }.toFloatArray()) }
				},
				element.getValue("values").jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() },
			)
		}

		private fun floats(a: FloatArray) = JsonArray(a.map(::JsonPrimitive))
	}
}

/**
 * Where a modifier acts: these [meshes], or every mesh under [part] (its sub-parts included) but the [excluded] ones -
 * a mesh moved into the part joins, one moved out leaves. Each mesh is keyed on its own; the settings are shared.
 */
data class ModifierTarget(val meshes: List<String> = emptyList(), val part: String? = null, val excluded: Set<String> = emptySet(),
                          /** Warp deformers: the modifier moves their control points, and so all they carry. */
                          val warps: List<String> = emptyList()) {
	init {
		require(meshes.distinct().size == meshes.size && meshes.all { it.isNotBlank() }) { "A modifier names distinct meshes" }
		require(warps.distinct().size == warps.size && warps.all { it.isNotBlank() }) { "A modifier names distinct warps" }
		require(if (part == null) (meshes.isNotEmpty() || warps.isNotEmpty()) && excluded.isEmpty() else meshes.isEmpty() && warps.isEmpty() && part.isNotBlank()) {
			"A modifier names one or more meshes or warps, or one part"
		}
	}

	/** What this names on [model] as stack keys: its meshes by ID, its warps as `warp:<id>`. */
	fun keys(model: PuppetModel?): List<String> = resolve(model) + warps.map { WARP + it }

	companion object {
		/** The stack key of a warp target. */
		const val WARP = "warp:"
	}

	/** The meshes this names on [model]: a part's at the time; none for a part without [model]. */
	fun resolve(model: PuppetModel?): List<String> {
		if (part == null) return meshes
		val parts = model?.parts?.associateBy { it.id.raw } ?: return emptyList()
		val out = ArrayList<String>()
		fun walk(id: String, seen: MutableSet<String>) {
			if (!seen.add(id)) return
			for (child in parts[id]?.children.orEmpty()) when (child) {
				is OrgChild.Drawable -> if (child.id.raw !in excluded) out += child.id.raw
				is OrgChild.Part -> walk(child.id.raw, seen)
			}
		}
		walk(part, HashSet())
		return out.filter { id -> model.drawables.any { it.id.raw == id && it.mesh != null } }
	}

	/** This target without [mesh]; null when nothing would be left. */
	fun without(mesh: String): ModifierTarget? = when {
		part != null -> copy(excluded = excluded + mesh)
		mesh.startsWith(WARP) -> copy(warps = warps - mesh.removePrefix(WARP)).takeIf { it.meshes.isNotEmpty() || it.warps.isNotEmpty() }
		else -> copy(meshes = meshes - mesh).takeIf { it.meshes.isNotEmpty() || it.warps.isNotEmpty() }
	}
}

/** A vertex group limiting the modifier per vertex: its weight, or one minus it when [invert]. */
data class ModifierWeights(val group: String, val invert: Boolean = false) {
	init { require(group.isNotBlank()) { "A vertex group is named" } }
}

/**
 * How finely the modifier's effect is sampled into keyforms: keys are added between a driving parameter's keys until
 * linear interpolation is within [tolerance] canvas pixels of the modifier, at most [maxKeys] per parameter.
 */
data class ModifierSampling(val tolerance: Float = DEFAULT_TOLERANCE, val maxKeys: Int = DEFAULT_MAX_KEYS) {
	init {
		require(tolerance.isFinite() && tolerance in 0.05f..64f) { "Sampling tolerance is 0.05-64 canvas pixels" }
		require(maxKeys in 2..MAX_KEYS) { "A parameter takes 2-$MAX_KEYS keys" }
	}

	companion object {
		const val DEFAULT_TOLERANCE = 1f
		const val DEFAULT_MAX_KEYS = 9
		const val MAX_KEYS = 33
	}
}

/**
 * How a target's stack is written: merged into its own keyforms, as a warp of its own between it and its parent
 * ([ModifierStage]), or whichever keeps the keyforms fewer ([AUTO]).
 */
enum class ModifierOutput(val id: String) { AUTO("auto"), MERGE("merge"), STAGE("stage") }

/**
 * A non-destructive modifier ([ModifierKinds]): a pure function of its targets' rest shape and its settings, run
 * after the journal and sampled into ordinary mesh keyforms on the parameters its settings are keyed on
 * ([ModifierSampler]). The document keeps the recipe only; the keyforms are made anew on every build, so a mesh edit
 * or remesh simply samples again. Modifiers on one mesh chain in the document's order: each takes the shape the ones
 * before it made.
 */
class RigModifier(
	val id: String,
	val kind: String,
	val name: String,
	val target: ModifierTarget,
	val enabled: Boolean = true,
	val settings: Map<String, ModifierValue> = emptyMap(),
	/** Parameters the modifier made for itself; it owns them and they leave with it unless something else uses them. */
	val created: List<Parameter> = emptyList(),
	val weights: ModifierWeights? = null,
	val sampling: ModifierSampling = ModifierSampling(),
	/** The kind's text choices ([ModifierKind.options]): a parameter, another target, a side. */
	val options: Map<String, String> = emptyMap(),
	val output: ModifierOutput = ModifierOutput.AUTO,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl) && id.none(Char::isWhitespace)) { "Invalid modifier ID" }
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "A modifier has a name" }
		val definition = requireNotNull(ModifierKinds.of(kind)) { "Unknown modifier kind: $kind" }
		for ((key, value) in settings) {
			val setting = requireNotNull(definition.settings.firstOrNull { it.key == key }) { "$kind has no setting $key" }
			require(value.size == setting.size) { "$kind $key takes ${setting.size} value(s)" }
		}
		require(created.map { it.id }.distinct().size == created.size) { "Created parameters are distinct" }
		for ((key, value) in options) {
			val option = requireNotNull(definition.options.firstOrNull { it.key == key }) { "$kind has no option $key" }
			require(value.isNotBlank() && value.none(Char::isISOControl)) { "$kind $key is a name" }
			require(option.choices.isEmpty() || value in option.choices) { "$kind $key is one of ${option.choices.joinToString()}" }
		}
	}

	val definition: ModifierKind get() = ModifierKinds.of(kind)!!

	/** The parameters it is sampled on without a model to look at: those its settings are keyed on, then those its kind names. */
	val drivers: List<String> get() = drivers(null)

	/** The parameters it is sampled on in [model]: those its settings are keyed on, in first-use order, then those its kind reads. */
	fun drivers(model: PuppetModel?): List<String> = (settings.values.flatMap { it.parameters } + definition.drivers(options, model)).distinct()

	/** Setting [key] as stored, or the kind's default. */
	fun value(key: String): ModifierValue = settings[key] ?: ModifierValue.Constant(definition.settings.first { it.key == key }.default.copyOf())

	fun copy(
		name: String = this.name, target: ModifierTarget = this.target, enabled: Boolean = this.enabled,
		settings: Map<String, ModifierValue> = this.settings, created: List<Parameter> = this.created,
		weights: ModifierWeights? = this.weights, sampling: ModifierSampling = this.sampling, options: Map<String, String> = this.options,
		output: ModifierOutput = this.output,
	) = RigModifier(id, kind, name, target, enabled, settings, created, weights, sampling, options, output)

	private val json: JsonObject by lazy {
		buildJsonObject {
			put("schema", SCHEMA)
			put("id", id)
			put("kind", kind)
			put("name", name)
			putJsonArray("targets") { target.meshes.forEach { add(it) } }
			target.part?.let { put("part", it) }
			if (target.warps.isNotEmpty()) putJsonArray("warps") { target.warps.forEach { add(it) } }
			if (target.excluded.isNotEmpty()) putJsonArray("excluded") { target.excluded.sorted().forEach { add(it) } }
			put("enabled", enabled)
			putJsonObject("settings") { settings.toSortedMap().forEach { (k, v) -> put(k, ModifierValue.toJson(v)) } }
			if (created.isNotEmpty()) putJsonArray("created") { created.forEach { add(parameterJson(it)) } }
			weights?.let { w -> putJsonObject("weights") { put("group", w.group); if (w.invert) put("invert", true) } }
			if (sampling != ModifierSampling()) putJsonObject("sampling") { put("tolerance", sampling.tolerance); put("maxKeys", sampling.maxKeys) }
			if (options.isNotEmpty()) putJsonObject("options") { options.toSortedMap().forEach { (k, v) -> put(k, v) } }
			if (output != ModifierOutput.AUTO) put("output", output.id)
		}
	}

	fun toJson(): JsonObject = json

	override fun equals(other: Any?) = other is RigModifier && json == other.json
	override fun hashCode() = json.hashCode()
	override fun toString() = "RigModifier($json)"

	companion object {
		const val SCHEMA = 1

		fun parameterJson(p: Parameter) = buildJsonObject {
			put("id", p.id.raw); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default)
			if (p.kind != ParameterKind.NORMAL) put("kind", p.kind.name)
		}

		fun parameter(o: JsonObject) = Parameter(
			ParameterId(o.getValue("id").jsonPrimitive.content), o.getValue("name").jsonPrimitive.content,
			o.getValue("min").jsonPrimitive.float, o.getValue("max").jsonPrimitive.float, o.getValue("default").jsonPrimitive.float,
			o["kind"]?.jsonPrimitive?.content?.let(ParameterKind::valueOf) ?: ParameterKind.NORMAL,
		)

		fun fromJson(o: JsonObject): RigModifier {
			require(o.getValue("schema").jsonPrimitive.int == SCHEMA) { "Unsupported modifier schema" }
			return RigModifier(
				o.getValue("id").jsonPrimitive.content,
				o.getValue("kind").jsonPrimitive.content,
				o.getValue("name").jsonPrimitive.content,
				ModifierTarget(o.getValue("targets").jsonArray.map { it.jsonPrimitive.content }, o["part"]?.jsonPrimitive?.content,
					o["excluded"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
					o["warps"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()),
				o.getValue("enabled").jsonPrimitive.boolean,
				o.getValue("settings").jsonObject.mapValues { ModifierValue.fromJson(it.value) },
				o["created"]?.jsonArray?.map { parameter(it.jsonObject) }.orEmpty(),
				o["weights"]?.jsonObject?.let { ModifierWeights(it.getValue("group").jsonPrimitive.content, it["invert"]?.jsonPrimitive?.boolean ?: false) },
				o["sampling"]?.jsonObject?.let { ModifierSampling(it.getValue("tolerance").jsonPrimitive.float, it.getValue("maxKeys").jsonPrimitive.int) } ?: ModifierSampling(),
				o["options"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
				o["output"]?.jsonPrimitive?.content?.let { id -> ModifierOutput.entries.first { it.id == id } } ?: ModifierOutput.AUTO,
			)
		}
	}
}
