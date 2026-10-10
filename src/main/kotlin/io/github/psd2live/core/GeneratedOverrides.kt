package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ThreeWayMerge
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs
import io.github.psd2live.core.legacy.SupersededEntryNote

/**
 * What an override did not do as recorded. Orphaned: the generated keyform it edits is gone or has another
 * shape, so it changes nothing. Conflict: the generator and the override both moved some of the form's control
 * points or vertices, and the override's values were kept.
 */
enum class GeneratedOverrideIssueKind(val wire: String) { ORPHANED("orphaned"), CONFLICT("conflict") }

/** Why an override is orphaned; null for a conflict. */
enum class GeneratedOverrideOrphanReason(val wire: String) { MISSING_KEYFORM("missing_keyform"), SHAPE_MISMATCH("shape_mismatch"), SUPERSEDED("superseded") }

/** One override's issue: [points] of the form's [total] points in conflict, or every point of an orphaned one. */
data class GeneratedOverrideIssue(
	val kind: GeneratedOverrideIssueKind,
	val generator: String,
	/** `warp:<id>` or `mesh:<id>`, as the override records it. */
	val target: String,
	val key: Map<String, Float>,
	val points: Int,
	val total: Int,
	val reason: GeneratedOverrideOrphanReason? = null,
) {
	val id: String get() = target.substringAfter(':')

	/** A plain English line for logs and tests; reports and the UI use the fields, never this text. */
	fun describe(): String = when (kind) {
		GeneratedOverrideIssueKind.CONFLICT -> "$id: $points of $total ${if (target.startsWith("warp:")) "control points" else "vertices"} at $key changed in both the generator and the override; the override is kept"
		GeneratedOverrideIssueKind.ORPHANED -> if (reason == GeneratedOverrideOrphanReason.SHAPE_MISMATCH) "$id: override at $key no longer matches the ${if (target.startsWith("warp:")) "lattice" else "mesh"}"
			else "$id: override at $key has no generated keyform"
	}
}

/**
 * A user's edit of generator output, kept across regeneration by a three-way merge.
 *
 * Swings and baked simulations own the keyforms their parameters add: a swing those of its axes on its target
 * Warps, a simulation those of its mode parameters on the meshes it baked. They run after the journal, so
 * replaying an ordinary edit there would happen before the parameter even exists. An override instead records
 * the generated form when the user edited it (`base`) beside the user's form (`points`), and replays after
 * every generator: each control point (Warp) or vertex offset (mesh) the user left where the generator had it
 * follows the new generation, each one the generator no longer changed keeps the user's value, and one both
 * moved keeps the user's value and is reported as a conflict.
 *
 * Which generator owns a keyform comes from [DocumentGenerators]' graph.
 */
internal object GeneratedOverrides {
	const val OP = "generated_override"

