package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.ModelIdRemap
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * Cubism's ID conversion as a journal entry: objects take new IDs where the entry replays, and every reference the rig
 * holds follows ([ModelIdRemap]). Entries before it keep the IDs they were recorded with and replay before it; entries
 * after it use the new ones. What replays after the journal - generated overrides and deferred panel edits - is
 * [forwarded] through the conversions recorded after it, and generators that name standard parameters by their
 * standard IDs (physics presets, motion presets, expressions) read [Renames.parameters] to find them again.
 *
 * `{"op": "convert_id", "renames": [{"kind": "parameter"|"param_group"|"mesh"|"warp"|"rotation"|"part", "from", "to"}]}`
 */
internal object RigIdConversion {
	const val OP = "convert_id"
	val KINDS = listOf("parameter", "param_group", "mesh", "warp", "rotation", "part")
	/** Cubism's ID alphabet, as parameter creation already requires. */
	val ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

	data class Rename(val kind: String, val from: String, val to: String) {
		/** Warps and rotations share one namespace. */
		val namespace: String get() = if (kind == "warp" || kind == "rotation") "deformer" else kind
	}

	fun renames(command: JsonObject): List<Rename> = command.getValue("renames").jsonArray.map { element ->
		val o = element.jsonObject
		Rename(o.getValue("kind").jsonPrimitive.content, o.getValue("from").jsonPrimitive.content, o.getValue("to").jsonPrimitive.content)
	}

	fun encode(renames: List<Rename>): JsonObject = buildJsonObject {
		put("op", OP)
		putJsonArray("renames") { renames.forEach { r -> add(buildJsonObject { put("kind", r.kind); put("from", r.from); put("to", r.to) }) } }
	}

	/** The IDs [model] holds in [namespace]. */
	fun ids(model: PuppetModel, namespace: String): Set<String> = when (namespace) {
		"parameter" -> model.parameters.mapTo(HashSet()) { it.id.raw }
		"param_group" -> HashSet<String>().also { out -> fun walk(nodes: List<ParameterNode>) { for (n in nodes) if (n is ParameterNode.Group) { out += n.id.raw; walk(n.children) } }; walk(model.parameterTree) }
		"mesh" -> model.drawables.mapTo(HashSet()) { it.id.raw }
		"deformer" -> model.deformers.mapTo(HashSet()) { it.id.raw }
		"part" -> model.parts.mapTo(HashSet()) { it.id.raw }
		else -> throw IllegalArgumentException("Unknown object kind: $namespace")
	}

	private fun exists(model: PuppetModel, rename: Rename): Boolean = when (rename.kind) {
		"warp" -> model.deformers.any { it.id.raw == rename.from && it is Deformer.Warp }
		"rotation" -> model.deformers.any { it.id.raw == rename.from && it is Deformer.Rotation }
		else -> rename.from in ids(model, rename.namespace)
	}

	/** Why [renames] cannot apply to [model], or null. */
	fun problem(model: PuppetModel, renames: List<Rename>): String? = refusal(model, renames)?.message

