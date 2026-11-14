package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceOperationDefinition
import kotlinx.serialization.json.*

/**
 * One core tool for the operations `<name>_<op>`: the caller names the op, and the request is validated against that
 * operation's exact schema. Members are every registry operation with the prefix, less [excluded] ones, so a new
 * operation of the family joins it without a list to update.
 */
internal class AgentToolFamily(val name: String, val summary: String, private val excluded: (String) -> Boolean = { false }) {
    fun members(definitions: List<WorkspaceOperationDefinition>): List<WorkspaceOperationDefinition> =
        definitions.filter { it.id.startsWith("${name}_") && !excluded(it.id.removePrefix("${name}_")) }

    fun op(operation: String) = operation.removePrefix("${name}_")
}

/**
 * The core profile's families. Private drafts, live auditions and previews, display annotations and the shared preset
 * library stay behind workspace_call: they are interactive sessions or bookkeeping, not model authoring.
 */
internal val AGENT_TOOL_FAMILIES = listOf(
    AgentToolFamily("view", "Render the actual model for observation: a pose, one layer, a layer in context, a pose sheet, " +
        "coverage of a canvas rectangle, history nodes side by side, or sampled exported motion. Returns PNG images."),
    AgentToolFamily("parameter", "Create, update or delete Cubism parameters. Deleting collapses every keyform axis at its default."),
    AgentToolFamily("motion", "Read and edit motion clips and their parameter timelines: clips, keys, curves, poses, events and generated presets."),
    AgentToolFamily("expression", "Read and edit the model's expressions (exp3): create from a pose or snapshot, set entries with Add/Multiply/Overwrite blends and fades."),
    AgentToolFamily("hit_area", "Set up, order, delete or test the model's hit areas (model3 HitAreas): name meshes, add the Head/Body preset, test a point at a pose."),
    AgentToolFamily("pose", "Read and edit the pose (pose3): groups of parts of which one shows at a time, with linked parts and the switch fade."),
    AgentToolFamily("skeleton", "Read, infer and edit the authored skeleton (bones, binding, enable) and solve FK/IK poses.") {
        it.startsWith("draft_")
    },
    AgentToolFamily("path", "Read, create, delete, preview or apply Deform Paths on ArtMeshes."),
    AgentToolFamily("physics", "Create, change, delete, sample, fit, order or import physics groups, or apply a stored preset to one.") {
        it.startsWith("audition") || it.startsWith("preset_")
    },
    AgentToolFamily("simulation", "2D cloth and hair simulation on ArtMeshes: put, delete, sample, bake to parameters and pendulums, or clear a bake.") {
        it.startsWith("preview")
    },
    AgentToolFamily("swing", "Create, replace or delete a regenerating swing on Warps or Meshes.") { it.startsWith("preview") },
    AgentToolFamily("modifier", "Inspect and edit explicit preset content: add independent instances or groups, adjust, enable, reorder, remove, or apply a prefix as ordinary content."),
    AgentToolFamily("plugin_dev", "Discover host plugin contracts, scaffold standalone Kotlin/Gradle plugin projects, register, build, list, or detach local dev plugin sources."),
    AgentToolFamily("source_paint", "Paint one gesture on a source layer in canvas units: brush, pencil, eraser, bucket, shape or clear."),
    AgentToolFamily("snapshot", "Save, rename, delete, apply, read or list named parameter poses. Does not change rig history."),
    AgentToolFamily("history", "List the branching history, move HEAD to a node, or add an explicit checkpoint.") { it.startsWith("annotation") },
    AgentToolFamily("project", "Open, import, create, save or export the project. Switching projects rejects unsaved changes unless discard_unsaved is true."),
    AgentToolFamily("job", "Read, wait for, cancel or list process-local background jobs."),
)

/** Operations the core profile publishes as their own tools. */
internal val CORE_OPERATIONS: Set<String> = linkedSetOf(
    "workspace_overview", "workspace_inspect", "workspace_list_operations", "workspace_get_operation",
    "workspace_apply_edits", "workspace_preview_edits", "rig_deform", "keyform_apply",
    "author_axis", "author_physics", "author_compile",
)

