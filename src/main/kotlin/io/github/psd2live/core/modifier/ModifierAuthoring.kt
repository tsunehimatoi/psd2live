package io.github.psd2live.core.modifier

import io.github.psd2live.core.DocumentGenerators
import io.github.psd2live.core.GeneratedOverrides
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.interior.InteriorLifecycle
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * The modifier document commands, shared by the editor and MCP: each is a pure function of the overlay and the
 * candidate it is applied to, and adds no history of its own.
 *
 * - `modifier_put` creates a modifier or changes one: its settings (whole values, or single keys - the diamond next
 *   to a field), targets, name, switch, vertex group and sampling.
 * - `modifier_delete` removes one; parameters it made that other content uses stay as ordinary parameters.
 * - `modifier_move` moves one in the stack order.
 * - `modifier_apply` writes what one does into the journal as ordinary keyforms (`keyforms`), or its shape at one
 *   pose into the rest shape (`rest`), and removes it - or, with `mesh`, does so for that mesh alone and leaves it out.
 * - `modifier_split` gives one mesh a copy of its own, `modifier_copy` puts copies on other meshes: independent
 *   modifiers with the same settings, placed right after the original.
 */
internal object ModifierAuthoring {
	const val PUT = "modifier_put"
	const val DELETE = "modifier_delete"
	const val MOVE = "modifier_move"
	const val APPLY = "modifier_apply"
	const val SPLIT = "modifier_split"
	const val COPY = "modifier_copy"
	val supported = setOf(PUT, DELETE, MOVE, APPLY, SPLIT, COPY)

	fun apply(operation: String, preview: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject): RigEditOverlay = when (operation) {
		PUT -> put(preview, overlay, request)
		DELETE -> delete(preview, overlay, request.text("id"))
		MOVE -> move(overlay, request)
		APPLY -> apply(preview, overlay, request)
		SPLIT -> split(preview, overlay, request)
		COPY -> copy(preview, overlay, request)
		else -> error("Unknown modifier operation: $operation")
	}