	/** Why [renames] cannot apply to [model], as the refusal a caller tells apart by its code; null when they can. */
	fun refusal(model: PuppetModel, renames: List<Rename>): IllegalArgumentException? {
		if (renames.isEmpty() || renames.size > 4096) return IllegalArgumentException("Use 1..4096 renames")
		for (r in renames) {
			if (r.kind !in KINDS) return IllegalArgumentException("Unknown object kind: ${r.kind}")
			if (!ID_PATTERN.matches(r.to)) return IllegalArgumentException("ID ${r.to} must be 1-64 ASCII letters, digits or underscores and start with a letter")
			if (!exists(model, r)) return RigRefusal(RigRefusal.Code.NOT_FOUND, "${r.kind} not found: ${r.from}", "${r.kind}:${r.from}")
			if (r.from == r.to) return IllegalArgumentException("${r.kind} ${r.from} already has that ID")
		}
		for ((namespace, group) in renames.groupBy { it.namespace }) {
			if (group.map { it.from }.distinct().size != group.size) return IllegalArgumentException("Each object is converted once per entry")
			group.groupBy { it.to }.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (id, pair) ->
				return RigRefusal(RigRefusal.Code.ID_CONFLICT, "Two objects cannot take the same ID $id", "${pair.first().kind}:${pair.first().from}")
			}
			val taken = ids(model, namespace) - group.mapTo(HashSet()) { it.from }
			group.firstOrNull { it.to in taken || it.to in group.map { r -> r.from } }?.let {
				return RigRefusal(RigRefusal.Code.ID_CONFLICT, "ID already exists: ${it.to}", "${it.kind}:${it.from}")
			}
		}
		return null
	}

	fun remap(renames: List<Rename>): ModelIdRemap {
		fun of(namespace: String) = renames.filter { it.namespace == namespace }.associate { it.from to it.to }
		return ModelIdRemap(
			parameters = of("parameter").entries.associate { ParameterId(it.key) to ParameterId(it.value) },
			groups = of("param_group").entries.associate { ParameterGroupId(it.key) to ParameterGroupId(it.value) },
			drawables = of("mesh").entries.associate { DrawableId(it.key) to DrawableId(it.value) },
			deformers = of("deformer").entries.associate { DeformerId(it.key) to DeformerId(it.value) },
			parts = of("part").entries.associate { PartId(it.key) to PartId(it.value) },
		)
	}

	fun remap(renames: Renames): ModelIdRemap = ModelIdRemap(
		parameters = renames.of("parameter").entries.associate { ParameterId(it.key) to ParameterId(it.value) },
		groups = renames.of("param_group").entries.associate { ParameterGroupId(it.key) to ParameterGroupId(it.value) },
		drawables = renames.of("mesh").entries.associate { DrawableId(it.key) to DrawableId(it.value) },
		deformers = renames.of("deformer").entries.associate { DeformerId(it.key) to DeformerId(it.value) },
		parts = renames.of("part").entries.associate { PartId(it.key) to PartId(it.value) },
	)

	/**
	 * [generated] - a rig as the generators make it, under the IDs they give - with the conversions in [journal]
	 * applied: under the IDs the authored rig holds, so a regeneration merge matches a converted object with its
	 * generated self rather than reading it as deleted and made anew.
	 */
	fun converted(generated: PuppetModel, journal: List<JsonObject>): PuppetModel {
		val renames = renames(journal)
		return if (renames.isEmpty) generated else remap(renames).model(generated)
	}

	fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
		require(command.keys == setOf("op", "renames")) { "Invalid ID conversion" }
		val renames = renames(command)
		problem(model, renames)?.let { throw IllegalArgumentException(it) }
		return remap(renames).model(model)
	}

	/** Where each ID the journal converted ended up, by namespace: original ID to the current one, through every conversion. */
	data class Renames(val byNamespace: Map<String, Map<String, String>>) {
		val parameters: Map<String, String> get() = byNamespace["parameter"].orEmpty()
		val isEmpty: Boolean get() = byNamespace.values.all { it.isEmpty() }
		fun of(namespace: String): Map<String, String> = byNamespace[namespace].orEmpty()
		/** [id] in [namespace] as it is called now. */
		fun current(namespace: String, id: String): String = byNamespace[namespace]?.get(id) ?: id

		/** These renames, then [later]. */
		fun then(later: Renames): Renames = Renames((byNamespace.keys + later.byNamespace.keys).associateWith { namespace ->
			val first = of(namespace); val second = later.of(namespace)
			val composed = LinkedHashMap<String, String>()
			for ((from, to) in first) composed[from] = second[to] ?: to
			for ((from, to) in second) if (from !in composed && from !in first.values) composed[from] = to
			composed.filter { it.key != it.value }
		})

		companion object {
			val None = Renames(emptyMap())
			fun of(renames: List<Rename>) = Renames(renames.groupBy { it.namespace }.mapValues { (_, group) -> group.associate { it.from to it.to } })
		}
	}

	fun isConversion(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	/** Every conversion in [journal], composed in order. */
	fun renames(journal: List<JsonObject>): Renames =
		journal.filter(::isConversion).fold(Renames.None) { acc, command -> acc.then(Renames.of(renames(command))) }

	/**
	 * [journal] with each entry that replays after the journal - a generated override or a deferred panel edit - naming
	 * the IDs the conversions recorded after it gave its objects. Without conversions, [journal] itself.
	 */
	fun forwarded(journal: List<JsonObject>): List<JsonObject> {
		if (journal.none(::isConversion)) return journal
		val out = ArrayList<JsonObject>(journal.size)
		var later = Renames.None
		for (command in journal.asReversed()) {
			if (isConversion(command)) {
				later = Renames.of(renames(command)).then(later)
				out += command
				continue
			}
			out += if (later.isEmpty) command else forward(command, later)
		}
		return out.asReversed()
	}

	private fun forward(command: JsonObject, renames: Renames): JsonObject = when (command["op"]?.jsonPrimitive?.contentOrNull) {
		GeneratedOverrides.OP -> {
			val target = command.getValue("target").jsonPrimitive.content
			val kind = target.substringBefore(':'); val id = target.substringAfter(':')
			val namespace = if (kind == "warp") "deformer" else "mesh"
			val key = command.getValue("key").jsonObject.mapKeys { renames.current("parameter", it.key) }
			JsonObject(command + ("target" to JsonPrimitive("$kind:${renames.current(namespace, id)}")) + ("key" to JsonObject(key)))
		}
		"structure" -> JsonObject(command + ("edits" to JsonArray(command.getValue("edits").jsonArray.map { forwardStructure(it.jsonObject, renames) })))
		else -> command
	}

	private fun forwardStructure(edit: JsonObject, renames: Renames): JsonObject {
		val kind = edit["kind"]?.jsonPrimitive?.contentOrNull ?: return edit
		if (kind != "parameter" && kind != "param_group") return edit
		fun panel(id: String, refKind: String?) = renames.current(if (refKind == "param_group") "param_group" else "parameter", id)
		val out = edit.toMutableMap()
		edit["id"]?.jsonPrimitive?.contentOrNull?.let { out["id"] = JsonPrimitive(panel(it, kind)) }
		edit["partner_id"]?.jsonPrimitive?.contentOrNull?.let { out["partner_id"] = JsonPrimitive(renames.current("parameter", it)) }
		edit["before_id"]?.jsonPrimitive?.contentOrNull?.let { out["before_id"] = JsonPrimitive(panel(it, edit["before_kind"]?.jsonPrimitive?.contentOrNull)) }
		edit["parent_id"]?.jsonPrimitive?.contentOrNull?.let { out["parent_id"] = JsonPrimitive(renames.current("param_group", it)) }
		return JsonObject(out)
	}

	/** [edit] with its parameters named as [renames] has them now: generated groups name standard parameters by their standard IDs. */
	fun forward(edit: RigPhysicsEdit, renames: Map<String, String>): RigPhysicsEdit =
		if (renames.isEmpty() || edit.parameters.none { it in renames }) edit
		else edit.copy(inputs = edit.inputs.map { it.copy(parameter = renames[it.parameter] ?: it.parameter) },
			outputs = edit.outputs.map { it.copy(parameter = renames[it.parameter] ?: it.parameter) })
}

