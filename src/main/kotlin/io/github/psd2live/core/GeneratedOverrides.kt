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

/**
 * One override's issue: [points] of the form's [total] points in conflict, or every point of an orphaned one. For an
 * override of a [channel] the points are the value's components (one for a scalar, three for a color).
 */
data class GeneratedOverrideIssue(
	val kind: GeneratedOverrideIssueKind,
	val generator: String,
	/** `warp:<id>` or `mesh:<id>`, as the override records it. */
	val target: String,
	val key: Map<String, Float>,
	val points: Int,
	val total: Int,
	val reason: GeneratedOverrideOrphanReason? = null,
	/** The overridden channel's record name (`opacity`, `multiply_color`, ...), or null for geometry. */
	val channel: String? = null,
) {
	val id: String get() = target.substringAfter(':')

	/** A plain English line for logs and tests; reports and the UI use the fields, never this text. */
	fun describe(): String = when (kind) {
		GeneratedOverrideIssueKind.CONFLICT -> if (channel != null) "$id: $points of $total $channel components at $key changed in both the generator and the override; the override is kept"
			else "$id: $points of $total ${if (target.startsWith("warp:")) "control points" else "vertices"} at $key changed in both the generator and the override; the override is kept"
		GeneratedOverrideIssueKind.ORPHANED -> if (reason == GeneratedOverrideOrphanReason.SHAPE_MISMATCH) "$id: override at $key no longer matches the ${if (target.startsWith("warp:")) "lattice" else "mesh"}"
			else "$id: override at $key has no generated ${channel?.let { "$it " }.orEmpty()}keyform"
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
 * The channel keyforms (opacity, draw order, multiply and screen color) a generator adds on its axes are kept the same
 * way: an override with a `channel` records the generated value (`base`) and the user's (`points`), one number for a
 * scalar and three for a color, and merges them component by component. A channel cell is the generator's when its
 * track gains the generator's axis after the journal; a track the journal itself keyed on that axis stays the user's,
 * edited by ordinary journal entries.
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
		val graph by lazy { DocumentGenerators.graph(overlay, primitives = skins, model = model) }
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
	fun ownedWrites(shown: PuppetModel, overlay: RigEditOverlay, journal: List<JsonObject>, skins: PrimitiveSkins = PrimitiveSkins.None,
					authored: PuppetModel? = null): List<JsonObject> {
		val graph by lazy { DocumentGenerators.graph(overlay, primitives = skins, model = shown) }
		/** The generator owning [channel]'s axis [parameter] on [kind] [id]: the track gains it after the journal ([authored] lacks it). */
		fun channelAxisOwner(kind: String, id: String, channel: FormChannel, parameter: String): String? {
			val before = authored ?: return null
			if (channelGrid(shown, kind, id, channel)?.axes?.any { it.parameterId.raw == parameter } != true) return null
			if (channelGrid(before, kind, id, channel)?.axes?.any { it.parameterId.raw == parameter } == true) return null
			return (if (kind == "mesh") skins.owner(DrawableId(id), ParameterId(parameter)) else null)
				?: DocumentGenerators.owner(graph, DocumentGenerators.keyform(kind, id, parameter))?.id
		}
		/** The generator owning [channel]'s cell of [kind] [id] at [key]: one of the key's parameters, away from its default, is such an axis. */
		fun channelOwner(kind: String, id: String, channel: FormChannel, key: Map<String, Float>): String? {
			if (channelCell(shown, kind, id, channel, key) == null) return null
			val defaults = shown.parameters.associate { it.id.raw to it.default }
			return key.entries.firstNotNullOfOrNull { (parameter, value) ->
				if (abs(value - (defaults[parameter] ?: return@firstNotNullOfOrNull null)) <= org.umamo.runtime.eval.EPS_KEY) null
				else channelAxisOwner(kind, id, channel, parameter)
			}
		}
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
					val overrides = ArrayList<JsonObject>()
					var rest = command
					(command["geometry"] as? JsonObject)?.let { geometry ->
						val generator = owner(graph, shown, kind, id, key, skins) ?: return@let
						val values = geometry[if (kind == "warp") "controlPoints" else "positionDeltas"]?.jsonArray?.map { it.jsonPrimitive.float }
							?: return@let
						val base = requireNotNull(cell(shown, kind, id, key)) { "$id has no keyform at $key" }
						overrides += override(generator, kind, id, key, base, values.toFloatArray())
						rest = JsonObject(rest - "geometry")
					}
					// Each channel of a generated cell becomes its own override; the user's other channels stay in the entry.
					(command["channels"] as? JsonObject)?.let { channels ->
						val owned = channels.keys.filter { field ->
							val channel = SET_FIELDS[field] ?: return@filter false
							val generator = channelOwner(kind, id, channel, key) ?: return@filter false
							val base = components(channelCell(shown, kind, id, channel, key)!!.form)
							overrides += channelOverride(generator, kind, id, key, channel, base, requested(channel, channels.getValue(field)))
							true
						}
						if (owned.isNotEmpty()) rest = if (owned.size == channels.size) JsonObject(rest - "channels")
							else JsonObject(rest + ("channels" to JsonObject(channels - owned.toSet())))
					}
					if (overrides.isEmpty()) listOf(command)
					else overrides + (if ("geometry" in rest || "channels" in rest) listOf(rest) else emptyList())
				}
				"copy" -> {
					val source = command.getValue("target").jsonPrimitive.content
					val destination = command["destination"]?.jsonPrimitive?.contentOrNull ?: source
					val listed = command["channels"]?.jsonArray?.map { it.jsonPrimitive.content }
					for ((target, field) in listOf(source to "from", destination to "key")) {
						val kind = targetKind(target) ?: continue
						val id = target.substringAfter(':')
						val geometry = listed == null || listed.any { it.equals("geometry", ignoreCase = true) }
						val generator = (if (geometry) owner(graph, shown, kind, id, key(command, field), skins) else null)
							?: (listed?.mapNotNull(::overridable) ?: OVERRIDABLE)
								.firstNotNullOfOrNull { channelOwner(kind, id, it, key(command, field)) }
						require(generator == null) { "Keyforms $generator generates cannot be copied to or from; edit them on the canvas" }
					}
					listOf(command)
				}
				"delete", "parameter_keys" -> {
					val target = command["target"]?.jsonPrimitive?.contentOrNull ?: return@flatMap listOf(command)
					val kind = targetKind(target) ?: return@flatMap listOf(command)
					val parameter = command["parameter"]?.jsonPrimitive?.contentOrNull ?: return@flatMap listOf(command)
					// A delete without a channel removes the key from geometry and every channel track; one naming a track only that.
					val named = command["channel"]?.jsonPrimitive?.contentOrNull ?: command["track"]?.jsonPrimitive?.contentOrNull
					val id = target.substringAfter(':')
					val geometry = named == null || named.equals("geometry", ignoreCase = true)
					val channels = if (named == null) OVERRIDABLE else listOfNotNull(overridable(named))
					val generator = (if (geometry) axisOwner(kind, id, parameter) else null)
						?: channels.firstNotNullOfOrNull { channelAxisOwner(kind, id, it, parameter) }
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

	/** The channels an override can carry. */
	private val OVERRIDABLE = listOf(FormChannel.OPACITY, FormChannel.DRAW_ORDER, FormChannel.MULTIPLY_COLOR, FormChannel.SCREEN_COLOR)

	/** The fields of a `set` entry's `channels` that write them; the set reads exactly these names. */
	private val SET_FIELDS = mapOf("opacity" to FormChannel.OPACITY, "drawOrder" to FormChannel.DRAW_ORDER,
		"multiplyColor" to FormChannel.MULTIPLY_COLOR, "screenColor" to FormChannel.SCREEN_COLOR)

	/**
	 * The overridable channel [name] names as the edits read it ([keyformChannelOf]: any case, camel or snake), or null
	 * for geometry, a channel an override cannot carry or an unknown name (the edit itself rejects that).
	 */
	private fun overridable(name: String): FormChannel? = runCatching { keyformChannelOf(name) }.getOrNull()?.takeIf { it in OVERRIDABLE }

	/** The record's name of [channel]: `opacity`, `draw_order`, `multiply_color` or `screen_color`. */
	fun wire(channel: FormChannel): String = channel.name.lowercase()

	private fun channelGrid(model: PuppetModel, kind: String, id: String, channel: FormChannel): KeyformGrid<ChannelValue>? = when (kind) {
		"warp" -> warp(model, id)?.channelGrids?.get(channel)
		else -> model.drawables.firstOrNull { it.id.raw == id }?.channelGrids?.get(channel)
	}

	private fun channelCell(model: PuppetModel, kind: String, id: String, channel: FormChannel, key: Map<String, Float>): KeyformCell<ChannelValue>? {
		val grid = channelGrid(model, kind, id, channel) ?: return null
		val coordinate = coordinate(grid, key) ?: return null
		return grid.cells.firstOrNull { it.coordinate.contentEquals(coordinate) }
	}

	/** A channel value as an override records it: one number for a scalar, red, green and blue for a color. */
	private fun components(value: ChannelValue): FloatArray = when (value) {
		is ChannelValue.Scalar -> floatArrayOf(value.value)
		is ChannelValue.Color -> floatArrayOf(value.color.red, value.color.green, value.color.blue)
		is ChannelValue.Flag -> error("Flags are not overridden")
	}

	/** What a `set` writes into [channel] from its payload [value], clamped as the set clamps it. */
	private fun requested(channel: FormChannel, value: JsonElement): FloatArray = when (channel.valueKind) {
		ChannelValueKind.COLOR -> value.jsonArray.let { rgb -> FloatArray(3) { rgb[it].jsonPrimitive.float.coerceIn(0f, 1f) } }
		else -> floatArrayOf(value.jsonPrimitive.float.coerceIn(0f, if (channel == FormChannel.DRAW_ORDER) 1000f else 1f))
	}

	private fun channelOverride(generator: String, kind: String, id: String, key: Map<String, Float>, channel: FormChannel,
								base: FloatArray, points: FloatArray) = buildJsonObject {
		put("op", OP); put("generator", generator); put("target", "$kind:$id"); put("channel", wire(channel))
		put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
		put("base", JsonArray(base.map(::JsonPrimitive)))
		put("points", JsonArray(points.map(::JsonPrimitive)))
	}

	/** Whether [command] is an override of a channel rather than of geometry. */
	fun isChannelOverride(command: JsonObject) = isOverride(command) && command["channel"] != null

	/** [apply] for an override of [channel]: the generated cell's value merged with the user's, component by component. */
	private fun applyChannel(model: PuppetModel, kind: String, id: String, generator: String, target: String, key: Map<String, Float>,
							 channel: FormChannel, base: List<Float>, points: List<Float>): Outcome {
		val size = if (channel.valueKind == ChannelValueKind.COLOR) 3 else 1
		require(base.size == size && points.size == size && points.all(Float::isFinite) && base.all(Float::isFinite)) { "Invalid override value" }
		val wire = wire(channel)
		val cell = channelCell(model, kind, id, channel, key) ?: return Outcome(model, listOf(GeneratedOverrideIssue(
			GeneratedOverrideIssueKind.ORPHANED, generator, target, key, size, size, GeneratedOverrideOrphanReason.MISSING_KEYFORM, wire)))
		fun indexed(values: List<Float>) = values.withIndex().associate { it.index to it.value }
		val merged = ThreeWayMerge.merge(indexed(base), indexed(components(cell.form).toList()), indexed(points)) { a, b ->
			a != null && b != null && abs(a - b) <= EPS
		}
		val result = FloatArray(size) { merged.merged.getValue(it) }
		val value = if (size == 3) ChannelValue.Color(ColorRgb(result[0], result[1], result[2])) else ChannelValue.Scalar(result[0])
		val issues = if (merged.conflicts.isEmpty()) emptyList()
			else listOf(GeneratedOverrideIssue(GeneratedOverrideIssueKind.CONFLICT, generator, target, key, merged.conflicts.size, size, channel = wire))
		val (owner, grids) = if (kind == "warp") warp(model, id)!!.let { KeyformOwner.Deformer(it.id) to it.channelGrids }
			else model.drawables.first { it.id.raw == id }.let { KeyformOwner.Drawable(it.id) to it.channelGrids }
		val grid = grids[channel]!!
		val next = KeyformGrid(grid.axes, grid.cells.map { if (it === cell) KeyformCell(it.coordinate, value) else it })
		return Outcome(model.withReplacedChannelGrids(owner, ChannelGrids(grids.gridsByChannel + (channel to next))), issues)
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
		command["channel"]?.jsonPrimitive?.content?.let { name ->
			val channel = requireNotNull(overridable(name)) { "Unknown override channel: $name" }
			return applyChannel(model, kind, id, generator, target, key, channel, base, points)
		}
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
