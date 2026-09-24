package io.github.psd2live.agent

import io.github.psd2live.core.RigWarpEdit
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
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
        buildJsonObject { put("scope", choices("project", "settings", "preview", "objects", "layers", "parameters", "physics", "paths")); put("query", string()); put("target", string()); put("offset", integer(0)); put("limit", integer(1, 64)) }) { a ->
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
                "settings" -> put("settings", workspace.projectSettings())
                "preview" -> put("preview", workspace.previewSession())
                else -> {
                    val scope = a.getValue("scope").jsonPrimitive.content
                    val items = when (scope) {
                        "objects" -> workspace.listRigObjectSummaries().map { row -> JsonObject(row - "id" - "kind" + ("target" to JsonPrimitive("${row.getValue("kind").jsonPrimitive.content}:${row.getValue("id").jsonPrimitive.content}"))) }
                        "layers" -> snapshot.layers.filterNot { it.deleted }.map { layer -> buildJsonObject {
                            put("id", layer.id); put("name", layer.sourceName); put("visible", layer.visible)
                            put("role", layer.semanticTag); put("side", layer.side)
                            put("type", layer.classificationType); put("parameter", layer.parameterBinding)
                            put("switch_id", layer.switchId)
                            put("mesh", workspace.layerMeshSettings(layer.id))
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

    tool("layer", "Classify an existing source layer using the same fields as the UI Layers table. Omitted fields retain their current values. This rebuilds the generated rig and commits a recoverable history edit.",
        buildJsonObject {
            put("state", string()); put("layer_id", string())
            put("type", choices(*LayerType.entries.map { it.name.lowercase() }.toTypedArray()))
            put("role", choices(*SemanticTag.entries.map { it.name.lowercase() }.toTypedArray()))
            put("side", choices(*Side.entries.map { it.name.lowercase() }.toTypedArray()))
            put("parameter", string()); put("switch_id", integer(0))
        }, listOf("state", "layer_id"), true) { a ->
        require(listOf("type", "role", "side", "parameter", "switch_id").any { it in a }) { "Provide at least one classification field" }
        val id = a.text("layer_id")
        val current = workspace.snapshot().layers.firstOrNull { it.id == id && !it.deleted }
            ?: throw IllegalArgumentException("Layer not found: $id")
        val classification = LayerClassificationOverride(
            type = LayerType.valueOf((a["type"]?.jsonPrimitive?.content ?: current.classificationType).uppercase()),
            tag = SemanticTag.valueOf((a["role"]?.jsonPrimitive?.content ?: current.semanticTag).uppercase()),
            side = Side.valueOf((a["side"]?.jsonPrimitive?.content ?: current.side).uppercase()),
            parameter = a["parameter"]?.jsonPrimitive?.content ?: current.parameterBinding,
            switchId = a["switch_id"]?.jsonPrimitive?.int ?: current.switchId,
        )
        val result = workspace.classifyLayer(id, classification, a.text("state"))
        buildJsonObject {
            put("state", result.historyNodeId)
            put("layer_id", id)
            if (!result.applied) put("applied", false)
        }
    }

    val upscaleSettings = objectSchema(buildJsonObject {
        put("scale", integer(1, 4))
        put("python", string()); put("nunifDirectory", string()); put("modelDirectory", string())
        put("tileSize", integer(64, 512)); put("noiseLevel", integer(-1, 3)); put("neuralAlpha", boolean())
    })
    val projectSettingFields = objectSchema(buildJsonObject {
        listOf("atlasSize", "meshSpacing", "texturePadding", "alphaThreshold").forEach { put(it, integer(0)) }
        listOf("meshOuterMargin", "meshEdgeWidth", "meshMaxEdgeDistance", "meshInteriorDensity",
            "headStrength", "bodyStrength", "mouthThickness", "exportPixelsPerUnit").forEach { put(it, number()) }
        listOf("meshOnly", "generateDeformers", "featureDisplacementEnabled", "mouthOutlineEnabled",
            "generatePhysics", "physicsFrontHair", "physicsBackHair", "physicsEyeJelly",
            "exportMotions", "motionIdle", "motionBlink", "motionNod", "motionShake",
            "exportCmo3", "exportMoc3", "exportJson", "exportHiddenParts", "exportHiddenDrawables",
            "exportGuideImageParts", "exportIncludePhysics", "exportIncludeUserData", "exportIncludeDisplayInfo").forEach { put(it, boolean()) }
        put("mouthShape", choices("flat", "smile", "w", "custom"))
        put("meshEdgeMode", choices("SINGLE", "DOUBLE", "TRIPLE"))
        put("runtimeTarget", string()); put("textureUpscale", upscaleSettings)
    })
    tool("settings", "Update the project generation/export configuration used by the UI. Inspect scope=settings first. Rebuilds the model and commits history; textureUpscale fields are merged with the existing configuration.",
        buildJsonObject { put("state", string()); put("changes", projectSettingFields) }, listOf("state", "changes"), true) { a ->
        workspace.updateProjectSettings(a.text("state"), a.getValue("changes").jsonObject).compact()
    }
    tool("export", "Export the current source artwork and authored rig to the model file family at an absolute output directory. Uses inspect.settings export options and returns written files and warnings. Does not advance history.",
        buildJsonObject { put("state", string()); put("output_directory", string()) }, listOf("state", "output_directory"), true) { a ->
        workspace.exportModel(a.text("state"), a.text("output_directory"))
    }
    tool("export_psd", "Export editable PSD layers using the same writer and upscale settings as the UI. Output path must be absolute; scale 2 or 4 requires configured texture upscale models. Does not advance history.",
        buildJsonObject { put("state", string()); put("path", string()); put("scale", integer(1, 4)); put("include_generated_layers", boolean()) },
        listOf("state", "path"), true) { a ->
        workspace.exportPsd(a.text("state"), a.text("path"), a["scale"]?.jsonPrimitive?.int ?: 1,
            a["include_generated_layers"]?.jsonPrimitive?.boolean ?: true)
    }
    tool("layer_mesh", "Set or reset a source layer's adaptive mesh settings using the same ranges as the UI layer mesh dialog. Omitted fields retain their current values; inspect scope=settings and inspect layers for context.",
        buildJsonObject {
            put("state", string()); put("layer_id", string()); put("reset", boolean())
            put("changes", objectSchema(buildJsonObject {
                put("outerMargin", number()); put("edgeMode", choices("SINGLE", "DOUBLE", "TRIPLE"))
                put("edgeWidth", number()); put("maxEdgeDistance", number()); put("interiorDensity", number())
                put("fillAlgorithm", choices(*io.github.psd2live.core.MeshFillAlgorithm.entries.map { it.name }.toTypedArray()))
                put("suppressBoundaryDiagonals", boolean())
            }))
        }, listOf("state", "layer_id"), true) { a ->
        workspace.setLayerMeshSettings(a.text("state"), a.text("layer_id"), a["changes"]?.jsonObject,
            a["reset"]?.jsonPrimitive?.boolean ?: false).compact()
    }
    val previewValues = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    val previewLocks = buildJsonObject { put("type", "object"); put("additionalProperties", boolean()) }
    tool("preview", "Set current preview parameter values and locks, or reset them, through the UI session state. Values are checked against parameter ranges. This changes the preview session, not model keyforms or history.",
        buildJsonObject {
            put("state", string()); put("mode", choices("set", "reset"))
            put("values", previewValues); put("locks", previewLocks)
        }, listOf("state", "mode"), true) { a -> workspace.setPreviewSession(a) }
    val paintPoint = vector(2)
    val paintColor = arraySchema(integer(0, 255), 4, 4)
    val paintCommon = buildJsonObject {
        put("state", string()); put("layer_id", string()); put("color", paintColor); put("opacity", number())
    }
    tool("paint", "Commit one source-image paint gesture in canvas pixels with the UI paint engine. Brush/eraser, bucket and shapes share the UI raster algorithms. Paint before authoring mesh forms or glue; source edits rebuild topology and are history-recoverable.",
        buildJsonObject { put("request", oneOf(listOf(
            variant("mode", "brush", JsonObject(paintCommon + buildJsonObject {
                put("points", arraySchema(paintPoint, 1, 512)); put("radius", number()); put("hardness", number())
            }), listOf("state", "layer_id", "points")),
            variant("mode", "eraser", JsonObject(paintCommon + buildJsonObject {
                put("points", arraySchema(paintPoint, 1, 512)); put("radius", number()); put("hardness", number())
            }), listOf("state", "layer_id", "points")),
            variant("mode", "bucket", JsonObject(paintCommon + buildJsonObject {
                put("point", paintPoint); put("tolerance", integer(0, 255))
            }), listOf("state", "layer_id", "point")),
            variant("mode", "shape", JsonObject(paintCommon + buildJsonObject {
                put("from", paintPoint); put("to", paintPoint); put("shape", choices("line", "rectangle", "ellipse"))
                put("stroke_width", number()); put("filled", boolean())
            }), listOf("state", "layer_id", "from", "to", "shape")),
            variant("mode", "clear", paintCommon, listOf("state", "layer_id"))
        ))) }, listOf("request"), true) { a ->
        workspace.paintSource(a.getValue("request").jsonObject).compact()
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
    adapted("parameter", "Create, update, or delete a parameter definition. A parameter alone produces no motion: form/deform author its object bindings and keys. Deleting collapses keyed axes at the prior default.",
        mapOf("create" to "parameter_create", "update" to "parameter_update", "delete" to "parameter_delete"), true)
    adapted("asset", "Import an existing PSD or use local PNG paths from your host image generator. create builds an empty workspace from placed source layers, bottom-to-top. split partitions a source layer into polygon-inside/remainder (canvas pixels), before motion authoring; hidden artwork is not generated. For additions prepare a reference, import/register PNG, preview, add. Reference/view handles preserve placement. Generated art is not proof of model motion.",
        mapOf("psd" to "asset_import_psd", "create" to "asset_create_artwork", "split" to "asset_split_artwork", "reference" to "asset_prepare_reference", "import" to "asset_import_png", "register" to "asset_register", "preview" to "asset_preview_composite", "add" to "layer_add_from_asset", "place" to "layer_set_placement", "finalize" to "layer_finalize_placement", "inspect" to "asset_inspect", "reprocess" to "asset_reprocess", "remove" to "layer_soft_delete"), true)
    adapted("physics", "Create, replace, or delete an independent input→output parameter pendulum. Author the output parameter's endpoint forms first. Static view poses do not establish settling or natural motion.", mapOf("put" to "physics_put", "delete" to "physics_delete"), true)
    tool("appearance", "Rename, show/hide or reorganize objects in one ordered edit. For an animated switch use form opacity keys instead of static visibility. Local reparenting changes inherited motion.",
        buildJsonObject { put("state", string()); put("edits", legacy.getValue("object_edit").tool.inputSchema.properties!!.getValue("edits")) }, listOf("state", "edits"), true) { a ->
        workspace.authorRig(a.text("state"), buildJsonArray { add(buildJsonObject { put("op", "structure"); put("edits", a.getValue("edits")) }) }, MutationAuthor.AGENT).compact()
    }
    val structureEdit = objectSchema(buildJsonObject {
        put("action", choices("static", "part", "delete", "create", "rename", "move", "link", "open", "color"))
        put("kind", choices("mesh", "warp", "rotation", "part", "parameter", "param_group"))
        put("id", string()); put("name", string())
        // Null means the root of the corresponding hierarchy; the domain editor validates each action.
        put("parent_id", buildJsonObject {}); put("before_id", buildJsonObject {})
        put("before_kind", choices("mesh", "part", "parameter", "param_group"))
        put("part_id", buildJsonObject {}); put("space", choices("local"))
        put("opacity", number()); put("draw_order", number())
        put("multiply_color", vector(3)); put("screen_color", vector(3))
        put("blend_mode", string()); put("masked_by", arraySchema(string(), 0, 64))
        put("invert_mask", boolean()); put("culling", boolean())
        put("selectable", boolean()); put("quad", boolean())
        put("partner_id", string()); put("linked", boolean()); put("open", boolean())
        put("label_type", string()); put("color", integer(Int.MIN_VALUE))
    }, listOf("action", "kind", "id"))
    tool("structure", "Edit static object properties, delete deformers, assign a deformer Part, or organize parameter folders and 2D links. Edits are ordered and use the same persistent structure journal as the UI. Parameter definitions use parameter instead.",
        buildJsonObject { put("state", string()); put("edits", arraySchema(structureEdit, 1, 128)) }, listOf("state", "edits"), true) { a ->
        val edits = a.getValue("edits").jsonArray
        require(edits.none { edit ->
            val item = edit.jsonObject
            item["kind"]?.jsonPrimitive?.content == "parameter" &&
                item["action"]?.jsonPrimitive?.content in setOf("create", "update", "delete")
        }) { "Use parameter for parameter definitions" }
        workspace.authorRig(a.text("state"), buildJsonArray {
            add(buildJsonObject { put("op", "structure"); put("edits", edits) })
        }, MutationAuthor.AGENT).compact()
    }
    val canvasBounds = objectSchema(buildJsonObject {
        listOf("x", "y", "w", "h").forEach { put(it, number()) }
    }, listOf("x", "y", "w", "h"))
    val canvasPose = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    val canvasBranches = listOf(
        variant("mode", "warp", buildJsonObject {
            put("state", string()); put("id", string()); put("name", string())
            put("meshes", arraySchema(string(), 0, 64))
            put("add_to", choices("parent_of_selected", "parent_of_deformer", "child_of_deformer", "specify_parent"))
            put("deformer_id", string()); put("parent_id", string()); put("part_id", string())
            put("bounds", canvasBounds); put("size_strategy", choices("selection_bounds", "keyform_envelope", "center_align"))
            put("rows", integer(1, 32)); put("columns", integer(1, 32))
        }, listOf("state", "name", "meshes")),
        variant("mode", "rotation", buildJsonObject {
            put("state", string()); put("id", string()); put("name", string())
            put("meshes", arraySchema(string(), 0, 64))
            put("add_to", choices("parent_of_selected", "parent_of_deformer"))
            put("deformer_id", string()); put("part_id", string())
            put("origin", vector(2)); put("angle", number()); put("handle_length", number())
            put("preservePose", boolean())
        }, listOf("state", "name")),
        variant("mode", "glue", buildJsonObject {
            put("state", string()); put("id", string()); put("mesh_a", string()); put("mesh_b", string())
            put("pose", canvasPose); put("distance", number())
        }, listOf("state", "mesh_a", "mesh_b")),
        variant("mode", "topology", buildJsonObject {
            put("state", string()); put("id", string())
            put("action", choices("merge", "duplicate", "connect", "delete", "subdivide", "split", "knife"))
            put("vertices", arraySchema(integer(0), 0, 65536))
            put("anchors", arraySchema(objectSchema(buildJsonObject {
                put("vertex", integer(0)); put("x", number()); put("y", number())
            }), 0, 4096))
            put("edges", arraySchema(arraySchema(integer(0), 2, 2), 0, 65536))
        }, listOf("state", "id", "action", "vertices")),
    )
    tool("canvas", "Use the canvas editor's persisted algorithms to create a Warp or Rotation, pair Glue vertices at a pose, or edit mesh topology. Topology indices are from the current mesh and require fresh state. Creation accepts an optional stable ID; otherwise one is generated.",
        buildJsonObject { put("request", oneOf(canvasBranches)) }, listOf("request"), true) { a ->
        val input = a.getValue("request").jsonObject
        validateAuthoringSchema(input, oneOf(canvasBranches))
        val mode = input.text("mode")
        val id = input["id"]?.jsonPrimitive?.content ?: "Agent${mode.replaceFirstChar(Char::uppercase)}_${UUID.randomUUID().toString().take(8)}"
        val op = when (mode) {
            "warp" -> "canvas_create_warp"
            "rotation" -> "canvas_create_rotation"
            "glue" -> "canvas_create_glue"
            "topology" -> "canvas_topology"
            else -> error("Unknown canvas mode")
        }
        val command = buildJsonObject {
            put("op", op); put("id", id)
            input.forEach { (key, value) -> if (key !in setOf("mode", "state", "id")) put(key, value) }
        }
        val result = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
        buildJsonObject {
            put("state", result.historyNodeId); put("id", id)
            if (!result.applied) put("applied", false)
        }
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
