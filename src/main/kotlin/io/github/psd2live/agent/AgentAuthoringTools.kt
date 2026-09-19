package io.github.psd2live.agent

import io.github.psd2live.core.RigWarpEdit
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.util.UUID

/** The default surface describes editing domains, never task-specific plans or skills. */
internal fun installAuthoringTools(server: Server, workspace: AgentWorkspace) {
    registerSourceEditing(server, workspace)
    registerAuthoringObservation(server, workspace)
    val legacy = server.tools.toMap()
    server.removeTools(legacy.keys.toList())
    server.prompts.keys.toList().forEach(server::removePrompt)
    val read = ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false)
    val write = ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)

    fun tool(name: String, description: String, fields: JsonObject, required: List<String> = emptyList(),
             mutating: Boolean = false, handler: suspend (JsonObject) -> JsonObject) {
        server.addTool(name, description, ToolSchema(properties = fields.flattenForPublication().jsonObject, required = required), toolAnnotations = if (mutating) write else read) { request ->
            try {
                val arguments = request.arguments ?: JsonObject(emptyMap())
                validateAuthoringSchema(arguments, objectSchema(fields, required))
                compactResult(handler(arguments))
            }
            catch (e: IllegalArgumentException) { authoringError(e, workspace) }
            catch (e: IllegalStateException) { authoringError(e, workspace) }
        }
    }

    tool("inspect", "Read project context, find objects/layers/parameters, or inspect one kind:id's direct axes, channels and parent. No point arrays. Query and page before expanding.",
        buildJsonObject { put("scope", choices("project", "objects", "layers", "parameters", "physics", "paths")); put("query", string()); put("target", string()); put("offset", integer(0)); put("limit", integer(1, 64)) }) { a ->
        val snapshot = workspace.snapshot()
        val state = snapshot.historyHeadNodeId
        val target = a["target"]?.jsonPrimitive?.content
        buildJsonObject {
            state?.let { put("state", it) }
            if (target != null) {
                val ref = parseTarget(target)
                val obj = workspace.getObject(ref)
                put("target", target); put("name", obj.name)
                obj.parentId?.let { put("parent", it) }
                put("visible", obj.visible)
                obj.geometry?.let { geometry ->
                    put("forms", geometry.keyformCount)
                    putJsonObject("axes") { geometry.axes.forEach { axis -> put(axis.parameterId, JsonArray(axis.keys.map(::JsonPrimitive))) } }
                }
                putJsonArray("channels") { obj.channels.forEach { channel -> add(buildJsonObject {
                    put("channel", channel.channel); put("value", channel.staticValue)
                    if (channel.axes.isNotEmpty()) putJsonObject("axes") { channel.axes.forEach { axis -> put(axis.parameterId, JsonArray(axis.keys.map(::JsonPrimitive))) } }
                }) } }
                if (ref.kind == "mesh") {
                    val paths = workspace.currentPuppet()?.deformPaths?.filter { it.drawableId.raw == ref.id }.orEmpty()
                    if (paths.isNotEmpty()) {
                        putJsonArray("paths") {
                            paths.forEach { p ->
                                add(buildJsonObject {
                                    put("id", p.id); put("level", p.editLevel); put("width", p.width)
                                    put("hardness", p.hardness); put("closed", p.closed); put("pointCount", p.points.size)
                                })
                            }
                        }
                    }
                }
                // A short ownership chain explains inherited motion without returning ancestor geometry.
                val objects = workspace.listRigObjectSummaries().associateBy { it.getValue("id").jsonPrimitive.content }
                var parent = obj.parentId
                val seen = mutableSetOf<String>()
                putJsonArray("inherited") {
                    while (parent != null) {
                        val parentId = parent ?: break
                        if (!seen.add(parentId)) break
                        val summary = objects[parentId] ?: break
                        val kind = summary.getValue("kind").jsonPrimitive.content
                        val ancestor = workspace.getObject(AgentKeyformTargetRef(kind, parentId))
                        add(buildJsonObject {
                            put("target", "$kind:$parent"); put("name", ancestor.name)
                            putJsonArray("parameters") { ancestor.geometry?.axes.orEmpty().forEach { add(JsonPrimitive(it.parameterId)) } }
                        })
                        parent = ancestor.parentId
                    }
                }
            } else when (a["scope"]?.jsonPrimitive?.content ?: "project") {
                "project" -> {
                    put("loaded", snapshot.loaded); put("busy", snapshot.busy)
                    putJsonArray("canvas") { snapshot.canvasWidth?.let { add(JsonPrimitive(it)) }; snapshot.canvasHeight?.let { add(JsonPrimitive(it)) } }
                    snapshot.selectedLayerId?.let { put("selection", it) }
                    put("layers", snapshot.layers.count { !it.deleted }); put("parameters", snapshot.parameters.size)
                    snapshot.persistenceError?.let { put("persistenceError", it) }
                }
                "physics" -> put("groups", JsonArray(workspace.listPhysics().map { it.toJson() }))
                else -> {
                    val scope = a.getValue("scope").jsonPrimitive.content
                    val items = when (scope) {
                        "objects" -> workspace.listRigObjectSummaries().map { row -> JsonObject(row - "id" - "kind" + ("target" to JsonPrimitive("${row.getValue("kind").jsonPrimitive.content}:${row.getValue("id").jsonPrimitive.content}"))) }
                        "layers" -> snapshot.layers.filterNot { it.deleted }.map { layer -> buildJsonObject {
                            put("id", layer.id); put("name", layer.sourceName); put("visible", layer.visible)
                            put("role", layer.semanticTag); put("side", layer.side)
                            putJsonArray("bounds") { listOf(layer.opaqueBounds.left, layer.opaqueBounds.top, layer.opaqueBounds.right, layer.opaqueBounds.bottom).forEach { add(JsonPrimitive(it)) } }
                        } }
                        "parameters" -> snapshot.parameters.map { p -> buildJsonObject { put("id", p.id); put("name", p.name); put("min", p.min); put("max", p.max); put("default", p.default) } }
                        "paths" -> workspace.currentPuppet()?.deformPaths.orEmpty().map { p -> buildJsonObject {
                            put("id", p.id); put("target", "mesh:${p.drawableId.raw}"); put("level", p.editLevel)
                            put("width", p.width); put("hardness", p.hardness); put("closed", p.closed); put("pointCount", p.points.size)
                        } }
                        else -> error("Unknown inspect scope")
                    }.filter { a["query"]?.jsonPrimitive?.content?.let { query -> it.toString().contains(query, ignoreCase = true) } ?: true }
                    val offset = a["offset"]?.jsonPrimitive?.int ?: 0; val limit = a["limit"]?.jsonPrimitive?.int ?: 24
                    require(offset >= 0 && limit in 1..64)
                    put("items", JsonArray(items.drop(offset).take(limit)))
                    if (offset + limit < items.size) put("next", offset + limit)
                }
            }
        }
    }

    val key = buildJsonObject { put("type", "object"); put("minProperties", 1); put("additionalProperties", number()) }
    val selection = objectSchema(buildJsonObject {
        put("rect", vector(4)); put("center", vector(2)); put("line", vector(4)); put("radius", number()); put("feather", number()); put("hardness", number())
    })
    fun operation(type: String, properties: JsonObject, required: List<String>) = variant("type", type, JsonObject(properties + ("selection" to selection)), required)
    val operations = arraySchema(oneOf(listOf(
        operation("translate", buildJsonObject { put("delta", vector(2)) }, listOf("delta")),
        operation("scale", buildJsonObject { put("factors", vector(2)); put("pivot", vector(2)) }, listOf("factors")),
        operation("rotate", buildJsonObject { put("degrees", number()); put("pivot", vector(2)) }, listOf("degrees")),
        operation("arc", buildJsonObject { put("degrees", number()); put("root", vector(2)); put("tip", vector(2)); put("root_pin", number()) }, listOf("degrees", "root", "tip")),
        operation("curve", buildJsonObject { put("axis", choices("x", "y")); put("controls", vector(4)) }, listOf("controls")),
        operation("landmarks", buildJsonObject { put("from", arraySchema(vector(2), 1, 16)); put("to", arraySchema(vector(2), 1, 16)) }, listOf("from", "to"))
    )), 1, 16)
    tool("deform", "Edit whole ArtMesh/Warp surfaces at exact keys, atomically across changes. All points participate including empty cage regions. Units: fixed input bounds, normalized x-right/y-down; rotations in degrees. arc bends cross-sections along root→tip; root_pin is a fixed length fraction. Optional brush hardness gives a broad plateau. Unspecified directly bound axes are an error. This edits local shapes; parent motion is inherited.",
        buildJsonObject {
            put("state", string()); put("changes", arraySchema(objectSchema(buildJsonObject {
                put("target", string()); put("key", key); put("operations", operations); put("selection", selection)
            }, listOf("target", "key", "operations")), 1, 128))
        }, listOf("state", "changes"), true) { a ->
        val edits = JsonArray(a.getValue("changes").jsonArray.map { change ->
            JsonObject(change.jsonObject + ("op" to JsonPrimitive("deform")))
        })
        workspace.authorRig(a.text("state"), edits, MutationAuthor.AGENT).compact()
    }

    val channels = objectSchema(buildJsonObject {
        put("opacity", number()); put("drawOrder", number()); put("multiplyColor", vector(3)); put("screenColor", vector(3))
        put("glueIntensity", number()); put("flipX", boolean()); put("flipY", boolean())
    })
    val formBase = buildJsonObject { put("target", string()); put("key", key) }
    tool("form", "Author key collections atomically. seed captures interpolated geometry without changing other keys. copy transfers selected channels (omit for all) from an explicit source key. set writes scalar/color channels or a rotation form, never mesh point arrays. Keys name only the destination object's axes, not the viewing pose.",
        buildJsonObject { put("state", string()); put("changes", arraySchema(oneOf(listOf(
            variant("op", "seed", formBase, listOf("target", "key")),
            variant("op", "copy", JsonObject(formBase + buildJsonObject { put("from", key); put("destination", string()); put("channels", arraySchema(string(), 1, 8)) }), listOf("target", "from", "key")),
            variant("op", "set", JsonObject(formBase + buildJsonObject { put("channels", channels); put("geometry", objectSchema(buildJsonObject {
                put("originX", number()); put("originY", number()); put("angle", number()); put("scale", number())
            })) }), listOf("target", "key")),
            variant("op", "delete", buildJsonObject { put("target", string()); put("parameter", string()); put("value", number()); put("channel", string()) }, listOf("target", "parameter"))
        )), 1, 128)) }, listOf("state", "changes"), true) { a -> workspace.authorRig(a.text("state"), a.getValue("changes").jsonArray, MutationAuthor.AGENT).compact() }

    tool("rig", "Create a fitted independent Warp for meshes sharing a parent. Existing keyforms and UVs migrate; the parent lattice constrains fit precision. Use deform directly when no independent motion layer is needed. Returned target is the new Warp.",
        buildJsonObject { put("state", string()); put("name", string()); put("targets", arraySchema(string(), 1, 64)) }, listOf("state", "name", "targets"), true) { a ->
        val targets = a.getValue("targets").jsonArray.map { parseTarget(it.jsonPrimitive.content).also { ref -> require(ref.kind == "mesh") { "Warp targets must be meshes" } } }
        val parents = targets.map { workspace.getObject(it).parentId }.distinct()
        require(parents.size == 1 && parents.single() != null) { "Targets need a common Warp parent" }
        val id = "AgentWarp_${UUID.randomUUID().toString().take(8)}"
        val edit = RigWarpEdit(id, a.text("name"), parents.single()!!, targets.map { it.id }, 16, 16, fitLocal = true)
        val result = workspace.authorRig(a.text("state"), buildJsonArray { add(buildJsonObject { put("op", "warp"); put("warp", edit.toJson()) }) }, MutationAuthor.AGENT)
        JsonObject(result.compact() + ("target" to JsonPrimitive("warp:$id")))
    }

    // Narrow, typed adapters retain mature asset/render/parameter primitives during backend migration.
    // Only these declared variants are callable; no arbitrary tool-name dispatcher is exposed.
    fun adapted(name: String, description: String, variants: Map<String, String>, mutating: Boolean) {
        val branches = variants.map { (mode, oldName) ->
            val schema = legacy.getValue(oldName).tool.inputSchema
            val properties = JsonObject(schema.properties.orEmpty().filterKeys { it != "task_id" && !(oldName == "asset_import_png" && it == "png_base64") }.mapKeys { if (it.key == "expected_history_head_node_id") "state" else it.key }
                .mapValues { stripSchemaDescriptions(it.value) })
            variant("mode", mode, properties, schema.required.orEmpty().filter { it != "task_id" }.map { if (it == "expected_history_head_node_id") "state" else it } + if (oldName == "asset_import_png") listOf("png_path") else emptyList())
        }
        server.addTool(name, description, ToolSchema(properties = buildJsonObject { put("request", oneOf(branches)) }.flattenForPublication().jsonObject, required = listOf("request")), toolAnnotations = if (mutating) write else read) { request ->
            try {
                val input = request.arguments!!.getValue("request").jsonObject
                validateAuthoringSchema(input, oneOf(branches))
                val old = legacy.getValue(requireNotNull(variants[input.text("mode")]) { "Unknown mode" })
                val arguments = JsonObject((input - "mode").mapKeys { if (it.key == "state" && "expected_history_head_node_id" in old.tool.inputSchema.properties.orEmpty()) "expected_history_head_node_id" else it.key })
                val result = old.handler.invoke(this, CallToolRequest(CallToolRequestParams(old.tool.name, arguments)))
                if (result.isError == true) result else {
                    val value = result.structuredContent ?: result.content.filterIsInstance<TextContent>().firstOrNull()?.text?.let {
                        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                    }
                    if (value != null && "historyNodeId" in value) compactResult(buildJsonObject {
                        put("state", value.getValue("historyNodeId"))
                        listOf("affectedLayerIds", "affectedParameterIds", "affectedObjectIds").forEach { field ->
                            value[field]?.takeIf { it is JsonArray && it.isNotEmpty() }?.let { put(field, it) }
                        }
                    }) else result
                }
            } catch (e: IllegalArgumentException) { authoringError(e, workspace) }
              catch (e: IllegalStateException) { authoringError(e, workspace) }
        }
    }
    adapted("view", "Observe model poses with a fixed camera, source layers or coverage. poses returns one labeled sheet; shared parameters are overridden per tile. Reuse canvas rectangles across comparisons. Static sampling does not simulate physics. Inspect only relevant regions; images are not proof of unobserved poses.",
        mapOf("compare" to "view_compare_history", "motion" to "view_sample_motion", "poses" to "view_render_poses", "model" to "view_render_model", "layer" to "view_render_layer", "context" to "view_render_context", "coverage" to "view_check_coverage"), false)
    adapted("parameter", "Create or change a parameter definition. A parameter alone produces no motion: form/deform author its object bindings and keys. IDs and ranges come from inspect.",
        mapOf("create" to "parameter_create", "update" to "parameter_update"), true)
    adapted("asset", "Use local PNG paths from your host image generator. create builds an empty workspace from placed source layers, bottom-to-top. split partitions a source layer into polygon-inside/remainder (canvas pixels), before motion authoring; hidden artwork is not generated. For additions prepare a reference, import/register PNG, preview, add. Reference/view handles preserve placement. Generated art is not proof of model motion.",
        mapOf("create" to "asset_create_artwork", "split" to "asset_split_artwork", "reference" to "asset_prepare_reference", "import" to "asset_import_png", "register" to "asset_register", "preview" to "asset_preview_composite", "add" to "layer_add_from_asset", "place" to "layer_set_placement", "finalize" to "layer_finalize_placement", "inspect" to "asset_inspect", "reprocess" to "asset_reprocess", "remove" to "layer_soft_delete"), true)
    adapted("physics", "Configure an independent input→output parameter pendulum. Author the output parameter's endpoint forms first. This edits physics; static view poses do not establish settling or natural motion.", mapOf("put" to "physics_put"), true)
    tool("appearance", "Rename, show/hide or reorganize objects in one ordered edit. For an animated switch use form opacity keys instead of static visibility. Local reparenting changes inherited motion.",
        buildJsonObject { put("state", string()); put("edits", legacy.getValue("object_edit").tool.inputSchema.properties!!.getValue("edits")) }, listOf("state", "edits"), true) { a ->
        workspace.authorRig(a.text("state"), buildJsonArray { add(buildJsonObject { put("op", "structure"); put("edits", a.getValue("edits")) }) }, MutationAuthor.AGENT).compact()
    }
    val pathBranches = listOf(
        variant("mode", "get", buildJsonObject {
            put("target", string())
            put("path_id", string())
        }, emptyList()),
        variant("mode", "list", buildJsonObject {
            put("target", string())
        }, emptyList()),
        variant("mode", "preview", buildJsonObject {
            put("target", string())
            put("path_id", string())
            put("moved_points", arraySchema(buildJsonObject {}, 2, 128))
            put("width", number())
            put("hardness", number())
            put("show_width", boolean())
            put("show_hardness", boolean())
            put("render", boolean())
        }, listOf("target", "path_id", "moved_points")),
        variant("mode", "put", buildJsonObject {
            put("state", string())
            put("target", string())
            put("id", string())
            put("points", arraySchema(buildJsonObject {}, 2, 128))
            put("width", number())
            put("hardness", number())
            put("closed", boolean())
            put("level", integer(2, 3))
        }, listOf("state", "target", "points")),
        variant("mode", "delete", buildJsonObject {
            put("state", string())
            put("path_id", string())
        }, listOf("state", "path_id")),
        variant("mode", "deform", buildJsonObject {
            put("state", string())
            put("target", string())
            put("path_id", string())
            put("key", key)
            put("moved_points", arraySchema(buildJsonObject {}, 2, 128))
        }, listOf("state", "target", "path_id", "key", "moved_points")),
    )
    server.addTool(
        "path",
        "Inspect, create, delete, dry-run preview, or deform an ArtMesh with Deform Paths. Points use mesh local coordinates [x, y] with auto-binding to mesh triangles. deform bakes moving least squares (MLS) displacement as a keyform at key.",
        ToolSchema(
            properties = buildJsonObject {
                put("request", oneOf(pathBranches))
            }.flattenForPublication().jsonObject,
            required = listOf("request"),
        ),
        toolAnnotations = write,
    ) { request ->
        try {
            val args = request.arguments ?: JsonObject(emptyMap())
            val input = args["request"]?.jsonObject ?: args
            validateAuthoringSchema(input, oneOf(pathBranches))
            val mode = input.text("mode")
            val puppet = workspace.currentPuppet() ?: error("No model is loaded")
            if (mode == "preview") {
                val preview = AgentPathTools.preview(puppet, input)
                val base64 = preview["previewImage"]?.jsonPrimitive?.contentOrNull
                if (base64 != null) {
                    CallToolResult(
                        content = listOf(
                            TextContent(preview.toString()),
                            ImageContent(base64, "image/png"),
                        ),
                        structuredContent = preview,
                    )
                } else {
                    compactResult(preview)
                }
            } else {
                val result = when (mode) {
                    "get", "list" -> AgentPathTools.inspect(puppet, input)
                    "put" -> {
                        val (pathId, command) = AgentPathTools.createPutCommand(puppet, input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", res.historyNodeId)
                            put("path_id", pathId)
                            put("target", input.text("target"))
                        }
                    }
                    "delete" -> {
                        val (pathId, command) = AgentPathTools.createDeleteCommand(input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", res.historyNodeId)
                            put("deleted", pathId)
                        }
                    }
                    "deform" -> {
                        val command = AgentPathTools.createDeformCommand(input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", res.historyNodeId)
                            put("target", input.text("target"))
                            put("key", input.getValue("key"))
                            if (res.affectedObjectIds.isNotEmpty()) put("changed", JsonArray(res.affectedObjectIds.map(::JsonPrimitive)))
                        }
                    }
                    else -> error("Unknown path mode: $mode")
                }
                compactResult(result)
            }
        } catch (e: IllegalArgumentException) { authoringError(e, workspace) }
          catch (e: IllegalStateException) { authoringError(e, workspace) }
    }

    adapted("revision", "Save, checkpoint, inspect history or restore a chosen snapshot. Every mutation returns a new state; chain it. Restore is a write, not preview. Keep your own task plan; checkpoints preserve useful progress.",
        mapOf("save" to "project_save", "checkpoint" to "history_checkpoint", "list" to "history_list", "restore" to "history_checkout"), true)
}

