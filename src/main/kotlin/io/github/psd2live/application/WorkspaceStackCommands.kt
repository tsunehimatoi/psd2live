package io.github.psd2live.application

import io.github.psd2live.core.RigComponents
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.modifier.ModifierAuthoring
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/**
 * One set of commands for every entry of the stack and every role component. `contribution_*` act on a modifier, swing,
 * simulation or interior effect alike, by the entry's type or ID (`swing:<id>`, `simulation:<id>`, `interiorEffect:<id>`,
 * else a modifier's), each routed to that entry's own command, which stays as an alias. `component_put` switches a role
 * component and sets its values, as the settings do.
 */
internal object WorkspaceStackCommands {
	const val PUT = "contribution_put"
	const val MOVE = "contribution_move"
	const val APPLY = "contribution_apply"
	const val DELETE = "contribution_delete"
	const val COMPONENT = "component_put"
	val supported = setOf(PUT, MOVE, APPLY, DELETE, COMPONENT)

	private val types = mapOf("modifier" to ModifierAuthoring.PUT, "swing" to "swing_put", "simulation" to "simulation_put", "interior" to "interior_put")

	/** [operation] as the command of the entry it names, on [document]. */
	fun route(operation: WorkspaceDocumentOperation, document: WorkspaceDocument): WorkspaceDocumentOperation {
		val request = operation.request
		fun entry(id: String): Pair<String, String> = when {
			id.startsWith("swing:") -> "swing" to id.removePrefix("swing:")
			id.startsWith("simulation:") -> "simulation" to id.removePrefix("simulation:")
			id.startsWith("interiorEffect:") -> "interior" to id.removePrefix("interiorEffect:")
			else -> "modifier" to id
		}
		val state = request["state"]?.let { mapOf("state" to it) }.orEmpty()
		return when (operation.operation) {
			PUT -> {
				val type = request.getValue("type").jsonPrimitive.content
				WorkspaceDocumentOperation(requireNotNull(types[type]) { "Unknown entry type: $type" }, JsonObject(request.getValue("value").jsonObject + state))
			}
			MOVE -> WorkspaceDocumentOperation(ModifierAuthoring.MOVE, request)
			APPLY -> {
				val (type, id) = entry(request.getValue("id").jsonPrimitive.content)
				when (type) {
					"modifier" -> WorkspaceDocumentOperation(ModifierAuthoring.APPLY, request)
					"simulation" -> WorkspaceDocumentOperation(io.github.psd2live.core.sim.SimApply.OP, JsonObject(state + ("id" to JsonPrimitive(id))))
					"swing" -> WorkspaceDocumentOperation("swing_delete", JsonObject(state + ("id" to JsonPrimitive(id)) + ("bake" to JsonPrimitive(true))))
					else -> error("An interior effect cannot be applied as keyforms")
				}
			}
			DELETE -> {
				val (type, id) = entry(request.getValue("id").jsonPrimitive.content)
				when (type) {
					"modifier" -> WorkspaceDocumentOperation(ModifierAuthoring.DELETE, JsonObject(state + ("id" to JsonPrimitive(id))))
					"simulation" -> WorkspaceDocumentOperation("simulation_delete", JsonObject(state + ("id" to JsonPrimitive(id))))
					"swing" -> WorkspaceDocumentOperation("swing_delete", JsonObject(state + ("id" to JsonPrimitive(id))))
					else -> {
						val effect = requireNotNull(document.rigEdits.interiorEffects.firstOrNull { it.id == id }) { "Interior effect not found: $id" }
						WorkspaceDocumentOperation("interior_delete", JsonObject(state + ("target" to JsonPrimitive(effect.target))))
					}
				}
			}
			COMPONENT -> WorkspaceDocumentOperation("settings_update", JsonObject(state + ("changes" to component(request, document))))
			else -> error("Not a stack command: ${operation.operation}")
		}
	}