	private fun JsonObject.text(key: String) = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "$key is required" }

	private fun mesh(id: String) = id.removePrefix("mesh:")

	/** A fresh ID for a new modifier of [kind]: the kind's name, then `_2`, `_3`… past those in use. */
	fun freshId(overlay: RigEditOverlay, kind: String): String {
		val stem = "Modifier_" + kind.replaceFirstChar { it.uppercase() }
		val used = overlay.modifiers.mapTo(HashSet()) { it.id }
		return generateSequence(1) { it + 1 }.map { if (it == 1) stem else "${stem}_$it" }.first { it !in used }
	}

	fun put(preview: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject): RigEditOverlay {
		val authored = preview.authoredPuppet(overlay)
		val shown = preview.rig.puppet
		val id = request["id"]?.jsonPrimitive?.contentOrNull
		val old = id?.let { i -> overlay.modifiers.firstOrNull { it.id == i } }
		val kind = old?.kind ?: requireNotNull(request["kind"]?.jsonPrimitive?.contentOrNull) { "kind is required for a new modifier" }
		val definition = requireNotNull(ModifierKinds.of(kind)) { "Unknown modifier kind: $kind (${ModifierKinds.all.joinToString { it.id }})" }
		require(old == null || request["kind"]?.jsonPrimitive?.contentOrNull.let { it == null || it == kind }) { "A modifier's kind does not change" }
		val target = when {
			request["part"] != null -> ModifierTarget(part = request.text("part").removePrefix("part:"),
				excluded = request["excluded"]?.jsonArray?.map { mesh(it.jsonPrimitive.content) }?.toSet().orEmpty())
			request["targets"] != null -> request.getValue("targets").jsonArray.map { it.jsonPrimitive.content }.let { all ->
				// `warp:<id>` names a warp deformer; anything else a mesh.
				ModifierTarget(all.filterNot { it.startsWith(ModifierTarget.WARP) }.map(::mesh), warps = all.filter { it.startsWith(ModifierTarget.WARP) }.map { it.removePrefix(ModifierTarget.WARP) })
			}
			else -> old?.target ?: error("targets or part is required for a new modifier")
		}
		target.part?.let { part -> requireNotNull(authored.parts.firstOrNull { it.id.raw == part }) { "Not a part: $part" } }
		for (mesh in target.meshes) requireNotNull(authored.drawables.firstOrNull { it.id.raw == mesh }?.mesh) { "Not a mesh: $mesh" }
		for (warp in target.warps) require(authored.deformers.firstOrNull { it.id.raw == warp } is org.umamo.runtime.model.Deformer.Warp) { "Not a warp: $warp" }
		val targets = target.resolve(authored)
		val created = (old?.created.orEmpty() + request["parameters"]?.jsonArray.orEmpty().map { e ->
			val o = e.jsonObject
			val p = Parameter(ParameterId(o.text("id")), o["name"]?.jsonPrimitive?.contentOrNull ?: o.text("id"),
				o["min"]?.jsonPrimitive?.float ?: -10f, o["max"]?.jsonPrimitive?.float ?: 10f, o["default"]?.jsonPrimitive?.float ?: 0f)
			require(p.min < p.max && p.default in p.min..p.max) { "Parameter ${p.id.raw} needs min < max and its default within" }
			require(shown.parameters.none { it.id == p.id } && old?.created.orEmpty().none { it.id == p.id }) { "Parameter ${p.id.raw} already exists" }
			p
		}).distinctBy { it.id }
		val newId = old?.id ?: id ?: freshId(overlay, kind)
		require(newId.none(Char::isWhitespace)) { "Invalid modifier ID" }
		// A new modifier starts from the kind's defaults for where its meshes rest.
		var settings = old?.settings ?: definition.defaults(bounds(authored, targets)).mapValues { ModifierValue.Constant(it.value) }
		request["settings"]?.jsonObject?.forEach { (key, value) ->
			val setting = requireNotNull(definition.settings.firstOrNull { it.key == key }) { "$kind has no setting $key" }
			settings = if (value is JsonNull) settings - key else settings + (key to checked(setting, ModifierValue.fromJson(value)))
		}
		for (e in request["keys"]?.jsonArray.orEmpty()) {
			val o = e.jsonObject
			val key = o.text("setting")
			val setting = requireNotNull(definition.settings.firstOrNull { it.key == key }) { "$kind has no setting $key" }
			val at = o.getValue("at").jsonObject.mapValues { it.value.jsonPrimitive.float }
			val current = settings[key] ?: ModifierValue.Constant(setting.default.copyOf())
			settings = if (o["delete"]?.jsonPrimitive?.boolean == true) {
				val keyed = current as? ModifierValue.Keyed ?: continue
				// Without keys the setting keeps what it is at [at] (its first key along a parameter [at] leaves out).
				if (o["all"]?.jsonPrimitive?.boolean == true) settings + (key to ModifierValue.Constant(keyed.at { p -> at[p] ?: keyed.axes.first { it.parameter == p }.keys.first() }))
				else settings + (key to requireNotNull(keyed.withoutKey(at)))
			} else {
				val value = ModifierValue.fromJson(o.getValue("value")) as? ModifierValue.Constant ?: error("A key's value is a number or an array")
				val next = when (current) {
					is ModifierValue.Keyed -> current.withKey(at, value.value)
					is ModifierValue.Constant -> ModifierValue.keyed(emptyList(), listOf(current.value), at, value.value)
				}
				settings + (key to checked(setting, next))
			}
		}
		val parameters = (shown.parameters.map { it.id.raw } + authored.parameters.map { it.id.raw } + overlay.modifiers.flatMap { m -> m.created.map { it.id.raw } } +
			created.map { it.id.raw }).toSet()
		for ((key, value) in settings) for (p in value.parameters) require(p in parameters) { "$key is keyed on unknown parameter $p" }
		val weights = when (val w = request["weights"]) {
			null -> old?.weights
			is JsonNull -> null
			else -> w.jsonObject.let { ModifierWeights(it.text("group"), it["invert"]?.jsonPrimitive?.boolean ?: false) }
		}
		val sampling = request["sampling"]?.jsonObject?.let { s ->
			val base = old?.sampling ?: ModifierSampling()
			ModifierSampling(s["tolerance"]?.jsonPrimitive?.float ?: base.tolerance, s["max_keys"]?.jsonPrimitive?.int ?: base.maxKeys)
		} ?: old?.sampling ?: ModifierSampling()
		// Options: a string sets one, null clears it; a required one must end up set.
		var options = old?.options ?: run {
			// What the targets are keyed on: their own axes, and what the modifiers already on them drive.
			val keys = target.keys(authored).toSet()
			val keyed = keys.flatMap { key ->
				if (key.startsWith(ModifierTarget.WARP)) (authored.deformers.firstOrNull { it.id.raw == key.removePrefix(ModifierTarget.WARP) } as? org.umamo.runtime.model.Deformer.Warp)?.geometryGrid?.axes.orEmpty()
				else authored.drawables.firstOrNull { it.id.raw == key }?.geometryGrid?.axes.orEmpty()
			}.map { it.parameterId.raw } + overlay.modifiers.filter { m -> m.target.keys(authored).any { it in keys } }.flatMap { it.drivers(authored) }
			definition.defaultOptions(authored, target.keys(authored), (authored.parameters + overlay.modifiers.flatMap { it.created } + created).distinctBy { it.id }, keyed.toSet())
		}
		request["options"]?.jsonObject?.forEach { (key, value) ->
			requireNotNull(definition.options.firstOrNull { it.key == key }) { "$kind has no option $key" }
			options = if (value is JsonNull) options - key else options + (key to value.jsonPrimitive.content)
		}
		for (option in definition.options.filter { it.required }) require(option.key in options) { "$kind needs option ${option.key}" }
		for (option in definition.options) {
			val value = options[option.key] ?: continue
			when (option.type) {
				ModifierOption.Type.PARAMETER -> require(value in parameters) { "${option.key}: unknown parameter $value" }
				ModifierOption.Type.TARGET -> require(ModifierSampler.restCanvas(authored, value.let { if (it.startsWith(ModifierTarget.WARP)) it else mesh(it) }) != null) {
					"${option.key}: not a mesh or warp: $value"
				}
				ModifierOption.Type.CHOICE -> Unit
			}
		}
		options = options.mapValues { (key, value) ->
			if (definition.options.first { it.key == key }.type == ModifierOption.Type.TARGET && !value.startsWith(ModifierTarget.WARP)) mesh(value) else value
		}
		val modifier = RigModifier(newId, kind, request["name"]?.jsonPrimitive?.contentOrNull ?: old?.name ?: definition.id.replaceFirstChar { it.uppercase() },
			target, request["enabled"]?.jsonPrimitive?.boolean ?: old?.enabled ?: true, settings, created, weights, sampling, options,
			request["output"]?.jsonPrimitive?.content?.let { id -> requireNotNull(ModifierOutput.entries.firstOrNull { it.id == id }) { "output is auto, merge or stage" } }
				?: old?.output ?: ModifierOutput.AUTO)
		val modifiers = if (old == null) overlay.modifiers + modifier else overlay.modifiers.map { if (it.id == modifier.id) modifier else it }
		val next = overlay.copy(modifiers = modifiers)
		requireOwnership(next, authored)
		return dropStaleOverrides(next, authored)
	}

	/** [value] within [setting]'s range. */
	private fun checked(setting: ModifierSetting, value: ModifierValue): ModifierValue {
		require(value.size == setting.size) { "${setting.key} takes ${setting.size} value(s)" }
		val all = when (value) { is ModifierValue.Constant -> listOf(value.value); is ModifierValue.Keyed -> value.values }
		require(all.all { v -> v.all { it.isFinite() && it in setting.min..setting.max } }) { "${setting.key} is within ${setting.min}..${setting.max}" }
		return value
	}

	/** The rest canvas bounds of [targets] (min x, min y, max x, max y). */
	private fun bounds(model: PuppetModel, targets: List<String>): FloatArray {
		val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
		for (target in targets) {
			val canvas = ModifierSampler.restCanvas(model, target) ?: continue
			for (i in 0 until canvas.size / 2) {
				out[0] = minOf(out[0], canvas[i * 2]); out[1] = minOf(out[1], canvas[i * 2 + 1])
				out[2] = maxOf(out[2], canvas[i * 2]); out[3] = maxOf(out[3], canvas[i * 2 + 1])
			}
		}
		return if (out[0] > out[2]) FloatArray(4) else out
	}

	/** Refuses modifiers that would write keyforms another generator owns (a simulation's, an interior effect's…). */
	private fun requireOwnership(overlay: RigEditOverlay, model: PuppetModel) {
		val others = DocumentGenerators.graph(overlay.copy(modifiers = emptyList())).order
		for (claim in ModifierSampler.claims(overlay.modifiers, model)) {
			val owner = others.firstOrNull { claim in it.writes } ?: continue
			error("${claim.removePrefix("keyform:").removePrefix("parameter:")} belongs to ${owner.id}; a modifier cannot key it")
		}
	}

	/**
	 * [overlay] without the overrides of modifier keyforms no modifier owns any more: the edits of a cell along a
	 * parameter that no stack on that mesh is keyed on now.
	 */
	private fun dropStaleOverrides(overlay: RigEditOverlay, model: PuppetModel): RigEditOverlay {
		val owned = ModifierSampler.claims(overlay.modifiers, model).toSet()
		val kept = overlay.authoringJournal.filter { entry ->
			if (!GeneratedOverrides.isOverride(entry) || entry["generator"]?.jsonPrimitive?.contentOrNull != ModifierSampler.NODE) return@filter true
			val target = entry["target"]?.jsonPrimitive?.contentOrNull?.removePrefix("mesh:") ?: return@filter true
			entry["key"]?.jsonObject.orEmpty().keys.any { DocumentGenerators.keyform("mesh", target, it) in owned }
		}
		return if (kept.size == overlay.authoringJournal.size) overlay else overlay.copy(authoringJournal = kept)
	}

	fun delete(preview: RigPreviewModel, overlay: RigEditOverlay, id: String): RigEditOverlay {
		val modifier = requireNotNull(overlay.modifiers.firstOrNull { it.id == id }) { "Modifier not found: $id" }
		val next = overlay.copy(modifiers = overlay.modifiers.filterNot { it.id == id })
		return retained(preview, dropStaleOverrides(next, preview.authoredPuppet(next)), modifier)
	}

	/**
	 * [next] (without [modifier]) with a `structure` record creating each parameter [modifier] made that the rig
	 * without it still uses - keyed elsewhere, linked, in physics, a motion or another modifier.
	 */
	private fun retained(preview: RigPreviewModel, next: RigEditOverlay, modifier: RigModifier): RigEditOverlay {
		if (modifier.created.isEmpty()) return next
		val authored = preview.authoredPuppet(next)
		val probe = next.finish(ModifierSampler.withCreated(authored, listOf(modifier))).model
		val used = InteriorLifecycle.referenced(probe, next.physicsEdits, next) + next.modifiers.flatMap { it.drivers }
		val kept = modifier.created.filter { it.id.raw in used && authored.parameters.none { p -> p.id == it.id } }
		return if (kept.isEmpty()) next else next.copy(authoringJournal = next.authoringJournal + creation(kept))
	}

	private fun creation(parameters: List<Parameter>) = buildJsonObject {
		put("op", "structure")
		putJsonArray("edits") {
			parameters.forEach { p ->
				addJsonObject {
					put("action", "create"); put("kind", "parameter"); put("id", p.id.raw); put("name", p.name)
					put("min", p.min); put("max", p.max); put("default", p.default)
					put("parameter_kind", p.kind.name); put("repeat", p.repeat)
				}
			}
		}
	}

	/**
	 * Moves stack entry `id` - a modifier, or a swing's, simulation's or interior effect's node (`swing:<id>`…) - before
	 * entry `before`, or `to` first or last. Moving a generator, or moving among them, writes the whole order
	 * ([ModifierStack.ordered]); moving modifiers among themselves in a stack that was never ordered only reorders them.
	 */
	fun move(overlay: RigEditOverlay, request: JsonObject): RigEditOverlay {
		val id = request.text("id")
		val ids = ModifierStack.entries(overlay).map { it.id }
		require(id in ids) { "Stack entry not found: $id" }
		val rest = ids - id
		val index = when {
			request["before"] != null -> rest.indexOf(request.text("before")).also { require(it >= 0) { "Stack entry not found: ${request.text("before")}" } }
			request["to"]?.jsonPrimitive?.contentOrNull == "first" -> 0
			request["to"]?.jsonPrimitive?.contentOrNull == "last" -> rest.size
			else -> error("Give before (a stack entry ID) or to (first or last)")
		}
		val order = rest.subList(0, index) + id + rest.subList(index, rest.size)
		val modifiers = overlay.modifiers.associateBy { it.id }
		// Never ordered, and the generators still first as they always ran: the modifiers' own order is the stack's.
		val generators = ids.filterNot { it in modifiers }
		if (overlay.stackOrder.isEmpty() && order.take(generators.size) == generators)
			return overlay.copy(modifiers = order.mapNotNull { modifiers[it] })
		return ModifierStack.ordered(overlay, order)
	}

	/**
	 * Writes what modifier `id` does into the journal and removes it, in one step; the rig looks as before.
	 *
	 * `mode=keyforms` (the default) keys each target along the modifier's parameters as ordinary keyforms, with the
	 * user's edits of its cells folded in; it must be the only modifier on each target, since the next one in a
	 * stack starts from the rest shape, not from these keyforms. `mode=rest` moves each target's rest shape - every
	 * keyform of it - by what the modifier does at `pose` (the default pose when left out), so later modifiers
	 * take the new shape; its own parameters are dropped unless something else uses them.
	 */
	fun apply(preview: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject): RigEditOverlay {
		val id = request.text("id")
		val modifier = requireNotNull(overlay.modifiers.firstOrNull { it.id == id }) { "Modifier not found: $id" }
		val mode = request["mode"]?.jsonPrimitive?.contentOrNull ?: "keyforms"
		require(mode == "keyforms" || mode == "rest") { "mode is keyforms or rest" }
		val authored = preview.authoredPuppet(overlay)
		val all = modifier.target.keys(authored)
		val only = request["mesh"]?.jsonPrimitive?.contentOrNull?.let { if (it.startsWith(ModifierTarget.WARP)) it else mesh(it) }
		require(only == null || only in all) { "$only is not one of ${modifier.id}'s meshes" }
		val meshes = only?.let(::listOf) ?: all
		// Applying to one mesh leaves the modifier on the others; applying to its last mesh removes it.
		val rest = if (only == null) null else modifier.target.without(only)?.let { modifier.copy(target = it) }
		val without = overlay.copy(modifiers = overlay.modifiers.mapNotNull { if (it.id != id) it else rest })
		if (!modifier.enabled) return if (rest == null) delete(preview, overlay, id) else without
		val stacks = ModifierSampler.stacks(overlay.modifiers, authored)
		// The rig the modifier acts on: the journal and every generator before the modifiers.
		val before = ModifierSampler.withCreated(DocumentGenerators.generate(authored, overlay.copy(modifiers = emptyList())), listOf(modifier))
		val commands = ArrayList<JsonObject>()
		val keyed = mode == "keyforms" && modifier.drivers(authored).isNotEmpty()
		if (keyed) {
			// The ones after it read the mesh as its keyforms pose it, so they see the applied keyforms as they saw it.
			for (mesh in meshes) require(stacks[mesh].orEmpty().firstOrNull()?.id == id) {
				"$mesh has modifiers before ${modifier.id}: apply those first, or apply to the rest shape"
			}
			val known = authored.parameters.mapTo(HashSet()) { it.id }
			modifier.created.filter { it.id !in known }.takeIf { it.isNotEmpty() }?.let { commands += creation(it) }
			for (mesh in meshes) {
				val (sampled, offsets) = ModifierSampler.offsets(before, mesh, listOf(modifier)) ?: continue
				if (mesh.startsWith(ModifierTarget.WARP)) { commands += warpKeys(overlay, authored, before, mesh, modifier, sampled, offsets); continue }
				val drawable = authored.drawables.first { it.id.raw == mesh }
				// The axes other generators add are not the journal's: the offsets are taken at their defaults.
				val keep = drawable.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }.toSet() + modifier.drivers
				val (axes, table) = ModifierSampler.reduced(sampled, offsets, keep, before.parameters.associate { it.id.raw to it.default })
				val grid = ModifierSampler.withOffsets(drawable.geometryGrid, drawable.mesh!!.positions.size, axes, table)
					?: error("$mesh keyforms cannot take the modifier's keys")
				commands += keys(mesh, grid, axes.map { it.parameter }.filter { it in modifier.drivers }.toSet())
				// An edit of one of the modifier's cells keeps what the user changed there, on the applied value.
				for (entry in overlay.authoringJournal) {
					if (!GeneratedOverrides.isOverride(entry) || entry["generator"]?.jsonPrimitive?.contentOrNull != ModifierSampler.NODE) continue
					if (entry["target"]?.jsonPrimitive?.contentOrNull != "mesh:$mesh") continue
					val key = entry.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
					val cell = grid.cells.firstOrNull { c -> key.all { (p, v) -> grid.axes.indexOfFirst { it.parameterId.raw == p }.let { a -> a >= 0 && kotlin.math.abs(grid.axes[a].keys[c.coordinate[a]] - v) <= ModifierValue.EPS } } }
						?: continue
					val base = entry.getValue("base").jsonArray.map { it.jsonPrimitive.float }
					val points = entry.getValue("points").jsonArray.map { it.jsonPrimitive.float }
					val deltas = cell.form.positionDeltas
					if (base.size != deltas.size || points.size != deltas.size) continue
					commands += set(mesh, grid, cell, FloatArray(deltas.size) { deltas[it] + points[it] - base[it] })
				}
			}
		} else {
			// Nothing keyed, or the shape at one pose: the rest shape moves, and every keyform with it.
			val pose = request["pose"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
			for (mesh in meshes) {
				if (mesh.startsWith(ModifierTarget.WARP)) { warpRest(authored, before, mesh, modifier, pose)?.let { commands += it }; continue }
				val (stack, mapping) = ModifierSampler.stack(before, mesh, listOf(modifier)) ?: continue
				val local = ModifierSampler.local(before, before.drawables.first { it.id.raw == mesh })
				// The rest shape moved by what the modifier does at the pose.
				val canvas = stack.restCanvas.let { rest -> stack.offsetAt(pose).let { d -> FloatArray(rest.size) { rest[it] + d[it] } } }
				val solved = mapping.worldToLocal(FloatArray(canvas.size) { if (it % 2 == 0) canvas[it] else -canvas[it] }, local, (0 until local.size / 2).toSet())
				val shown = ModifierSampler.local(authored, authored.drawables.first { it.id.raw == mesh })
				commands += buildJsonObject {
					put("op", "canvas_geometry"); put("kind", "mesh"); put("id", mesh); putJsonObject("key") {}
					put("preserve_image", false)
					putJsonArray("points") { for (i in shown.indices) add(shown[i] + solved[i] - local[i]) }
				}
			}
		}
		val journal = overlay.authoringJournal.filterNot { entry ->
			keyed && GeneratedOverrides.isOverride(entry) && entry["generator"]?.jsonPrimitive?.contentOrNull == ModifierSampler.NODE &&
				meshes.any { entry["target"]?.jsonPrimitive?.contentOrNull == if (it.startsWith(ModifierTarget.WARP)) it else "mesh:$it" }
		} + commands
		val next = without.copy(authoringJournal = journal)
		val removed = dropStaleOverrides(next, preview.authoredPuppet(next))
		return if (keyed || rest != null) removed else retained(preview, removed, modifier)
	}

	/** [modifier] with the settings of [source] under a fresh ID on [target]; it makes no parameters of its own. */
	private fun copyOf(overlay: RigEditOverlay, source: RigModifier, target: ModifierTarget, id: String?) =
		RigModifier(id ?: freshId(overlay, source.kind), source.kind, source.name, target, source.enabled, source.settings, emptyList(),
			source.weights, source.sampling, source.options, source.output).also { copy -> require(overlay.modifiers.none { it.id == copy.id }) { "Modifier ${copy.id} already exists" } }

	/** [overlay] with [copy] right after [after]. */
	private fun inserted(overlay: RigEditOverlay, after: String, copy: RigModifier): RigEditOverlay {
		val index = overlay.modifiers.indexOfFirst { it.id == after }
		val next = overlay.copy(modifiers = overlay.modifiers.subList(0, index + 1) + copy + overlay.modifiers.subList(index + 1, overlay.modifiers.size))
		if (overlay.stackOrder.isEmpty()) return next
		val order = ModifierStack.entries(overlay).map { it.id }.toMutableList().also { it.add(it.indexOf(after) + 1, copy.id) }
		return ModifierStack.ordered(next, order)
	}

	/** Gives `mesh` its own copy of modifier `id`, which leaves it: from then on the two are adjusted apart. */
	fun split(preview: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject): RigEditOverlay {
		val modifier = requireNotNull(overlay.modifiers.firstOrNull { it.id == request.text("id") }) { "Modifier not found: ${request.text("id")}" }
		val authored = preview.authoredPuppet(overlay)
		val mesh = request.text("mesh").let { if (it.startsWith(ModifierTarget.WARP)) it else mesh(it) }
		require(mesh in modifier.target.keys(authored)) { "$mesh is not one of ${modifier.id}'s targets" }
		val rest = requireNotNull(modifier.target.without(mesh)) { "${modifier.id} acts on $mesh alone" }
		val own = if (mesh.startsWith(ModifierTarget.WARP)) ModifierTarget(warps = listOf(mesh.removePrefix(ModifierTarget.WARP))) else ModifierTarget(listOf(mesh))
		val copy = copyOf(overlay, modifier, own, request["new_id"]?.jsonPrimitive?.contentOrNull)
		val next = inserted(overlay.copy(modifiers = overlay.modifiers.map { if (it.id == modifier.id) modifier.copy(target = rest) else it }), modifier.id, copy)
		requireOwnership(next, authored)
		return next
	}

	/** Puts a copy of modifier `id` on `targets` (meshes), right after it; the copy is keyed on the same parameters. */
	fun copy(preview: RigPreviewModel, overlay: RigEditOverlay, request: JsonObject): RigEditOverlay {
		val modifier = requireNotNull(overlay.modifiers.firstOrNull { it.id == request.text("id") }) { "Modifier not found: ${request.text("id")}" }
		val authored = preview.authoredPuppet(overlay)
		val targets = request.getValue("targets").jsonArray.map { mesh(it.jsonPrimitive.content) }
		for (target in targets) requireNotNull(authored.drawables.firstOrNull { it.id.raw == target }?.mesh) { "Not a mesh: $target" }
		val next = inserted(overlay, modifier.id, copyOf(overlay, modifier, ModifierTarget(targets), request["new_id"]?.jsonPrimitive?.contentOrNull))
		requireOwnership(next, authored)
		return next
	}

	/**
	 * The journal entries keying warp [key] with what [modifier] adds there ([sampled] axes, [offsets]): the keys of its
	 * driving parameters, then every cell's control points, the user's edits of its cells folded in.
	 */
	private fun warpKeys(overlay: RigEditOverlay, authored: PuppetModel, before: PuppetModel, key: String, modifier: RigModifier,
						 sampled: List<ModifierValue.Keyed.Axis>, offsets: ModifierValue): List<JsonObject> {
		val id = key.removePrefix(ModifierTarget.WARP)
		val warp = authored.deformers.first { it.id.raw == id } as org.umamo.runtime.model.Deformer.Warp
		val keep = warp.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }.toSet() + modifier.drivers
		val (axes, table) = ModifierSampler.reduced(sampled, offsets, keep, before.parameters.associate { it.id.raw to it.default })
		val grid = requireNotNull(warp.geometryGrid?.let { ModifierSampler.withWarpOffsets(it, axes, table) }) { "$key keyforms cannot take the modifier's keys" }
		fun at(cell: KeyformCell<WarpLatticeForm>) = grid.axes.withIndex().associate { (a, axis) -> axis.parameterId.raw to axis.keys[cell.coordinate[a]] }
		return buildList {
			for (axis in grid.axes) if (axis.parameterId.raw in modifier.drivers) add(buildJsonObject {
				put("op", "parameter_keys"); put("target", key); put("parameter", axis.parameterId.raw)
				put("action", "add"); put("track", "geometry")
				putJsonArray("values") { axis.keys.forEach { add(it) } }
			})
			for (cell in grid.cells) {
				val pose = at(cell)
				val points = cell.form.controlPoints
				val edit = overlay.authoringJournal.firstOrNull { e ->
					GeneratedOverrides.isOverride(e) && e["generator"]?.jsonPrimitive?.contentOrNull == ModifierSampler.NODE && e["target"]?.jsonPrimitive?.contentOrNull == key &&
						e["key"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }?.let { k -> pose.all { (p, v) -> kotlin.math.abs((k[p] ?: v) - v) <= ModifierValue.EPS } } == true
				}
				val shown = edit?.let { e ->
					val base = e.getValue("base").jsonArray.map { it.jsonPrimitive.float }; val user = e.getValue("points").jsonArray.map { it.jsonPrimitive.float }
					if (base.size == points.size && user.size == points.size) FloatArray(points.size) { points[it] + user[it] - base[it] } else null
				} ?: points
				add(buildJsonObject {
					put("op", "set"); put("target", key)
					putJsonObject("key") { pose.forEach { (p, v) -> put(p, v) } }
					putJsonObject("geometry") { putJsonArray("controlPoints") { shown.forEach { add(it) } } }
				})
			}
		}
	}

	/** The canvas edit moving warp [key]'s every keyform by what [modifier] does there at [pose]. */
	private fun warpRest(authored: PuppetModel, before: PuppetModel, key: String, modifier: RigModifier, pose: Map<String, Float>): JsonObject? {
		val id = key.removePrefix(ModifierTarget.WARP)
		val (_, offsets) = ModifierSampler.offsets(before, key, listOf(modifier)) ?: return null
		val defaults = before.parameters.associate { it.id.raw to it.default }
		val offset = offsets.at { pose[it] ?: defaults[it] ?: 0f }
		val shown = io.github.psd2live.core.RigGeometryTools.geometry(authored, "warp", id, emptyMap()).points
		return buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "warp"); put("id", id); putJsonObject("key") {}
			putJsonArray("points") { for (i in shown.indices) add(shown[i] + offset[i]) }
		}
	}

	/** The journal entries giving [mesh] [grid]: the keys of the [added] axes, then every cell's shape. */
	private fun keys(mesh: String, grid: KeyformGrid<MeshDeltaForm>, added: Set<String>): List<JsonObject> = buildList {
		for (axis in grid.axes) if (axis.parameterId.raw in added) add(buildJsonObject {
			put("op", "parameter_keys"); put("target", "mesh:$mesh"); put("parameter", axis.parameterId.raw)
			put("action", "add"); put("track", "geometry")
			putJsonArray("values") { axis.keys.forEach { add(it) } }
		})
		for (cell in grid.cells) add(set(mesh, grid, cell, cell.form.positionDeltas))
	}

	private fun set(mesh: String, grid: KeyformGrid<MeshDeltaForm>, cell: KeyformCell<MeshDeltaForm>, deltas: FloatArray) = buildJsonObject {
		put("op", "set"); put("target", "mesh:$mesh")
		putJsonObject("key") { grid.axes.forEachIndexed { a, axis -> put(axis.parameterId.raw, axis.keys[cell.coordinate[a]]) } }
		putJsonObject("geometry") { putJsonArray("positionDeltas") { deltas.forEach { add(it) } } }
	}
}