/** The core tools: single operations, families and workspace_call. */
internal val CORE_TOOL_COUNT = CORE_OPERATIONS.size + AGENT_TOOL_FAMILIES.size + 1

internal const val OP_FIELD = "op"

/**
 * A family publishes the union of its members' fields without per-member branches, so strict hosts accept it and the
 * listing stays small. A field whose shape differs between members is published without a shape; execution validates
 * the chosen member exactly either way.
 */
internal fun AgentToolFamily.inputSchema(members: List<WorkspaceOperationDefinition>): JsonObject {
    val fields = linkedMapOf<String, JsonElement>()
    val varying = mutableSetOf<String>()
    members.forEach { member ->
        val request = compactRequest(member.requestSchema)
        val branches = request["oneOf"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        (listOf(request) + branches).forEach { schema ->
            schema["properties"]?.jsonObject?.forEach { (field, exact) ->
                val shape = flatShape(exact)
                val known = fields[field]
                // An unconstrained placeholder (the root of a oneOf request) yields to a branch's actual shape.
                if (known == null || known == JsonObject(emptyMap())) fields[field] = shape
                else if (known != shape && shape != JsonObject(emptyMap())) varying += field
            }
        }
    }
    varying.forEach { field ->
        fields[field] = buildJsonObject { put("description", "Depends on op; workspace_get_operation describes it.") }
    }
    return buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject(OP_FIELD) {
                put("type", "string"); put("enum", JsonArray(members.map { JsonPrimitive(op(it.id)) }))
                put("description", "The operation: ${name}_<op>.")
            }
            putJsonObject("request") {
                put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
                put("description", "Fields of the chosen op. Required fields are listed per op in the tool description.")
            }
            if (members.any { it.jobBacked }) put(WAIT_FIELD, waitSchema())
        }
        put("required", JsonArray(listOf(JsonPrimitive(OP_FIELD), JsonPrimitive("request"))))
    }
}

private const val SEE_OPERATION = "workspace_get_operation describes the exact shape."

/** The same field without oneOf, const or references; it may admit more than the exact schema, never less. */
private fun flatShape(element: JsonElement): JsonElement {
    val shape = element as? JsonObject ?: return element
    fun described(base: JsonObject, note: String) = JsonObject(base + ("description" to JsonPrimitive(
        listOfNotNull(shape["description"]?.jsonPrimitive?.content, note).joinToString(" "))))
    if ("\$ref" in shape) return described(JsonObject(emptyMap()), SEE_OPERATION)
    shape["oneOf"]?.jsonArray?.let { raw ->
        val branches = raw.map { flatShape(it).jsonObject }
        val values = branches.filter { it["type"] != JsonPrimitive("null") }
        val base = JsonObject(shape - "oneOf" - "description")
        return when {
            values.size == 1 && values.size < branches.size -> described(JsonObject(values.single() - "description" + base), "May be null.")
            values.isNotEmpty() && values.all { it["type"] == JsonPrimitive("object") } -> described(buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(values.flatMap { it["properties"]?.jsonObject.orEmpty().entries }.associate { it.key to it.value }))
            }, "Fields depend on the variant; $SEE_OPERATION")
            else -> described(base, SEE_OPERATION)
        }
    }
    return JsonObject(shape.mapNotNull { (key, value) ->
        when (key) {
            "const" -> "enum" to JsonArray(listOf(value))
            "properties" -> key to JsonObject(value.jsonObject.mapValues { flatShape(it.value) })
            "items", "additionalProperties" -> key to flatShape(value)
            else -> key to value
        }
    }.toMap())
}

internal fun AgentToolFamily.description(members: List<WorkspaceOperationDefinition>): String = buildString {
    append(summary)
    if (members.any { it.jobBacked }) append(" Ops marked [job] run in the background. ").append(CORE_WAIT_NOTE)
    append(" Ops and required request fields:")
    members.forEach { member ->
        val request = compactRequest(member.requestSchema)
        val required = request["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        append("\n- ").append(op(member.id))
        if (required.isNotEmpty()) append(": ").append(required.joinToString(", "))
        if ("oneOf" in request) append(" (variants)")
        if (member.jobBacked) append(" [job]")
    }
}
