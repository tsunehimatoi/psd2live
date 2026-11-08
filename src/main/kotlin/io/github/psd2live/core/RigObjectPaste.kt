package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.ModelIdRemap
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/**
 * Cubism's copy and paste of objects as a journal entry. A paste request names the copied objects; [compile] closes the
 * set over what the objects hold (a deformer its descendant deformers and meshes, a part its child parts and meshes and
 * the deformers organized in it), fixes every new ID in advance and records the copies as an `object_paste` entry,
 * followed by a `structure` entry that moves the pasted roots under the requested deformer or part.
 *
 * Replay copies the objects as the rig holds them where the entry replays: each copy sits right after its original,
 * shares its texture, keeps its keyforms and blend shapes on the same parameters, and references copied objects in place
 * of their originals (parents, parts, masks); a glue is copied when both its meshes are, a path and a vertex group with
 * their mesh. A copied parameter is a new axis with the original's range; keyforms stay on the original. Swings,
 * simulations and interior effects belong to the document, not to an object, and are not copied.
 *
 * `{"op": "object_paste", "objects": [{"kind": "mesh"|"warp"|"rotation"|"part"|"parameter", "id", "new_id", "paths"?: {old: new}}]}`
 */
internal object RigObjectPaste {
	const val OP = "object_paste"
	val KINDS = listOf("mesh", "warp", "rotation", "part", "parameter")

	data class Copy(val kind: String, val id: String, val newId: String, val paths: Map<String, String> = emptyMap())