/**
 * The IDs an export checks before writing: two objects of one kind under one ID (warps and rotations share one) cannot
 * be told apart by a runtime or by Cubism, so an export refuses them; an ID outside Cubism's alphabet only warns.
 */
object RigIdCheck {
	/**
	 * Every ID [model] exports, by kind, and what the runtime's advanced mode carries beside it ([advanced]): the
	 * skeleton's virtual bones among the deformers, the simulations and their colliders in kinds of their own.
	 */
	private fun all(model: PuppetModel, advanced: io.github.psd2live.format.model.AdvancedIR?): List<Pair<String, String>> = buildList {
		model.parameters.mapTo(this) { "parameter" to it.id.raw }; model.parts.mapTo(this) { "part" to it.id.raw }
		model.deformers.mapTo(this) { "deformer" to it.id.raw }; model.drawables.mapTo(this) { "mesh" to it.id.raw }
		fun walk(nodes: List<ParameterNode>) { for (n in nodes) if (n is ParameterNode.Group) { add("param_group" to n.id.raw); walk(n.children) } }
		walk(model.parameterTree)
		if (advanced != null) {
			advanced.virtualBones.mapTo(this) { "deformer" to it.id }
			advanced.simulations.mapTo(this) { "simulation" to it.id }
			advanced.colliders.mapTo(this) { "collider" to it.id }
		}
	}

	/** The model [preview] exports: interior effects lowered or dropped as the export resolves them ([RigIrCompiler]). */
	fun exported(preview: RigPreviewModel): PuppetModel = io.github.psd2live.core.interior.InteriorLifecycle.exportModel(
		preview.rig.puppet, preview.config.rigEdits, PhysicsCatalog.active(preview))

	/** `kind id` for each ID more than one object of its kind holds. */
	fun conflicts(model: PuppetModel, advanced: io.github.psd2live.format.model.AdvancedIR? = null): List<String> =
		all(model, advanced).groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { (kind, id) -> "$kind $id" }

	/** `kind id` for each ID outside Cubism's alphabet ([RigIdConversion.ID_PATTERN]). */
	fun invalid(model: PuppetModel, advanced: io.github.psd2live.format.model.AdvancedIR? = null): List<String> =
		all(model, advanced).filterNot { RigIdConversion.ID_PATTERN.matches(it.second) }.map { (kind, id) -> "$kind $id" }

	/** Fails an export of [model] that has [conflicts]; the warnings for [invalid] IDs otherwise. */
	fun checked(label: String, model: PuppetModel, advanced: io.github.psd2live.format.model.AdvancedIR? = null): List<String> {
		val conflicts = conflicts(model, advanced)
		require(conflicts.isEmpty()) { io.github.psd2live.i18n.tr("validation.idConflict", label, conflicts.joinToString()) }
		val invalid = invalid(model, advanced)
		return if (invalid.isEmpty()) emptyList() else listOf(io.github.psd2live.i18n.tr("validation.idInvalid", label, invalid.take(12).joinToString() +
			if (invalid.size > 12) " (+${invalid.size - 12})" else ""))
	}
}