	/** The settings changes of a `component_put`: the switch, and values (shared or per side) of the component's own. */
	private fun component(request: JsonObject, document: WorkspaceDocument): JsonObject {
		val id = request.getValue("id").jsonPrimitive.content
		val component = requireNotNull(RigComponents.switchable.firstOrNull { it.id == id }) { "Unknown component: $id" }
		val config = WorkspaceSettingsCodec.decode(document.settings)
		val own = RigTuning.fields.filter { it.group in component.groups }.map { it.id }.toSet()
		return buildJsonObject {
			request["enabled"]?.jsonPrimitive?.boolean?.let { on ->
				val next = if (on) config.disabledComponents - id else config.disabledComponents + id
				putJsonArray("disabledComponents") { next.sorted().forEach { add(it) } }
			}
			val values = request["values"]?.jsonObject.orEmpty()
			val sides = listOf("left", "right").mapNotNull { side -> request[side]?.jsonObject?.let { side to it } }
			for (key in values.keys + sides.flatMap { it.second.keys }) require(key in own) { "$key is not one of $id's values" }
			if (values.isNotEmpty() || sides.isNotEmpty()) putJsonObject("rigTuning") {
				values.forEach { (k, v) -> put(k, v) }
				sides.forEach { (side, map) -> put(side, map) }
			}
		}
	}
}

/** The stack and component commands, for the agent: each a document edit, batchable with the others. */
internal fun registerStackCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
	val s = WorkspaceResultSchema
	val number = buildJsonObject { put("type", "number") }
	val schemas = mapOf(
		WorkspaceStackCommands.PUT to WorkspaceCommandSchema(buildJsonObject {
			put("state", s.handle())
			put("type", s.choices("modifier", "swing", "simulation", "interior"))
			put("value", buildJsonObject { put("type", "object"); put("description", "The arguments of that type's own command: modifier_put, swing_put, simulation_put or interior_put, without state.") })
		}, listOf("state", "type", "value")),
		WorkspaceStackCommands.MOVE to WorkspaceModifierSchemas.move,
		WorkspaceStackCommands.APPLY to WorkspaceModifierSchemas.apply,
		WorkspaceStackCommands.DELETE to WorkspaceModifierSchemas.delete,
		WorkspaceStackCommands.COMPONENT to WorkspaceCommandSchema(buildJsonObject {
			put("state", s.handle())
			put("id", s.choices(*RigComponents.switchable.map { it.id }.toTypedArray()))
			put("enabled", s.boolean())
			put("values", buildJsonObject { put("type", "object"); put("additionalProperties", number); put("description", "Its rig values by ID (settings rigTuning), shared by both sides.") })
			put("left", buildJsonObject { put("type", "object"); put("additionalProperties", s.nullable(number)); put("description", "The left side's own values of those read per side; null returns one to the shared value.") })
			put("right", buildJsonObject { put("type", "object"); put("additionalProperties", s.nullable(number)); put("description", "As left, for the right side.") })
		}, listOf("state", "id")),
	)
	val descriptions = mapOf(
		WorkspaceStackCommands.PUT to "Create or change a stack entry of any type: a modifier, swing, simulation or interior effect, with value holding that type's own command's arguments.",
		WorkspaceStackCommands.MOVE to "Move a stack entry (a modifier's ID, or swing:<id>, simulation:<id>, interiorEffect:<id>) in the order the stack runs.",
		WorkspaceStackCommands.APPLY to "Apply a stack entry as ordinary keyforms and remove it: a modifier (mode, pose and mesh as modifier_apply), a baked simulation or a swing. An interior effect cannot be applied.",
		WorkspaceStackCommands.DELETE to "Delete a stack entry of any type by its ID.",
		WorkspaceStackCommands.COMPONENT to "Switch a role component (head, eyes, brows, mouth, body, breath, hair) on or off and set its rig values, shared or per side; a component off keeps its deformers and edits, its parameters move nothing it generated.",
	)
	for ((id, schema) in schemas) catalog.register(id, descriptions.getValue(id), schema, hints = MUTATING) { request ->
		WorkspaceOperationOutput(workspace.applyDocumentEdits(request.arguments.getValue("state").jsonPrimitive.content, "Stack",
			listOf(WorkspaceDocumentOperation(id, request.arguments)), MutationAuthor.AGENT).toJson())
	}
}
