package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.PinPoint
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/**
 * Object-level edits Cubism keeps in its Edit menu: paste copies of objects ([RigObjectPaste]), duplicate one mesh's
 * topology onto others ([RigMeshDuplication]), find and replace object names or IDs, and convert IDs
 * ([RigIdConversion]). Each is a pure candidate on the document: journal entries, plus for an ID conversion the
 * document's own references outside the rig (motions, user physics, swing targets).
 */
internal object WorkspaceObjectEdits {
	const val PASTE = "object_paste"
	const val MESH_DUPLICATE = "mesh_duplicate"
	const val RENAME = "object_rename"
	const val CONVERT_ID = "object_convert_id"
	const val FIND = "object_find"
	val supported = setOf(PASTE, MESH_DUPLICATE, RENAME, CONVERT_ID)

	/** Object kinds find and replace covers, as targets name them. */
	val FIND_KINDS = listOf("mesh", "warp", "rotation", "part", "parameter", "param_group")

	fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
		val request = JsonObject(operation.request - setOf("state", "dry_run"))
		return when (operation.operation) {
			// Planned on the authored rig: what only the shown rig holds - a swing's, simulation's or interior effect's
			// objects, the copies over the bangs - is not there where the paste replays.
			PASTE -> WorkspaceDocumentEdits.journal(document, model, JsonArray(RigObjectPaste.compile(model.authored.rig.puppet, request, model.rig.puppet)))
			MESH_DUPLICATE -> WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(JsonObject(request + ("op" to JsonPrimitive(RigMeshDuplication.OP))))))
			RENAME -> rename(document, model, request)
			CONVERT_ID -> convert(document, model, request.getValue("renames").jsonArray.map { element ->
				val o = element.jsonObject
				val target = o.getValue("target").jsonPrimitive.content
				RigIdConversion.Rename(target.substringBefore(':', ""), target.substringAfter(':', ""), o.getValue("id").jsonPrimitive.content)
			})
			else -> throw IllegalArgumentException("Not an object edit: ${operation.operation}")
		}
	}

	/**
	 * What [operation] would record on [model]: the request that records exactly that - a paste with every new ID
	 * fixed, a rename with its matches named - and the result fields naming what it records. Committed on the same
	 * state, the pinned request records what the fields say or fails.
	 */
	fun planned(operation: String, model: RigPreviewModel, request: JsonObject): Pair<JsonObject, JsonObject> {
		// As the commit refuses it ([WorkspaceDocumentEdits]): a copy over the bangs is made anew on every build.
		WorkspaceDocumentEdits.refuseCopies(model, request)
		return plan(operation, model, request)
	}

	private fun plan(operation: String, model: RigPreviewModel, request: JsonObject): Pair<JsonObject, JsonObject> = when (operation) {
		PASTE -> {
			val plan = RigObjectPaste.plan(model.authored.rig.puppet, request, model.rig.puppet)
			val ids = plan.ids
			JsonObject(request + ("ids" to JsonObject(ids.mapValues { JsonPrimitive(it.value) }))) to buildJsonObject {
				put("ids", JsonObject(ids.mapValues { JsonPrimitive(it.value) }))
				// Deform paths copied with their meshes, by path:<id>; the same plan the commit compiles on this state.
				putJsonObject("paths") { for (copy in plan.copies) for ((from, to) in copy.paths) put("path:$from", to) }
			}
		}
		RENAME -> {
			val matches = find(model, JsonObject(request - setOf("offset", "limit")))
			require(matches.isNotEmpty()) { "Nothing matches" }
			JsonObject(request + ("targets" to JsonArray(matches.map { JsonPrimitive(it.target) }))) to buildJsonObject {
				put("field", request["field"]?.jsonPrimitive?.content ?: "name")
				putJsonArray("renamed") { for (m in matches) add(buildJsonObject { put("target", m.target); put("before", m.before); put("after", m.after) }) }
			}
		}
		CONVERT_ID -> request to buildJsonObject {
			putJsonObject("ids") { for (r in request.getValue("renames").jsonArray) put(r.jsonObject.getValue("target").jsonPrimitive.content, r.jsonObject.getValue("id")) }
		}
		// The plan the commit compiles on the same state: each target's vertex count and copied vertices outside its artwork.
		MESH_DUPLICATE -> request to buildJsonObject {
			putJsonArray("targets") { for (t in RigMeshDuplication.plan(model.rig.puppet, request)) add(buildJsonObject {
				put("target", "mesh:${t.id.raw}"); put("vertices", t.mesh.vertexCount); put("outside", t.outside)
			}) }
		}
		else -> request to JsonObject(emptyMap())
	}

	// --- Find and replace ---------------------------------------------------------------------------------------------

	/** One object a find matches: its `kind:id`, the [field] searched and what replace makes of it. */
	data class Match(val kind: String, val id: String, val name: String, val field: String, val before: String, val after: String) {
		val target: String get() = "$kind:$id"
	}

	/** Left and right in the spellings model IDs and names use, longest first so `Left` is not read as `L` + `eft`. */
	private val SIDES = listOf("Left" to "Right", "left" to "right", "LEFT" to "RIGHT", "左" to "右", "L" to "R", "l" to "r")

	/** [text] with each left spelled as the matching right and back, each only where it is a word of its own ([token]). */
	fun swapSides(text: String): String {
		val words = SIDES.flatMap { (l, r) -> listOf(l to r, r to l) }
		val out = StringBuilder()
		var i = 0
		while (i < text.length) {
			val hit = words.firstOrNull { (from, _) -> text.startsWith(from, i) && token(text, i, from.length) }
			if (hit == null) { out.append(text[i]); i++ } else { out.append(hit.second); i += hit.first.length }
		}
		return out.toString()
	}

	/**
	 * Whether the [length] letters at [at] are a word of their own: bounded by the ends, a non-letter or a change to an
	 * upper-case letter (`ArmL`, `Hair_L_01`, `EyeLOpen`, `LeftArm`, not `Lip` or `Cleft`). A CJK side is a word anywhere.
	 */
	private fun token(text: String, at: Int, length: Int): Boolean {
		val first = text[at]; val last = text[at + length - 1]
		if (first.code > 0x7F) return true
		val before = text.getOrNull(at - 1); val after = text.getOrNull(at + length)
		val starts = before == null || !before.isLetter() || (first.isUpperCase() && before.isLowerCase())
		val ends = after == null || !after.isLetter() || (after.isUpperCase() && (last.isLowerCase() || length == 1))
		return starts && ends
	}

	/**
	 * The objects of [model] the find [request] matches and what the replace makes of each: `field` (`name`, default,
	 * or `id`), `kinds` (default all of [FIND_KINDS]), `targets` to search only those `kind:id`s, `find` (text, or a
	 * regular expression with `regex`), `match_case` (default true), `replace`, `prefix`, `suffix` and `swap_sides`
	 * (left and right exchanged: L/R, Left/Right, 左/右). With nothing to replace, it lists what `find` matches.
	 */
	/** [find] among the objects of [model]'s authored rig: what the generators add after the journal is theirs to name. */
	fun find(model: RigPreviewModel, request: JsonObject): List<Match> = find(model.authored.rig.puppet, request)

	fun find(model: PuppetModel, request: JsonObject): List<Match> {
		val allowed = setOf("field", "kinds", "targets", "find", "regex", "match_case", "replace", "prefix", "suffix", "swap_sides", "standard_ids", "offset", "limit")
		require(request.keys.all { it in allowed }) { "Unknown find field: ${(request.keys - allowed).sorted().joinToString()}" }
		val field = request["field"]?.jsonPrimitive?.content ?: "name"
		require(field == "name" || field == "id") { "field must be name or id" }
		val kinds = request["kinds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: FIND_KINDS
		require(kinds.all { it in FIND_KINDS }) { "kinds must be among ${FIND_KINDS.joinToString()}" }
		val targets = request["targets"]?.jsonArray?.mapTo(HashSet()) { it.jsonPrimitive.content }
		val find = request["find"]?.jsonPrimitive?.content.orEmpty()
		val regex = request["regex"]?.jsonPrimitive?.boolean ?: false
		val matchCase = request["match_case"]?.jsonPrimitive?.boolean ?: true
		val replacement = request["replace"]?.jsonPrimitive?.contentOrNull
		val prefix = request["prefix"]?.jsonPrimitive?.content.orEmpty()
		val suffix = request["suffix"]?.jsonPrimitive?.content.orEmpty()
		val swap = request["swap_sides"]?.jsonPrimitive?.boolean ?: false
		val standard = request["standard_ids"]?.jsonPrimitive?.boolean ?: false
		require(!standard || field == "id") { "standard_ids converts IDs: use field id" }
		val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
		val pattern = if (find.isEmpty()) null else if (regex) runCatching { Regex(find, options) }.getOrElse {
			throw IllegalArgumentException("Invalid regular expression: ${it.message}")
		} else Regex(Regex.escape(find), options)
		val replacing = replacement != null || prefix.isNotEmpty() || suffix.isNotEmpty() || swap || standard
		val out = ArrayList<Match>()
		for ((kind, id, name) in objects(model)) {
			if (kind !in kinds || (targets != null && "$kind:$id" !in targets)) continue
			val text = if (field == "id") id else name
			if (pattern != null && !pattern.containsMatchIn(text)) continue
			var next = text
			if (standard) next = standardId(kind, next) ?: continue
			if (swap) next = swapSides(next)
			if (pattern != null && replacement != null) next = pattern.replace(next, if (regex) replacement else Regex.escapeReplacement(replacement))
			next = prefix + next + suffix
			if (replacing && next == text) continue
			out += Match(kind, id, name, field, text, next)
		}
		return out
	}

	/**
	 * [id] in Cubism 3's style when it is in Cubism 2's: `PARAM_EYE_L_OPEN` becomes `ParamEyeLOpen` and `PARTS_01_FACE`
	 * `PartFace`, the standard IDs Cubism 3 introduced; null for any other ID.
	 */
	fun standardId(kind: String, id: String): String? {
		fun camel(words: List<String>) = words.filter { it.isNotEmpty() }.joinToString("") { word -> word.lowercase().replaceFirstChar(Char::uppercaseChar) }
		return when (kind) {
			"parameter" -> Regex("PARAM_([A-Z0-9_]+)").matchEntire(id)?.let { "Param" + camel(it.groupValues[1].split('_')) }
			"part" -> Regex("PARTS_[0-9]+_([A-Z0-9_]+)").matchEntire(id)?.let { "Part" + camel(it.groupValues[1].split('_')) }
			else -> null
		}
	}

	/** Every object find and replace covers, as `(kind, id, name)`, in panel order. */
	fun objects(model: PuppetModel): List<Triple<String, String, String>> = buildList {
		for (d in model.drawables) add(Triple("mesh", d.id.raw, d.name))
		for (d in model.deformers) add(Triple(if (d is Deformer.Warp) "warp" else "rotation", d.id.raw, d.name))
		for (p in model.parts) add(Triple("part", p.id.raw, p.name))
		for (p in model.parameters) add(Triple("parameter", p.id.raw, p.name))
		fun walk(nodes: List<ParameterNode>) { for (n in nodes) if (n is ParameterNode.Group) { add(Triple("param_group", n.id.raw, n.name)); walk(n.children) } }
		walk(model.parameterTree)
	}

	/** The find [request] applied: names as one structure entry, IDs as one ID conversion. */
	fun rename(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
		val matches = find(model, JsonObject(request - setOf("offset", "limit")))
		require(matches.isNotEmpty()) { "Nothing matches" }
		require(matches.size <= 4096) { "Replace at most 4096 objects at once" }
		if (matches.first().field == "id") return convert(document, model, matches.map { RigIdConversion.Rename(it.kind, it.id, it.after) })
		matches.firstOrNull { it.after.isBlank() || it.after.any(Char::isISOControl) }?.let { throw IllegalArgumentException("${it.target} would have no name") }
		val edits = matches.map { m -> buildJsonObject {
			put("action", "rename"); put("kind", m.kind); put("id", m.id); put("name", m.after.trim())
		} }
		return WorkspaceDocumentEdits.journal(document, model, buildJsonArray { add(buildJsonObject { put("op", "structure"); put("edits", JsonArray(edits)) }) })
	}

	// --- ID conversion ----------------------------------------------------------------------------------------------

	/** Why [rename] cannot convert in [document]: what outside the rig holds the ID in a way only its owner can change. */
	private fun refusal(document: WorkspaceDocument, model: RigPreviewModel, rename: RigIdConversion.Rename): RigRefusal? {
		fun owned(message: String) = RigRefusal(RigRefusal.Code.OWNED_BY_GENERATOR, message, "${rename.kind}:${rename.from}")
		fun leftover(message: String) = RigRefusal(RigRefusal.Code.ID_CONFLICT, message, "${rename.kind}:${rename.from}")
		val shown = model.rig.puppet
		val authored = model.authored.rig.puppet
		val id = rename.from
		if (RigIdConversion.ids(shown, rename.namespace).contains(id) && !RigIdConversion.ids(authored, rename.namespace).contains(id))
			return owned("${rename.kind} $id belongs to a swing, simulation or interior effect; its ID follows that generator")
		val overlay = document.rigEdits
		// The skeleton bakes into the generated rig before the journal replays, under the IDs it names; renaming what
		// it binds would leave the bake and the conversion naming different objects.
		overlay.skeleton?.let { skeleton ->
			val bones = skeleton.bones
			when (rename.kind) {
				"mesh" -> if (bones.any { id in it.drawableIds } || id in skeleton.manualWeights)
					return owned("Mesh $id is bound to the skeleton; unbind it before converting its ID")
				"parameter" -> if (bones.any { it.parameterId == id } || skeleton.savedPoses.values.any { id in it })
					return owned("Parameter $id belongs to the skeleton; its ID follows the bone")
				"warp", "rotation" -> bones.firstOrNull { it.deformerId == id }?.let { return owned("Deformer $id belongs to the bone ${it.id}; its ID follows the bone") }
			}
		}
		when (rename.kind) {
			"mesh" -> {
				overlay.simEdits.firstOrNull { sim -> id in sim.targets || sim.colliders.any { it.mesh == id } }
					?.let { return owned("Mesh $id is simulated by ${it.id}; remove the simulation before converting its ID") }
				overlay.interiorEffects.firstOrNull { it.target == id }
					?.let { return owned("Mesh $id has the interior effect ${it.id}; remove it before converting its ID") }
			}
			"parameter" -> {
				// What a deleted parameter left under the new ID would be overwritten by the converted one's.
				overlay.motionClips.firstOrNull { clip -> clip.curves.any { it.parameterId == rename.to } }
					?.let { return leftover("Motion ${it.id} still has a curve on the deleted parameter ${rename.to}; remove it before converting $id to that ID") }
				overlay.expressions.orEmpty().firstOrNull { expression -> expression.entries.any { it.parameterId == rename.to } }
					?.let { return leftover("Expression ${it.id} still has an entry on the deleted parameter ${rename.to}; remove it before converting $id to that ID") }
				overlay.simEdits.firstOrNull { sim -> sim.inputs.any { it.parameter == id } || id in sim.inputRanges || id in sim.staticInputs.orEmpty() ||
					id in sim.forcedInputs || id in sim.outputs || id in sim.outputNames }
					?.let { return owned("Parameter $id drives the simulation ${it.id}; remove it from the simulation before converting its ID") }
				overlay.interiorEffects.firstOrNull { effect -> effect.sources.any { it.id.raw == id } || id in effect.parameterIds }
					?.let { return owned("Parameter $id drives the interior effect ${it.id}; remove it before converting its ID") }
				overlay.swingEdits.firstOrNull { id in it.parameterIds }?.let { return owned("Parameter $id belongs to the swing ${it.id}") }
			}
		}
		return null
	}

	/**
	 * [document] with [renames] converted: one `convert_id` entry for the rig, and the document's own references -
	 * motion curves, expressions, the pose, hit areas, the user's physics groups, swing targets - renamed with it in the
	 * same edit.
	 */
	fun convert(document: WorkspaceDocument, model: RigPreviewModel, renames: List<RigIdConversion.Rename>): WorkspaceDocument {
		RigIdConversion.refusal(model.rig.puppet, renames)?.let { throw it }
		RigIdConversion.refusal(model.authored.rig.puppet, renames)?.let { throw it }
		for (rename in renames) refusal(document, model, rename)?.let { throw it }
		val journaled = WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(RigIdConversion.encode(renames))))
		val parameters = renames.filter { it.kind == "parameter" }.associate { it.from to it.to }
		val deformers = renames.filter { it.namespace == "deformer" }.associate { it.from to it.to }
		val meshes = renames.filter { it.kind == "mesh" }.associate { it.from to it.to }
		var overlay = journaled.rigEdits
		// Motion curves and expression entries name parameters, the pose names parts, hit areas name meshes.
		for (rename in renames) if (rename.kind == "parameter" || rename.kind == "part")
			overlay = ModelSidecars.withIdRenamed(overlay, rename.kind, rename.from, rename.to)
		overlay = RigHitAreas.replacingMeshes(overlay, meshes.mapValues { listOf(it.value) })
		val orders = journaled.settings["drawOrderOverrides"]?.jsonObject
		val layerIds = model.analysis.layers.mapTo(HashSet()) { it.source.id.raw }
		val movedOrders = orders?.mapKeys { (id, _) -> if (id in layerIds) id else meshes[id] ?: id }
		val settings = if (orders == movedOrders) journaled.settings else
			JsonObject(journaled.settings + ("drawOrderOverrides" to JsonObject(requireNotNull(movedOrders))))
		fun pins(points: List<PinPoint>) = points.map { point -> point.mapKeys { parameters[it.key] ?: it.key } }
		val layout = if (parameters.isEmpty()) journaled.layout else journaled.layout.copy(
			referencePins = journaled.layout.referencePins.mapValues { (_, points) -> pins(points) },
			references = journaled.layout.references.map { it.copy(pins = pins(it.pins)) })
		val modifiers = overlay.modifierStack?.let { raw ->
			val replacements = renames.mapNotNull { rename ->
				val kind = rename.namespace.takeIf { it in setOf("mesh", "deformer", "part") } ?: return@mapNotNull null
				ModifierTarget(kind, rename.from) to listOf(ModifierTarget(kind, rename.to))
			}.toMap()
			RigModifierStack.fromJson(raw).retarget(replacements).toJson()
		}
		return journaled.copy(settings = settings, layout = layout, rigEdits = overlay.copy(
			modifierStack = modifiers,
			physicsEdits = overlay.physicsEdits.map { RigIdConversion.forward(it, parameters) },
			swingEdits = if (deformers.isEmpty()) overlay.swingEdits else overlay.swingEdits.map { swing ->
				if (swing.targets.none { it in deformers }) swing else swing.copy(targets = swing.targets.map { deformers[it] ?: it })
			},
		))
	}

	/**
	 * Refuses [spec] binding or weighting one of the meshes the bake cannot bind ([SkeletonAutoBuilder.unbaked]) that [previous] did not already: the bake would
	 * drop the binding without a word. One an older document already holds stays, so its other edits still apply.
	 */
	fun refuseUnbakedBindings(spec: SkeletonSpec, previous: SkeletonSpec?, overlay: RigEditOverlay, rig: BuiltRig) {
		fun bound(s: SkeletonSpec?) = s?.let { it.bones.flatMap { b -> b.drawableIds } + it.manualWeights.keys }.orEmpty().toSet()
		val added = bound(spec) - bound(previous)
		if (added.isEmpty()) return
		val unbaked = SkeletonAutoBuilder.unbaked(overlay, rig)
		added.firstOrNull { it in unbaked }?.let {
			throw IllegalArgumentException("Mesh $it was converted or made after the generated rig; the skeleton bakes before the journal and cannot bind it")
		}
	}

	// --- Results ----------------------------------------------------------------------------------------------------

	fun findResult(matches: List<Match>, offset: Int, limit: Int): JsonObject = buildJsonObject {
		put("total", matches.size)
		putJsonArray("matches") {
			for (m in matches.drop(offset).take(limit)) add(buildJsonObject {
				put("target", m.target); put("kind", m.kind); put("id", m.id); put("name", m.name)
				put("field", m.field); put("before", m.before); put("after", m.after)
			})
		}
	}
}