	fun isOverride(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	/** The generator that owns the cell at [key] of [kind] [id]: one of its parameters is keyed away from its default. */
	private fun owner(graph: io.github.psd2live.format.compile.document.GeneratorGraph, model: PuppetModel, kind: String, id: String,
					  key: Map<String, Float>, skins: PrimitiveSkins = PrimitiveSkins.None): String? = key.entries.firstNotNullOfOrNull { (parameterId, value) ->
		val parameter = model.parameters.firstOrNull { it.id.raw == parameterId } ?: return@firstNotNullOfOrNull null
		if (abs(value - parameter.default) <= org.umamo.runtime.eval.EPS_KEY) return@firstNotNullOfOrNull null
		// A version 2 part's generated keyforms belong to the generators the base names in its side channel.
		(if (kind == "mesh") skins.owner(DrawableId(id), parameter.id) else null)
			?: DocumentGenerators.owner(graph, DocumentGenerators.keyform(kind, id, parameterId))?.id
	}

	/** The edited target's kind as the graph names it: `warp` or `mesh`; null for anything else. */
	private fun kindOf(command: JsonObject): String? = when (val raw = command["kind"]?.jsonPrimitive?.contentOrNull) {
		"warp" -> "warp"
		null -> null
		else -> runCatching { RigTargetKind.fromString(raw) }.getOrNull()?.takeIf { it == RigTargetKind.ART_MESH }?.let { "mesh" }
	}

	/**
	 * [commands] with each keyed geometry edit of a generated cell recorded as an override against what
	 * [model] (the generated rig) holds there now. Other commands pass through unchanged. [skins] is the base's side
	 * channel: the generators owning each version 2 part's keyforms ([PrimitiveSkins.owner]).
	 */
	fun capture(model: PuppetModel, overlay: RigEditOverlay, commands: JsonArray, skins: PrimitiveSkins = PrimitiveSkins.None): JsonArray {
		val graph by lazy { DocumentGenerators.graph(overlay, primitives = skins) }
		return JsonArray(commands.map { element ->
			val command = element.jsonObject
			if (command["op"]?.jsonPrimitive?.contentOrNull != "canvas_geometry" || command["preserve_children"]?.jsonPrimitive?.booleanOrNull == true)
				return@map command
			val kind = kindOf(command) ?: return@map command
			val id = command.getValue("id").jsonPrimitive.content
			val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
			if (key.isEmpty()) return@map command
			val generator = owner(graph, model, kind, id, key, skins) ?: return@map command
			val (base, points) = when (kind) {
				"warp" -> {
					val cell = warpCell(model, id, key) ?: return@map command
					cell.form.controlPoints to (runCatching { CanvasGeometryJournal.points(model, command) }.getOrNull() ?: return@map command)
				}
				else -> {
					val cell = meshCell(model, id, key) ?: return@map command
					// The mesh edit's points are where the vertices show; the offsets it keys are what the user made.
					val edited = runCatching { CanvasEdits.apply(model, command) }.getOrNull()?.let { meshCell(it, id, key) } ?: return@map command
					cell.form.positionDeltas to edited.form.positionDeltas
				}
			}
			buildJsonObject {
				put("op", OP); put("generator", generator); put("target", "$kind:$id")
				put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
				put("base", JsonArray(base.map(::JsonPrimitive)))
				put("points", JsonArray(points.map(::JsonPrimitive)))
			}
		})
	}

	/**
	 * Compiled [journal] entries recorded on the finished rig [shown], as the journal replays them on [authored] - the
	 * rig before the generators add their axes. A key leaves out each axis a generator adds to its target at that axis's
	 * default (the target has it in [shown] and not in [authored]), so it lands on the rest cell the generator builds
	 * from. A generated parameter the target has no axis on yet stays: the edit makes that axis, and
	 * [GeneratedParameterAdoption] gives the journal the parameter. The viewing pose already holds only what the target reads, away
	 * from its default ([RigGeometryTools.referencePose]). The points are unchanged: at those defaults [shown] shows the
	 * geometry [authored] does.
	 */
	fun journalOnly(shown: PuppetModel, authored: PuppetModel, journal: List<JsonObject>): List<JsonObject> {
		val defaults = shown.parameters.associate { it.id.raw to it.default }
		fun axes(model: PuppetModel, kind: String, id: String): Set<String> = when (kind) {
			"warp" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp)?.geometryGrid
			else -> model.drawables.firstOrNull { it.id.raw == id }?.geometryGrid
		}?.axes?.mapTo(HashSet()) { it.parameterId.raw }.orEmpty()
		fun atDefault(parameterId: String, value: JsonElement) =
			defaults[parameterId]?.let { abs(value.jsonPrimitive.float - it) <= org.umamo.runtime.eval.EPS_KEY } == true
		return journal.map { command ->
			val bezier = command["op"]?.jsonPrimitive?.contentOrNull == RigBezierJournal.OP
			if (!bezier && command["op"]?.jsonPrimitive?.contentOrNull != "canvas_geometry") return@map command
			val kind = if (bezier) "warp" else kindOf(command) ?: return@map command
			val id = command.getValue("id").jsonPrimitive.content
			val added by lazy { axes(shown, kind, id) - axes(authored, kind, id) }
			fun cleaned(edit: JsonObject): JsonObject {
				val key = edit["key"]?.jsonObject ?: return edit
				val keptKey = JsonObject(key.filter { (parameterId, value) -> !(atDefault(parameterId, value) && parameterId in added) })
				return if (keptKey.size == key.size) edit else JsonObject(edit + ("key" to keptKey))
			}
			val outer = cleaned(command)
			if (!bezier) return@map outer
			val geometry = command["geometry"]?.takeIf { it != JsonNull }?.jsonObject ?: return@map outer
			JsonObject(outer + ("geometry" to cleaned(geometry)))
		}
	}