	fun copies(command: JsonObject): List<Copy> = command.getValue("objects").jsonArray.map { element ->
		val o = element.jsonObject
		Copy(o.getValue("kind").jsonPrimitive.content, o.getValue("id").jsonPrimitive.content, o.getValue("new_id").jsonPrimitive.content,
			o["paths"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
	}

	fun encode(copies: List<Copy>): JsonObject = buildJsonObject {
		put("op", OP)
		putJsonArray("objects") { for (c in copies) add(buildJsonObject {
			put("kind", c.kind); put("id", c.id); put("new_id", c.newId)
			if (c.paths.isNotEmpty()) put("paths", JsonObject(c.paths.mapValues { JsonPrimitive(it.value) }))
		}) }
	}

	/** Whether [command] is a paste request still to be compiled ([compile]) rather than a recorded entry. */
	fun isRequest(command: JsonObject) = "sources" in command

	private fun namespace(kind: String) = if (kind == "warp" || kind == "rotation") "deformer" else kind

	/** What a paste request makes: the recorded copies, in model order, and the structure edits that place their roots. */
	class Plan(val copies: List<Copy>, val placement: List<JsonObject>, private val rootKeys: Set<String>) {
		/** Each copied `kind:id` and the ID of its copy. */
		val ids: Map<String, String> get() = copies.associate { "${it.kind}:${it.id}" to it.newId }
		/** The copies no other copy holds: what the request placed. */
		val roots: List<Copy> get() = copies.filter { "${it.kind}:${it.id}" in rootKeys }
	}

	/**
	 * The paste [request] makes on [model]: `sources` (1..128 `kind:id`), optional `parent` (`warp:ID`, `rotation:ID`
	 * or `root`) for the pasted meshes and deformers, `part` (`part:ID` or `root`) for the pasted roots' organizational
	 * place, `parameter_group` (`param_group:ID` or `root`) for pasted parameters and `ids` fixing new IDs by source
	 * `kind:id`. New IDs not given are the source ID with `_copy`, then `_copy_2`, ... - the first one free.
	 * IDs [reserved] holds count as taken too: a paste planned on the authored rig is recorded on the shown one, which
	 * also holds what the generators add.
	 */
	fun plan(model: PuppetModel, request: JsonObject, reserved: PuppetModel? = null): Plan {
		val allowed = setOf("op", "sources", "parent", "part", "parameter_group", "ids")
		require(request.keys.all { it in allowed }) { "Unknown paste field: ${(request.keys - allowed).sorted().joinToString()}" }
		val sources = request.getValue("sources").jsonArray.map { it.jsonPrimitive.content }
		require(sources.size in 1..128) { "Paste 1..128 objects" }
		val wanted = LinkedHashSet<Pair<String, String>>()
		for (source in sources) {
			val kind = source.substringBefore(':', ""); val id = source.substringAfter(':', "")
			require(kind in KINDS && id.isNotEmpty()) { "Use kind:id with kind ${KINDS.joinToString("|")}: $source" }
			RigRefusal.found(exists(model, kind, id), "$kind:$id") { "$kind not found: $id" }
			wanted += kind to id
		}
		// A deformer brings its descendants and the meshes they hold; a part its child parts, meshes and deformers.
		val deformers = model.deformers.associateBy { it.id.raw }
		val copiedParts = LinkedHashSet<String>()
		fun addPart(id: String) {
			if (!copiedParts.add(id)) return
			for (child in model.partById.getValue(PartId(id)).children) when (child) {
				is OrgChild.Part -> addPart(child.id.raw)
				is OrgChild.Drawable -> wanted += "mesh" to child.id.raw
			}
		}
		for ((kind, id) in wanted.toList()) if (kind == "part") addPart(id)
		for (deformer in model.deformers) if (deformer.partId?.raw in copiedParts) wanted += kindOf(deformer) to deformer.id.raw
		val copiedDeformers = HashSet<String>()
		fun addDeformer(id: String) {
			if (!copiedDeformers.add(id)) return
			for (child in model.deformers) if (child.parent?.raw == id) addDeformer(child.id.raw)
		}
		for ((kind, id) in wanted.toList()) if (kind == "warp" || kind == "rotation") addDeformer(id)
		val meshes = wanted.filter { it.first == "mesh" }.mapTo(HashSet()) { it.second } +
			model.drawables.filter { it.parentDeformerId?.raw in copiedDeformers }.map { it.id.raw }
		val parameters = wanted.filter { it.first == "parameter" }.mapTo(HashSet()) { it.second }
		val explicit = request["ids"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
		val taken = HashMap<String, MutableSet<String>>()
		fun taken(namespace: String) = taken.getOrPut(namespace) {
			(RigIdConversion.ids(model, namespace) + reserved?.let { RigIdConversion.ids(it, namespace) }.orEmpty()).toMutableSet()
		}
		val paths = (model.deformPaths + reserved?.deformPaths.orEmpty()).mapTo(HashSet()) { it.id }
		fun copy(kind: String, id: String): Copy {
			val namespace = namespace(kind)
			val newId = explicit["$kind:$id"]?.also { chosen ->
				require(RigIdConversion.ID_PATTERN.matches(chosen)) { "ID $chosen must be 1-64 ASCII letters, digits or underscores and start with a letter" }
				RigRefusal.free(chosen !in taken(namespace), "$kind:$id") { "ID already exists: $chosen" }
			} ?: StableIds.fresh("${id}_copy") { it in taken(namespace) }
			taken(namespace) += newId
			val pathIds = if (kind != "mesh") emptyMap() else model.deformPaths.filter { it.drawableId.raw == id }.associate { path ->
				path.id to StableIds.fresh("${path.id}_copy") { it in paths }.also { paths += it }
			}
			return Copy(kind, id, newId, pathIds)
		}
		val unknown = explicit.keys - (copiedParts.map { "part:$it" } + copiedDeformers.map { "${kindOf(deformers.getValue(it))}:$it" } +
			meshes.map { "mesh:$it" } + parameters.map { "parameter:$it" })
		require(unknown.isEmpty()) { "ids names objects the paste does not copy: ${unknown.sorted().joinToString()}" }
		// Model order, so a copy's parent or part is always copied before it is referenced.
		val copies = model.parts.filter { it.id.raw in copiedParts }.map { copy("part", it.id.raw) } +
			model.deformers.filter { it.id.raw in copiedDeformers }.map { copy(kindOf(it), it.id.raw) } +
			model.drawables.filter { it.id.raw in meshes }.map { copy("mesh", it.id.raw) } +
			model.parameters.filter { it.id.raw in parameters }.map { copy("parameter", it.id.raw) }
		require(copies.size <= 4096) { "A paste copies at most 4096 objects" }
		val partOwner = HashMap<String, String>()
		for (part in model.parts) for (child in part.children) when (child) {
			is OrgChild.Part -> partOwner["part:${child.id.raw}"] = part.id.raw
			is OrgChild.Drawable -> partOwner["mesh:${child.id.raw}"] = part.id.raw
		}
		val drawables = model.drawables.associateBy { it.id.raw }
		val rootKeys = copies.filter { c -> when (c.kind) {
			"part" -> partOwner["part:${c.id}"] !in copiedParts
			"mesh" -> drawables.getValue(c.id).parentDeformerId?.raw !in copiedDeformers && partOwner["mesh:${c.id}"] !in copiedParts
			"warp", "rotation" -> deformers.getValue(c.id).parent?.raw !in copiedDeformers && deformers.getValue(c.id).partId?.raw !in copiedParts
			else -> true
		} }.mapTo(HashSet()) { "${it.kind}:${it.id}" }
		return Plan(copies, placement(model, request, copies.filter { "${it.kind}:${it.id}" in rootKeys }), rootKeys)
	}

	private fun placement(model: PuppetModel, request: JsonObject, roots: List<Copy>): List<JsonObject> {
		fun reference(field: String, kinds: Set<String>): Pair<Boolean, String?> {
			val text = request[field]?.jsonPrimitive?.contentOrNull ?: return false to null
			if (text == "root") return true to null
			val kind = text.substringBefore(':', ""); val id = text.substringAfter(':', "")
			require(kind in kinds && exists(model, kind, id)) { "$field must be ${kinds.joinToString(" or ") { "$it:ID" }} or root: $text" }
			return true to id
		}
		val edits = ArrayList<JsonObject>()
		val (reparent, parent) = reference("parent", setOf("warp", "rotation"))
		val (regroup, part) = reference("part", setOf("part"))
		val (refile, group) = reference("parameter_group", setOf("param_group"))
		fun edit(action: String, kind: String, id: String, vararg fields: Pair<String, JsonElement>) =
			buildJsonObject { put("action", action); put("kind", kind); put("id", id); fields.forEach { (k, v) -> put(k, v) } }
		val parentValue = parent?.let(::JsonPrimitive) ?: JsonNull
		val partValue = part?.let(::JsonPrimitive) ?: JsonNull
		for (root in roots) when (root.kind) {
			"mesh" -> {
				if (reparent) edits += edit("bind", "mesh", root.newId, "parent_id" to parentValue, "space" to JsonPrimitive("canvas"))
				if (regroup) edits += edit("move", "mesh", root.newId, "parent_id" to partValue)
			}
			"warp", "rotation" -> {
				if (reparent) edits += edit("move", root.kind, root.newId, "parent_id" to parentValue, "space" to JsonPrimitive("local"))
				if (regroup) edits += edit("part", root.kind, root.newId, "part_id" to partValue)
			}
			"part" -> if (regroup) edits += edit("move", "part", root.newId, "parent_id" to partValue)
			"parameter" -> if (refile) edits += edit("move", "parameter", root.newId, "parent_id" to (group?.let(::JsonPrimitive) ?: JsonNull))
		}
		return edits
	}

	/** [request] as journal entries: the paste and, when it places its roots elsewhere, the structure edit that does. */
	fun compile(model: PuppetModel, request: JsonObject, reserved: PuppetModel? = null): List<JsonObject> {
		val plan = plan(model, request, reserved)
		val paste = encode(plan.copies)
		return if (plan.placement.isEmpty()) listOf(paste)
			else listOf(paste, buildJsonObject { put("op", "structure"); put("edits", JsonArray(plan.placement)) })
	}

	private fun kindOf(deformer: Deformer) = if (deformer is Deformer.Warp) "warp" else "rotation"

	private fun exists(model: PuppetModel, kind: String, id: String): Boolean = when (kind) {
		"warp" -> model.deformers.any { it.id.raw == id && it is Deformer.Warp }
		"rotation" -> model.deformers.any { it.id.raw == id && it is Deformer.Rotation }
		"param_group" -> id in RigIdConversion.ids(model, "param_group")
		else -> id in RigIdConversion.ids(model, namespace(kind))
	}

	fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
		require(command.keys == setOf("op", "objects")) { "Invalid object paste" }
		val copies = copies(command)
		require(copies.isNotEmpty()) { "Paste copies nothing" }
		for (c in copies) {
			require(c.kind in KINDS) { "Unknown object kind: ${c.kind}" }
			require(exists(model, c.kind, c.id)) { "Pasted ${c.kind} not found: ${c.id}" }
		}
		for ((namespace, group) in copies.groupBy { namespace(it.kind) }) {
			val taken = RigIdConversion.ids(model, namespace)
			require(group.map { it.newId }.distinct().size == group.size && group.none { it.newId in taken }) { "Paste ID already exists" }
			require(group.map { it.id }.distinct().size == group.size) { "An object is pasted once per entry" }
		}
		fun of(kind: String) = copies.filter { namespace(it.kind) == kind }.associate { it.id to it.newId }
		// References between copies point at the copies; parameters are new axes, so keyforms keep the originals'.
		val remap = ModelIdRemap(
			drawables = of("mesh").entries.associate { DrawableId(it.key) to DrawableId(it.value) },
			deformers = of("deformer").entries.associate { DeformerId(it.key) to DeformerId(it.value) },
			parts = of("part").entries.associate { PartId(it.key) to PartId(it.value) },
		)
		val meshes = remap.drawables; val deformerIds = remap.deformers; val partIds = remap.parts
		val parameterIds = of("parameter").entries.associate { ParameterId(it.key) to ParameterId(it.value) }

		fun <T> afterEach(list: List<T>, copy: (T) -> T?): List<T> = buildList { for (item in list) { add(item); copy(item)?.let(::add) } }
		val drawables = afterEach(model.drawables) { d -> if (d.id in meshes) remap.drawable(d) else null }
		val deformers = afterEach(model.deformers) { d -> if (d.id in deformerIds) remap.deformer(d) else null }
		// A copied part holds copies of its children (remapped with it); any other copy sits right after its original.
		fun children(list: List<OrgChild>): List<OrgChild> = buildList {
			for (child in list) {
				add(child)
				when (child) {
					is OrgChild.Part -> if (child.id in partIds) add(remap.child(child))
					is OrgChild.Drawable -> if (child.id in meshes) add(remap.child(child))
				}
			}
		}
		val parts = afterEach(model.parts) { p -> if (p.id in partIds) remap.part(p) else null }
			.map { p -> if (p.id in partIds || p.id in partIds.values) p else p.copy(children = children(p.children)) }
		val glues = model.glues + model.glues.filter { it.meshA in meshes && it.meshB in meshes }.map { remap.glue(it).copy(id = null) }
		val paths = model.deformPaths + copies.filter { it.kind == "mesh" }.flatMap { c ->
			model.deformPaths.filter { it.drawableId.raw == c.id }.map { path ->
				path.copy(id = requireNotNull(c.paths[path.id]) { "Pasted path ${path.id} needs an ID" }, drawableId = DrawableId(c.newId))
			}
		}
		require(paths.map { it.id }.distinct().size == paths.size) { "Pasted path ID already exists" }
		val groups = model.vertexGroups + model.vertexGroups.filter { it.drawableId in meshes }.map { it.copy(drawableId = meshes.getValue(it.drawableId)) }
		val parameters = afterEach(model.parameters) { p -> parameterIds[p.id]?.let { p.copy(id = it) } }
		fun tree(nodes: List<ParameterNode>): List<ParameterNode> = buildList {
			for (node in nodes) when (node) {
				is ParameterNode.Param -> { add(node); parameterIds[node.id]?.let { add(ParameterNode.Param(it)) } }
				is ParameterNode.Group -> add(node.copy(children = tree(node.children)))
			}
		}
		val tree = tree(model.parameterTree)
		// A parameter the panel tree does not hold yet stays out of it, as parameters created without a group do.
		return model.copy(drawables = drawables, deformers = deformers, parts = parts, rootChildren = children(model.rootChildren),
			glues = glues, deformPaths = paths, vertexGroups = groups, parameters = parameters, parameterTree = tree).withDerivedRenderRoot()
	}
}