private fun parseTarget(value: String): AgentKeyformTargetRef {
    val pair = value.split(':', limit = 2)
    require(pair.size == 2 && pair[0] in setOf("mesh", "warp", "rotation", "part", "glue") && pair[1].isNotBlank()) { "Use kind:id from inspect" }
    return AgentKeyformTargetRef(pair[0], pair[1])
}
private fun AgentWorkspaceMutationResult.compact() = buildJsonObject {
    put("state", historyNodeId)
    // Emitted only when false: a no-op is the case a caller has to act on, and leaving it off a normal
    // response keeps the common payload small.
    if (!applied) put("applied", false)
    if (affectedObjectIds.isNotEmpty()) put("changed", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
}
private fun compactResult(value: JsonObject) = CallToolResult(content = listOf(TextContent(value.toString())), structuredContent = value)
private fun authoringError(e: Exception, workspace: AgentWorkspace): CallToolResult {
    val value = buildJsonObject { put("error", e.message ?: "Invalid authoring request"); workspace.snapshot().historyHeadNodeId?.let { put("state", it) } }
    return CallToolResult(content = listOf(TextContent(value.toString())), structuredContent = value, isError = true)
}
private fun string() = buildJsonObject { put("type", "string") }
private fun number() = buildJsonObject { put("type", "number") }
private fun boolean() = buildJsonObject { put("type", "boolean") }
private fun integer(min: Int, max: Int? = null) = buildJsonObject { put("type", "integer"); put("minimum", min); max?.let { put("maximum", it) } }
private fun choices(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
private fun vector(size: Int) = arraySchema(number(), size, size)
private fun arraySchema(items: JsonObject, min: Int, max: Int) = buildJsonObject { put("type", "array"); put("items", items); put("minItems", min); put("maxItems", max) }
private fun objectSchema(fields: JsonObject, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object"); put("properties", fields); put("additionalProperties", false)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
}
private fun variant(discriminator: String, value: String, fields: JsonObject, required: List<String>) =
    objectSchema(JsonObject(fields + (discriminator to buildJsonObject { put("type", "string"); put("const", value) })), listOf(discriminator) + required)
private fun oneOf(branches: List<JsonObject>) = buildJsonObject { put("oneOf", JsonArray(branches)) }

/** Strict tool-schema validators such as GLM's reject oneOf/const, so the published schema
 *  merges each variant set into one flat object (union of properties, const discriminators
 *  folded into an enum, per-variant required fields listed in the description). Server-side
 *  validation still runs against the exact variant contract via validateAuthoringSchema. */
private fun JsonElement.flattenForPublication(): JsonElement = when (this) {
    is JsonObject -> flattenVariantSet(this)
    is JsonArray -> JsonArray(map { it.flattenForPublication() })
    else -> this
}

private fun flattenVariantSet(node: JsonObject): JsonObject {
    val branches = (node["oneOf"] as? JsonArray)?.map { flattenVariantSet(it.jsonObject) }
        ?: return JsonObject(node.mapValues { (_, value) -> value.flattenForPublication() })
    val properties = LinkedHashMap<String, JsonElement>()
    val discriminators = LinkedHashMap<String, MutableList<String>>()
    val requiredLists = mutableListOf<List<String>>()
    val variants = mutableListOf<String>()
    for (branch in branches) {
        val branchRequired = branch["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        requiredLists += branchRequired
        val constants = mutableListOf<String>()
        val constKeys = mutableSetOf<String>()
        for ((key, field) in branch["properties"]?.jsonObject.orEmpty()) {
            val const = (field as? JsonObject)?.get("const") as? JsonPrimitive
            if (const != null) {
                discriminators.getOrPut(key) { mutableListOf() }.add(const.content)
                constants += const.content
                constKeys += key
            } else {
                properties.getOrPut(key) { field }
            }
        }
        val rest = branchRequired - constKeys
        val name = constants.joinToString("&")
        variants += if (rest.isEmpty()) name else "$name (needs ${rest.joinToString()})"
    }
    for ((key, values) in discriminators) {
        properties[key] = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.distinct().map(::JsonPrimitive))) }
    }
    val required = requiredLists.reduceOrNull { acc, list -> acc.filter { it in list } }.orEmpty()
    return buildJsonObject {
        put("type", "object"); put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.distinct().map(::JsonPrimitive)))
        if (variants.isNotEmpty()) put("description", "Exactly one variant per call: ${variants.joinToString("; ")}.")
        put("additionalProperties", false)
    }
}
private fun stripSchemaDescriptions(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterKeys { it !in setOf("description", "examples", "title") }.mapValues { stripSchemaDescriptions(it.value) })
    is JsonArray -> JsonArray(value.map(::stripSchemaDescriptions))
    else -> value
}