	/**
	 * Compiled [journal] entries that write keyforms a generator owns ([DocumentGenerators]) made replayable: those cells
	 * exist only after the generators run, after the journal. A keyform `set` of one, or a Bezier edit of one, becomes an
	 * override of the form it leaves, as a canvas edit does in [capture]; an entry that cannot be one - a copy to or from
	 * it, a key deleted or added on the generator's axis, an edit that keeps the children in place - is refused, naming
	 * the generator to change instead. [shown] is the generated rig the entries were made on.
	 */
	fun ownedWrites(shown: PuppetModel, overlay: RigEditOverlay, journal: List<JsonObject>, skins: PrimitiveSkins = PrimitiveSkins.None): List<JsonObject> {
		val graph by lazy { DocumentGenerators.graph(overlay, primitives = skins) }
		fun targetKind(target: String): String? = runCatching { RigAuthoringJournal.target(target).kind }.getOrNull()?.let {
			when (it) { RigTargetKind.WARP_DEFORMER -> "warp"; RigTargetKind.ART_MESH -> "mesh"; else -> null }
		}
		fun axisOwner(kind: String, id: String, parameter: String): String? =
			(if (kind == "mesh") skins.owner(DrawableId(id), ParameterId(parameter)) else null)
				?: DocumentGenerators.owner(graph, DocumentGenerators.keyform(kind, id, parameter))?.id
		fun key(command: JsonObject, field: String) = (command[field] as? JsonObject)?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
		fun override(generator: String, kind: String, id: String, key: Map<String, Float>, base: FloatArray, points: FloatArray) = buildJsonObject {
			put("op", OP); put("generator", generator); put("target", "$kind:$id")
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
			put("base", JsonArray(base.map(::JsonPrimitive)))
			put("points", JsonArray(points.map(::JsonPrimitive)))
		}
		fun cell(model: PuppetModel, kind: String, id: String, key: Map<String, Float>) =
			if (kind == "warp") warpCell(model, id, key)?.form?.controlPoints else meshCell(model, id, key)?.form?.positionDeltas
		fun keepingChildren(generator: String) = "Keeping the children in place is not available on keyforms $generator generates; edit them without it"
		return journal.flatMap { command ->
			when (command["op"]?.jsonPrimitive?.contentOrNull) {
				// A plain canvas edit of a generated cell is already an override (capture); one keeping the children is not.
				"canvas_geometry" -> {
					if (command["preserve_children"]?.jsonPrimitive?.booleanOrNull != true) return@flatMap listOf(command)
					val kind = kindOf(command) ?: return@flatMap listOf(command)
					val generator = owner(graph, shown, kind, command.getValue("id").jsonPrimitive.content, key(command, "key"), skins)
					require(generator == null) { keepingChildren(generator!!) }
					listOf(command)
				}
				RigBezierJournal.OP -> {
					val id = command.getValue("id").jsonPrimitive.content
					val key = key(command, "key")
					val generator = owner(graph, shown, "warp", id, key, skins) ?: return@flatMap listOf(command)
					val inner = command["geometry"] as? JsonObject
					require(inner?.get("preserve_children")?.jsonPrimitive?.booleanOrNull != true) { keepingChildren(generator) }
					val base = requireNotNull(warpCell(shown, id, key)) { "$id has no keyform at $key" }.form.controlPoints
					val edited = requireNotNull(warpCell(RigBezierJournal.replay(shown, command), id, key)).form.controlPoints
					listOf(override(generator, "warp", id, key, base, edited))
				}
				"set" -> {
					val target = command.getValue("target").jsonPrimitive.content
					val kind = targetKind(target) ?: return@flatMap listOf(command)
					val id = target.substringAfter(':')
					val key = key(command, "key")
					val geometry = command["geometry"] as? JsonObject ?: return@flatMap listOf(command)
					val generator = owner(graph, shown, kind, id, key, skins) ?: return@flatMap listOf(command)
					val values = geometry[if (kind == "warp") "controlPoints" else "positionDeltas"]?.jsonArray?.map { it.jsonPrimitive.float }
						?: return@flatMap listOf(command)
					val base = requireNotNull(cell(shown, kind, id, key)) { "$id has no keyform at $key" }
					listOf(override(generator, kind, id, key, base, values.toFloatArray())) +
						(if ("channels" in command) listOf(JsonObject(command - "geometry")) else emptyList())
				}
				"copy" -> {
					val source = command.getValue("target").jsonPrimitive.content
					val destination = command["destination"]?.jsonPrimitive?.contentOrNull ?: source
					if (command["channels"] == null) for ((target, field) in listOf(source to "from", destination to "key")) {
						val kind = targetKind(target) ?: continue
						val generator = owner(graph, shown, kind, target.substringAfter(':'), key(command, field), skins)
						require(generator == null) { "Keyforms $generator generates cannot be copied to or from; edit them on the canvas" }
					}
					listOf(command)
				}
				"delete", "parameter_keys" -> {
					val target = command["target"]?.jsonPrimitive?.contentOrNull ?: return@flatMap listOf(command)
					val kind = targetKind(target) ?: return@flatMap listOf(command)
					val parameter = command["parameter"]?.jsonPrimitive?.contentOrNull ?: return@flatMap listOf(command)
					val geometry = (command["channel"]?.jsonPrimitive?.contentOrNull?.equals("geometry", ignoreCase = true) ?: true) &&
						(command["track"]?.jsonPrimitive?.contentOrNull?.let { it == "geometry" } ?: true)
					val generator = if (geometry) axisOwner(kind, target.substringAfter(':'), parameter) else null
					require(generator == null) { "$generator makes the keys of $parameter on ${target.substringAfter(':')}; change it there instead" }
					listOf(command)
				}
				else -> listOf(command)
			}
		}
	}

	/** The coordinate of the cell at exactly [key] in [grid] (every axis keyed, at one of its keys), or null. */
	private fun coordinate(grid: KeyformGrid<*>, key: Map<String, Float>): IntArray? {
		// The key names exactly the grid's axes: a generator that no longer adds its axis leaves no generated cell,
		// and the override must not fall through to the rest form under it.
		if (grid.axes.map { it.parameterId.raw }.toSet() != key.keys) return null
		return IntArray(grid.axes.size) { axis ->
			val value = key.getValue(grid.axes[axis].parameterId.raw)
			grid.axes[axis].keys.indexOfFirst { abs(it - value) < org.umamo.runtime.eval.EPS_KEY }.takeIf { it >= 0 } ?: return null
		}
	}

	private fun warp(model: PuppetModel, id: String) = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp

	private fun warpCell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<WarpLatticeForm>? {
		val grid = warp(model, id)?.geometryGrid ?: return null
		val coordinate = coordinate(grid, key) ?: return null
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	private fun meshCell(model: PuppetModel, id: String, key: Map<String, Float>): KeyformCell<MeshDeltaForm>? {
		val grid = model.drawables.firstOrNull { it.id.raw == id }?.geometryGrid ?: return null
		val coordinate = coordinate(grid, key) ?: return null
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	/**
	 * A replay's model with what its overrides could not apply as recorded. [notes]: journal entries before a
	 * version 2 split that failed only on the drawables that split supersedes and replayed as no-ops (from the
	 * entries this replay ran; a checkpoint hit skips the ones before it).
	 */
	class Outcome(val model: PuppetModel, val issues: List<GeneratedOverrideIssue>, val notes: List<SupersededEntryNote> = emptyList()) {
		val conflicts: List<String> get() = issues.map(GeneratedOverrideIssue::describe)
	}

	/**
	 * Applies [command] to a generated [model]. A model without the generated cell (before the generators ran,
	 * or after the generator was removed or reshaped) is left unchanged; the override then reports as orphaned.
	 */
	fun apply(model: PuppetModel, command: JsonObject): Outcome {
		val target = command.getValue("target").jsonPrimitive.content
		val kind = target.substringBefore(':')
		val id = target.substringAfter(':')
		val generator = command["generator"]?.jsonPrimitive?.contentOrNull.orEmpty()
		val key = command.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
		val base = command.getValue("base").jsonArray.map { it.jsonPrimitive.float }
		val points = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }
		require(kind == "warp" || kind == "mesh") { "Unknown override target: $target" }
		require(base.size == points.size && points.size % 2 == 0 && points.all(Float::isFinite) && base.all(Float::isFinite)) { "Invalid override form" }
		fun orphaned(reason: GeneratedOverrideOrphanReason) = Outcome(model, listOf(GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED, generator, target, key, points.size / 2, points.size / 2, reason)))
		val current = (if (kind == "warp") warpCell(model, id, key)?.form?.controlPoints else meshCell(model, id, key)?.form?.positionDeltas)
			?: return orphaned(GeneratedOverrideOrphanReason.MISSING_KEYFORM)
		if (current.size != points.size) return orphaned(GeneratedOverrideOrphanReason.SHAPE_MISMATCH)
		fun pairs(values: List<Float>) = (0 until values.size / 2).associateWith { values[it * 2] to values[it * 2 + 1] }
		val merged = ThreeWayMerge.merge(pairs(base), pairs(current.toList()), pairs(points)) { a, b ->
			a != null && b != null && abs(a.first - b.first) <= EPS && abs(a.second - b.second) <= EPS
		}
		val result = FloatArray(current.size)
		for ((index, point) in merged.merged) { result[index * 2] = point.first; result[index * 2 + 1] = point.second }
		val issues = if (merged.conflicts.isEmpty()) emptyList()
			else listOf(GeneratedOverrideIssue(GeneratedOverrideIssueKind.CONFLICT, generator, target, key, merged.conflicts.size, points.size / 2))
		val next = if (kind == "warp") {
			val warp = warp(model, id)!!
			val grid = warp.geometryGrid!!
			val cell = warpCell(model, id, key)!!
			model.withReplacedGeometryGrid(KeyformOwner.Deformer(warp.id),
				KeyformGrid(grid.axes, grid.cells.map { if (it === cell) KeyformCell(it.coordinate, WarpLatticeForm(result)) else it }))
		} else {
			val drawable = model.drawables.first { it.id.raw == id }
			val grid = drawable.geometryGrid!!
			val cell = meshCell(model, id, key)!!
			model.withReplacedGeometryGrid(KeyformOwner.Drawable(drawable.id),
				KeyformGrid(grid.axes, grid.cells.map { if (it === cell) KeyformCell(it.coordinate, MeshDeltaForm(result)) else it }))
		}
		return Outcome(next, issues)
	}

	/**
	 * Every override in [journal], applied in order to the generated [model]. An orphaned override on a mesh a
	 * split superseded ([superseded]) reports that reason instead of a missing keyform.
	 */
	fun applyAll(model: PuppetModel, journal: List<JsonObject>, superseded: Set<String> = emptySet()): Outcome {
		var current = model
		val issues = ArrayList<GeneratedOverrideIssue>()
		for (command in journal) if (isOverride(command)) {
			val outcome = apply(current, command)
			current = outcome.model
			issues += outcome.issues.map { issue ->
				if (issue.kind == GeneratedOverrideIssueKind.ORPHANED && issue.reason == GeneratedOverrideOrphanReason.MISSING_KEYFORM && issue.target.startsWith("mesh:") && issue.id in superseded)
					issue.copy(reason = GeneratedOverrideOrphanReason.SUPERSEDED) else issue
			}
		}
		return Outcome(current, issues)
	}

	private const val EPS = 1e-4f
}
