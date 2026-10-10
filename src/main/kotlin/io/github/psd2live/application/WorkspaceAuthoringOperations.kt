package io.github.psd2live.application

import io.github.psd2live.application.validateOperationSchema

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.project.WorkspaceKeyformTargetRef
import io.github.psd2live.project.WorkspaceMutationResult

import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.sim.SimMaterialPreset
import kotlinx.serialization.json.*
import java.util.UUID

/** Domain operations call application commands directly; no transport handler is constructed. */
internal fun registerAuthoringOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceBackend, commands: WorkspaceCommands, jobs: WorkspaceJobs) {
    val directNames = mapOf("inspect" to "workspace_inspect", "layer" to "layer_classify", "settings" to "settings_update",
        "layer_mesh" to "layer_mesh_update", "deform" to "rig_deform", "form" to "keyform_apply",
        "rig" to "rig_create_warp", "model_preset" to "model_apply_preset", "vertex_group" to "vertex_group_update",
        "appearance" to "object_edit_appearance", "structure" to "rig_edit_structure")

    fun register(id: String, description: String, schema: JsonObject, kind: WorkspaceOperationKind,
                 handler: suspend (JsonObject) -> WorkspaceOperationOutput) {
        val sampling = id in WorkspaceSamplingJobs.supported
        val motionObservation = id == WorkspaceObservationJobs.motion
        val physics = id in WorkspacePhysicsEdits.supported
        val raster = id in WorkspaceRasterCommands.supported
        val layer = id in WorkspaceLayerEdits.supported || id in WorkspaceAssetLayerEdits.supported || id == WorkspaceImageLayerCommands.OP
        val generation = id in WorkspaceGenerationCommands.supported
        val partition = id in WorkspacePartitionCommands.supported
        val regeneration = id in WorkspaceGenerationUpdate.supported
        val warp = id in WorkspaceWarpEdits.supported
        val asset = id in WorkspaceAssetSessions.supported
        val pose = id == "preview_pose"
        val swing = id in setOf("swing_preview", "swing_preview_commit")
        val background = id in WorkspaceSimulationEdits.supported || physics || raster || layer || generation || partition || regeneration || warp || asset || sampling || motionObservation || pose || swing
        registry.register(WorkspaceOperationDefinition(id,
            description + if (background) " Returns a process-owned job handle; use job_wait/job_get for the result, state and diagnostics. Disconnecting does not cancel execution." else "",
            schema, kind, jobBacked = background, workspaceBound = background || kind != WorkspaceOperationKind.QUERY,
            resultSchema = requireNotNull(if (background) WorkspaceJobResultSchemas.operationOutput(id) else WorkspaceAuthoringResultSchemas.forOperation(id)) { "No result contract: $id" },
            jobResultSchema = if (background) WorkspaceJobResultSchemas.result(id) else null)) { request, _ ->
            if (motionObservation) WorkspaceObservationJobs.start(workspace, jobs, request)
            else if (sampling) WorkspaceSamplingJobs.start(workspace, jobs, id, request)
            else if (!background) handler(request) else startWorkspaceOperationJob(workspace, jobs, id) {
                val completion = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[WorkspaceJobCompletion])
                kotlinx.coroutines.withContext(if (physics) WorkspacePhysicsJobExecution(id, completion)
                    else if (raster) WorkspaceRasterJobExecution(id, completion)
                    else if (layer) WorkspaceLayerJobExecution(id, completion)
                    else if (generation) WorkspaceGenerationJobExecution(id, completion)
                    else if (partition) WorkspacePartitionJobExecution(id, completion)
                    else if (regeneration) WorkspaceGenerationUpdateJobExecution(completion)
                    else if (warp) WorkspaceWarpJobExecution(completion)
                    else if (asset) WorkspaceAssetJobExecution(id, completion)
                    else if (pose) WorkspacePoseJobExecution(completion)
                    else if (swing) WorkspaceSwingJobExecution(completion)
                    else WorkspaceSimulationJobExecution(id, completion)) { handler(request) }
            }
        }
    }
    fun registerOperationVariants(name: String, description: String, schema: JsonObject, mutating: Boolean,
                 handler: suspend (JsonObject) -> WorkspaceOperationOutput) {
        val variants = schema["oneOf"]?.jsonArray?.map { it.jsonObject }
        if (variants == null) {
            register(directNames.getValue(name), description, schema, if (mutating) WorkspaceOperationKind.DOCUMENT else WorkspaceOperationKind.QUERY, handler)
        } else variants.forEach { branch ->
            val mode = branch.getValue("properties").jsonObject.getValue("mode").jsonObject.getValue("const").jsonPrimitive.content
            val id = "${if (name == "paint") "source_paint" else name}_$mode"
            val publicSchema = JsonObject(branch + ("properties" to JsonObject(branch.getValue("properties").jsonObject - "mode")) +
                ("required" to JsonArray(branch["required"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content != "mode" })))
            val query = !mutating || (name == "skeleton" && mode in setOf("get", "propose", "pose")) ||
                (name == "motion" && mode in setOf("list", "get", "sample")) ||
                (name == "path" && mode in setOf("get", "list", "preview")) || (name == "simulation" && mode in setOf("simulate", "compare"))
            val kind = when { query -> WorkspaceOperationKind.QUERY; name == "preview" -> WorkspaceOperationKind.SESSION; else -> WorkspaceOperationKind.DOCUMENT }
            register(id, "$description Operation: $mode.", publicSchema, kind) { input -> handler(JsonObject(input + ("mode" to JsonPrimitive(mode)))) }
        }
    }
    fun registerJsonOperation(name: String, description: String, fields: JsonObject, required: List<String> = emptyList(),
             mutating: Boolean = false, handler: suspend (JsonObject) -> JsonObject) {
        val grouped = fields.keys == setOf("request")
        val schema = if (grouped) fields.getValue("request").jsonObject else objectSchema(fields, required)
        registerOperationVariants(name, description, schema, mutating) { input ->
            WorkspaceOperationOutput(handler(if (grouped) buildJsonObject { put("request", input) } else input))
        }
    }

    register(WorkspaceImageLayerCommands.OP, "Import 1..128 PNG, lossless WebP, TIFF or BMP files as editable layers. Trims transparent borders, centres and scales down to fit the canvas without resampling the pixels. Each image gets its own mesh in the authored rig, which the generators never regenerate; parent_deformer_id attaches it to that existing deformer (default the model root). Move, scale or rotate it afterwards with layer_transform; undo removes it. Preserves existing object identities and commits the complete batch as one history edit. Each file is limited to 64 MiB and 16 megapixels; the complete batch is limited to 32 megapixels.",
        objectSchema(buildJsonObject { put("state", string()); put("paths", arraySchema(string(), 1, 128)); put("parent_deformer_id", string()) },
            listOf("state", "paths")), WorkspaceOperationKind.DOCUMENT) { request ->
        WorkspaceOperationOutput(workspace.importImages(request.text("state"), request.getValue("paths").jsonArray.map {
            java.nio.file.Path.of(it.jsonPrimitive.content)
        }, request["parent_deformer_id"]?.jsonPrimitive?.content).layerResult())
    }

    register(WorkspaceLayerTransform.OP, "Move, scale or rotate a layer as a whole - imported or from the PSD, at the root or under any deformer, bound or not. Give matrix [a, b, c, d, e, f] (x' = a·x + c·y + e, y' = b·x + d·y + f in canvas units, y down), or translate [x, y], scale [sx, sy] or one number, rotate (degrees, clockwise on screen) and pivot [x, y]; the transform composes with the layer's current one. Its pixels, texture and texture coordinates never change and nothing is regenerated: every mesh of the layer moves at rest, keyforms keep their offsets, and the layer remembers where its pixels now show for painting. One undoable history edit.",
        objectSchema(buildJsonObject {
            put("state", string()); put("layer_id", string())
            put("matrix", arraySchema(number(), 6, 6)); put("translate", arraySchema(number(), 2, 2))
            put("scale", buildJsonObject { putJsonArray("oneOf") { add(number()); add(arraySchema(number(), 2, 2)) } })
            put("rotate", number()); put("pivot", arraySchema(number(), 2, 2))
        }, listOf("state", "layer_id")), WorkspaceOperationKind.DOCUMENT) { request ->
        val id = request.text("layer_id")
        val result = workspace.applyDocumentEdits(request.text("state"), "Transformed layer $id",
            listOf(WorkspaceDocumentOperation(WorkspaceLayerTransform.OP, JsonObject(request - "state"))), MutationAuthor.AGENT)
        WorkspaceOperationOutput(buildJsonObject {
            put("state", requireNotNull(result.state)); put("history_node_id", result.historyNodeId); put("project_id", requireNotNull(result.projectId))
            put("layer_id", id)
            if (!result.applied) put("applied", false)
        })
    }

    register(WorkspaceRasterEdits.REBUILD_MESH, "Rebuild a layer's meshes from its pixels as they are now, at its mesh settings: after painting or replacing pixels beyond the mesh, or to apply changed density. Keyforms, paths, weights and glue migrate onto the new topology, and a layer moved as a whole keeps its place. Nothing else rebuilds a mesh except the paint and image-replacement rebuild_mesh option, mesh settings and regenerations. One undoable history edit.",
        objectSchema(buildJsonObject { put("state", string()); put("layer_id", string()) }, listOf("state", "layer_id")), WorkspaceOperationKind.DOCUMENT) { request ->
        val id = request.text("layer_id")
        val result = workspace.applyDocumentEdits(request.text("state"), "Rebuilt mesh of $id",
            listOf(WorkspaceDocumentOperation(WorkspaceRasterEdits.REBUILD_MESH, JsonObject(request - "state"))), MutationAuthor.AGENT)
        WorkspaceOperationOutput(buildJsonObject {
            put("state", requireNotNull(result.state)); put("history_node_id", result.historyNodeId); put("project_id", requireNotNull(result.projectId))
            put("layer_id", id)
            if (!result.applied) put("applied", false)
        })
    }

    register(WorkspaceGenerationUpdate.OP, "Regenerate the rig with this build's generators. A project whose journal has a regeneration checkpoint keeps what the generators made when it was written, even after an update; this merges what they make now onto the user's edits: what the user left follows the new output, the user's changes stay, and what does not carry over cleanly is reported in issues (also in workspace_inspect quality.regeneration). Commits one undoable history node, or none when the generators make the same rig (updated: false). Imported CMO3 models have no generated rig and are refused.",
        objectSchema(buildJsonObject { put("state", string()) }, listOf("state")), WorkspaceOperationKind.DOCUMENT) { request ->
        WorkspaceOperationOutput(workspace.updateGeneration(request.text("state")))
    }

    registerJsonOperation("inspect", "Read project context, find objects/layers/parameters, or inspect one kind:id's direct axes, channels and parent. No point arrays. Query and page before expanding.",
        buildJsonObject { put("scope", choices("project", "settings", "preview", "objects", "layers", "parameters", "physics", "swings", "paths", "simulations", "vertex_groups")); put("query", string()); put("target", string()); put("offset", integer(0)); put("limit", integer(1, 64)) }) { a ->
        workspace.captureQueries().inspect(a)
    }

    register("source_get_components", "Inspect the current splittable mesh islands of a source layer from one captured version. Components are ordered by source Y, then X; use the same order for names, sides and optional piece_ids in source_split_components. Authored or unsplittable layers return can_split=false.",
        objectSchema(buildJsonObject { put("layer_id", string()) }, listOf("layer_id")), WorkspaceOperationKind.QUERY) { request ->
        WorkspaceOperationOutput(workspace.captureQueries().sourceMeshComponents(request.text("layer_id")))
    }

    registerJsonOperation("layer", "Classify an existing source layer using the same fields as the UI Layers table. Omitted fields retain their current values. This rebuilds the generated rig and commits a recoverable history edit.",
        buildJsonObject {
            put("state", string()); put("layer_id", string())
            put("type", choices(*LayerType.entries.map { it.name.lowercase() }.toTypedArray()))
            put("role", choices(*SemanticTag.entries.map { it.name.lowercase() }.toTypedArray()))
            put("side", choices(*Side.entries.map { it.name.lowercase() }.toTypedArray()))
            put("parameter", string()); put("switch_id", integer(0))
        }, listOf("state", "layer_id"), true) { a ->
        require(listOf("type", "role", "side", "parameter", "switch_id").any { it in a }) { "Provide at least one classification field" }
        val id = a.text("layer_id")
        val result = workspace.classifyLayer(id, JsonObject(a - setOf("state", "layer_id")), a.text("state"))
        buildJsonObject {
            put("state", requireNotNull(result.state)); put("history_node_id", result.historyNodeId); put("project_id", requireNotNull(result.projectId))
            put("layer_id", id)
            if (!result.applied) put("applied", false)
        }
    }

    val upscaleSettings = objectSchema(buildJsonObject {
        put("scale", integer(1, 4))
        put("python", string()); put("nunifDirectory", string()); put("modelDirectory", string())
        put("tileSize", integer(64, 512)); put("noiseLevel", integer(-1, 3)); put("neuralAlpha", boolean())
    })
    // Each fill algorithm reads only its own group; omitted groups and fields keep their values.
    val fillParameterFields = objectSchema(buildJsonObject {
        put("poisson", objectSchema(buildJsonObject { put("edgeRatio", number()); put("gradation", number()); put("jitter", number()) }))
        for (group in listOf("quadtree", "fractal")) {
            put(group, objectSchema(buildJsonObject { put("edgeRatio", number()); put("gradation", number()); put("angle", number()) }))
        }
        put("paving", objectSchema(buildJsonObject { put("edgeRatio", number()); put("gradation", number()); put("maxRows", integer(0, 24)) }))
    })
    // How far the rig moves each part at the parameters' full values, in the units the model presets show; omitted values keep theirs.
    val rigTuningFields = objectSchema(buildJsonObject {
        io.github.psd2live.core.RigTuning.fields.forEach { f ->
            put(f.id, buildJsonObject { put("type", "number"); put("minimum", f.range.start); put("maximum", f.range.endInclusive)
                put("description", "${f.group.name.lowercase()} group${if (f.advanced) " (advanced)" else ""}, in ${f.unit.name.lowercase().replace('_', ' ')}, default ${f.default}") })
        }
    })
    val projectSettingFields = objectSchema(buildJsonObject {
        listOf("atlasSize", "meshSpacing", "texturePadding", "alphaThreshold").forEach { put(it, integer(0)) }
        listOf("meshOuterMargin", "meshEdgeWidth", "meshMaxEdgeDistance", "meshInteriorDensity",
            "headStrength", "bodyStrength", "mouthThickness").forEach { put(it, number()) }
        put("exportPixelsPerUnit", oneOf(listOf(number(), buildJsonObject { put("type", "null") })))
        listOf("meshSuppressBoundaryDiagonals", "meshOnly", "generateDeformers", "featureDisplacementEnabled", "mouthOutlineEnabled",
            "generatePhysics", "physicsFrontHair", "physicsBackHair", "physicsEyeJelly",
            "exportMotions", "motionBasic", "motionIdle", "motionBlink", "motionNod", "motionShake", "motionSkeleton",
            "exportCmo3", "exportMoc3", "exportJson", "exportHiddenParts", "exportHiddenDrawables",
            "exportGuideImageParts", "exportIncludePhysics", "exportIncludeUserData", "exportIncludeDisplayInfo").forEach { put(it, boolean()) }
        put("mouthShape", choices("flat", "smile", "w", "custom"))
        put("mouthColor", oneOf(listOf(integer(0, 0xFFFFFF), buildJsonObject { put("type", "null") })))
        put("mouthCurve", arraySchema(objectSchema(buildJsonObject {
            put("x", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
            put("y", buildJsonObject { put("type", "number"); put("minimum", -0.5); put("maximum", 0.5) })
        }, listOf("x", "y")), 7, 7))
        put("meshEdgeMode", choices("SINGLE", "DOUBLE", "TRIPLE"))
        put("meshFillAlgorithm", choices(*io.github.psd2live.core.MeshFillAlgorithm.entries.map { it.name }.toTypedArray()))
        put("meshUnits", buildJsonObject {
            put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("DOCUMENT"), JsonPrimitive("PIXELS"))))
            put("description", "Unit of every mesh length: DOCUMENT is pixels of the document scaled to a " +
                "${io.github.psd2live.core.MeshResolution.REFERENCE_SIDE} px long side (same mesh at any resolution); PIXELS is source pixels")
        })
        put("meshTrace", buildJsonObject {
            put("type", "string"); put("enum", JsonArray(io.github.psd2live.core.MeshTrace.entries.map { JsonPrimitive(it.name) }))
            put("description", "What mesh outlines are traced from: TEXTURE traces each layer's own pixels (alpha reduced by " +
                "maximum, up to ${io.github.psd2live.core.MeshResolution.MAX_DETAIL.toInt()}x finer than a mesh unit), so strokes thinner " +
                "than a canvas pixel stay inside the mesh; CANVAS traces the layer averaged to canvas resolution, as projects saved before it")
        })
        put("meshWrap", buildJsonObject {
            put("type", "number"); put("minimum", 0); put("maximum", io.github.psd2live.core.MeshWrap.range.endInclusive)
            put("description", "Wrap topology, in mesh units: gaps and notches of a layer's outline narrower than this are closed " +
                "before tracing, so fine protrusions such as lashes or strand tips share one envelope; 0 (default) traces the drawn edge. " +
                "Every painted pixel stays inside the mesh. Layers with a layer_mesh override keep their own wrap")
        })
        put("runtimeTarget", string()); put("textureUpscale", upscaleSettings)
        put("meshFillParameters", fillParameterFields)
        put("rigTuning", rigTuningFields)
    })
    registerJsonOperation("settings", "Update the project generation/export configuration used by the UI. Inspect scope=settings first. Rebuilds the model and commits history; textureUpscale, meshFillParameters and rigTuning fields are merged with the existing configuration; rigTuning sets how far the rig moves each part at the parameters' full values (head turn and face, eyes, brows, mouth, nose and ears, hair, body turn, open, lean, proportion, breath, depth).",
        buildJsonObject { put("state", string()); put("changes", projectSettingFields) }, listOf("state", "changes"), true) { a ->
        workspace.updateProjectSettings(a.text("state"), a.getValue("changes").jsonObject).compact()
    }
    registerJsonOperation("layer_mesh", "Set or reset a source layer's adaptive mesh settings using the same ranges as the UI layer mesh dialog. Omitted fields retain their current values; inspect scope=settings and inspect layers for context.",
        buildJsonObject {
            put("state", string()); put("layer_id", string()); put("reset", boolean())
            put("changes", objectSchema(buildJsonObject {
                put("outerMargin", number()); put("edgeMode", choices("SINGLE", "DOUBLE", "TRIPLE"))
                put("edgeWidth", number()); put("maxEdgeDistance", number()); put("interiorDensity", number())
                put("fillAlgorithm", choices(*io.github.psd2live.core.MeshFillAlgorithm.entries.map { it.name }.toTypedArray()))
                put("suppressBoundaryDiagonals", boolean())
                put("fillParameters", fillParameterFields)
                put("wrap", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", io.github.psd2live.core.MeshWrap.range.endInclusive)
                    put("description", "Wrap topology in mesh units: outline gaps narrower than this are closed; 0 traces the drawn edge") })
            }))
        }, listOf("state", "layer_id"), true) { a ->
        workspace.setLayerMeshSettings(a.text("state"), a.text("layer_id"), a["changes"]?.jsonObject,
            a["reset"]?.jsonPrimitive?.boolean ?: false).compact()
    }
    val previewValues = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    val previewLocks = buildJsonObject { put("type", "object"); put("additionalProperties", boolean()) }
    registerJsonOperation("preview", "Set authored preview parameter values and locks, or reset them, against the captured committed pose. Values are checked against parameter ranges; unspecified values survive independently of animation frames. This persists auxiliary session state without authoring model keyforms or history.",
        buildJsonObject { put("request", oneOf(listOf(
            variant("mode", "set", buildJsonObject { put("state", string()); put("values", previewValues); put("locks", previewLocks) }, listOf("state")),
            variant("mode", "reset", buildJsonObject { put("state", string()) }, listOf("state"))
        ))) }, listOf("request"), true) { a -> workspace.setPreviewSession(a.getValue("request").jsonObject) }
    register("preview_pose", "Author parameter values or an FK/IK bone target and optionally record automatic timeline keys atomically. Merge against the committed authored pose. auto_key preserves existing handles and records the previous authored value at zero for a new curve. This command is separate from preview_set/reset and returns the committed pose and keys.",
        objectSchema(buildJsonObject {
            put("state", string()); put("values", previewValues); put("locks", previewLocks)
            put("bone_id", string()); put("target", vector(2)); put("ik", boolean())
            put("ik_target", objectSchema(buildJsonObject { put("bone_id", string()); put("enabled", boolean()); put("point", oneOf(listOf(vector(2), buildJsonObject { put("type", "null") }))) }, listOf("bone_id", "point")))
            put("bone_ik", objectSchema(buildJsonObject { put("bone_id", string()); put("settings", objectSchema(buildJsonObject {
                put("chainLength", integer(1, 32)); put("iterations", integer(1, 256));
                put("tolerancePx", buildJsonObject { put("type", "number"); put("minimum", 0.001); put("maximum", 10) }); put("bendDirection", integer(-1, 1))
            })) }, listOf("bone_id", "settings")))
            put("auto_key", objectSchema(buildJsonObject { put("clip_id", string()); put("time", number()); put("snap", boolean()) }, listOf("clip_id", "time")))
        }, listOf("state")), WorkspaceOperationKind.SESSION) { input -> WorkspaceOperationOutput(workspace.authorPose(input)) }
    register("preview_playback", "Tracking mode accepts smooth (default false) for delayed head/body following with body Y; the algorithm is independent of clip selection. Control the process-owned motion clock or normalized pointer tracking. Frames stay transient and never replace authored preview values, locks or saved poses. Modes start, seek, pause, stop and tracking return the current bounded evaluated frame.",
        WorkspaceResultSchema.union(listOf(
            variant("mode", "start", buildJsonObject { put("state", string()); put("clip_id", string()); put("time", number()) }, listOf("state", "clip_id")),
            variant("mode", "seek", buildJsonObject { put("state", string()); put("clip_id", string()); put("time", number()) }, listOf("state", "time")),
            variant("mode", "pause", buildJsonObject { put("state", string()) }, listOf("state")),
            variant("mode", "stop", buildJsonObject { put("state", string()) }, listOf("state")),
            variant("mode", "animation", buildJsonObject { put("state", string()); put("enabled", boolean()) }, listOf("state", "enabled")),
            variant("mode", "trigger", buildJsonObject { put("state", string()); put("name", string()) }, listOf("state", "name")),
            variant("mode", "stop_motion", buildJsonObject { put("state", string()); put("name", string()) }, listOf("state")),
            variant("mode", "reset", buildJsonObject { put("state", string()) }, listOf("state")),
            variant("mode", "tracking", buildJsonObject { put("state", string()); put("enabled", boolean()); put("pointer", vector(2)); put("smooth", boolean()) }, listOf("state", "enabled"))
        )), WorkspaceOperationKind.SESSION) { input -> WorkspaceOperationOutput(workspace.controlPlayback(input)) }
    register("preview_physics", "Step the process-owned preview pendulums for dt seconds (0..1, default 1/60), on authored values or the supplied transient pose. Reports outputs and settling without changing authored values, dirty state or history. playing selects the playback clock; changing clocks starts from rest. reset restarts this workspace's pendulums.",
        objectSchema(buildJsonObject { put("state", string()); put("values", previewValues); put("dt", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) }); put("playing", boolean()); put("reset", boolean()) }, listOf("state")), WorkspaceOperationKind.SESSION) {
        WorkspaceOperationOutput(workspace.previewPhysics(it))
    }
    register("preview_playback_get", "Read the current process playback frame, clock and tracking state. Playback advances using the monotonic process clock even when no GUI frame is drawn. This does not advance the durable workspace state.",
        objectSchema(buildJsonObject {}), WorkspaceOperationKind.QUERY) { WorkspaceOperationOutput(workspace.playbackFrame()) }
    val paintPoint = vector(2)
    val paintColor = arraySchema(integer(0, 255), 4, 4)
    val paintCommon = buildJsonObject {
        put("state", string()); put("layer_id", string()); put("color", paintColor); put("opacity", number())
        put("rebuild_mesh", boolean())
    }
    registerJsonOperation("paint", "Commit one source-image paint gesture in canvas pixels with the shared raster engine. Brush, pencil, eraser, bucket and shapes use the UI algorithms. Pencil uses a hard tip without antialiasing. Defaults to retaining the mesh and authored bindings; first visible paint on a transparent layer creates its mesh and returns generated object handles in changed. rebuild_mesh=true migrates existing meshes, forms, paths, weights and glue to cover painted areas. Clear erases pixels while retaining the layer and its bindings even when rebuild_mesh is true. Source and mesh changes share one history node.",
        buildJsonObject { put("request", oneOf(listOf(
            variant("mode", "brush", JsonObject(paintCommon + buildJsonObject {
                put("points", arraySchema(paintPoint, 1, 512)); put("radius", number()); put("hardness", number())
            }), listOf("state", "layer_id", "points")),
            variant("mode", "pencil", JsonObject(paintCommon + buildJsonObject {
                put("points", arraySchema(paintPoint, 1, 512)); put("radius", number())
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
    registerJsonOperation("deform", "Edit whole ArtMesh/Warp surfaces at exact keys, atomically across changes. All points participate including empty cage regions. Units: fixed input bounds, normalized x-right/y-down; rotations in degrees. arc bends cross-sections along root→tip; root_pin is a fixed length fraction. Optional brush hardness gives a broad plateau. Unspecified directly bound axes are an error. This edits local shapes; parent motion is inherited.",
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
    registerJsonOperation("form", "Author key collections atomically. seed captures interpolated geometry without changing other keys. copy transfers selected channels (omit for all) from an explicit source key. set writes scalar/color channels or a rotation form, never mesh point arrays. Keys name only the destination object's axes, not the viewing pose.",
        buildJsonObject { put("state", string()); put("changes", arraySchema(oneOf(listOf(
            variant("op", "seed", formBase, listOf("target", "key")),
            variant("op", "copy", JsonObject(formBase + buildJsonObject { put("from", key); put("destination", string()); put("channels", arraySchema(string(), 1, 8)) }), listOf("target", "from", "key")),
            variant("op", "set", JsonObject(formBase + buildJsonObject { put("channels", channels); put("geometry", objectSchema(buildJsonObject {
                put("originX", number()); put("originY", number()); put("angle", number()); put("scale", number())
            })) }), listOf("target", "key")),
            variant("op", "delete", buildJsonObject { put("target", string()); put("parameter", string()); put("value", number()); put("channel", string()) }, listOf("target", "parameter"))
        )), 1, 128)) }, listOf("state", "changes"), true) { a -> workspace.authorRig(a.text("state"), a.getValue("changes").jsonArray, MutationAuthor.AGENT).compact() }

    registerJsonOperation("rig", "Create a fitted independent Warp for meshes sharing a parent. Existing keyforms and UVs migrate; the parent lattice constrains fit precision. Use deform directly when no independent motion layer is needed. Returned target is the new Warp.",
        buildJsonObject {
            put("state", string()); put("name", string())
            put("id", JsonObject(string() + ("description" to JsonPrimitive("Optional new Warp ID for references in later batch members; otherwise allocated once in the candidate."))))
            putJsonObject("targets") {
                put("type", "array"); put("minItems", 1); put("uniqueItems", true)
                put("items", buildJsonObject { put("type", "string"); put("pattern", "^mesh:.+$") })
            }
            for (field in listOf("rows", "columns")) put(field, JsonObject(integer(1, 32) + mapOf(
                "default" to JsonPrimitive(16), "description" to JsonPrimitive("Requested divisions; aligned to parent lattice knots. Inspect the created Warp for effective divisions."))))
            put("fit_local", JsonObject(boolean() + mapOf("default" to JsonPrimitive(true),
                "description" to JsonPrimitive("Crop to the grid and additive blend-shape envelope; false retains the full parent domain."))))
        }, listOf("state", "name", "targets"), true) { a ->
        workspace.createIndependentWarp(a, a.text("state")).warpResult()
    }

    fun registerCommandOperations(name: String, description: String, variants: Map<String, String>, mutating: Boolean) {
        variants.forEach { (mode, commandId) ->
            val command = commands.get(commandId)
            val properties = JsonObject(command.schema.properties.filterKeys { it != "task_id" && !(commandId == "asset_import_png" && it == "png_base64") })
            val required = command.schema.required.filter { it != "task_id" } +
                if (commandId == "asset_import_png") listOf("png_path") else emptyList()
            val id = when (commandId) {
                "asset_split_artwork" -> "source_split_polygon"
                "asset_split_components" -> "source_split_components"
                "asset_split_depth" -> "source_split_depth"
                else -> commandId
            }
            val kind = when {
                id == "project_save" || id.startsWith("asset_") && id !in setOf("asset_inspect", "asset_preview_composite") -> WorkspaceOperationKind.OUTPUT
                command.hints.readOnlyHint -> WorkspaceOperationKind.QUERY
                else -> WorkspaceOperationKind.DOCUMENT
            }
            register(id, command.description.ifBlank { "$description Operation: $mode." }, objectSchema(properties, required), kind) { input ->
                val result = commands.invoke(commandId, input)
                val value = result.data
                if ("historyNodeId" !in value) result else result.copy(data = buildJsonObject {
                    put("state", value.getValue("state")); put("history_node_id", value.getValue("historyNodeId")); put("project_id", value.getValue("project_id"))
                    value["revisionId"]?.let { put("revision", it) }
                    value["applied"]?.let { put("applied", it) }
                    listOf("affectedLayerIds", "affectedParameterIds", "affectedObjectIds").forEach { field ->
                        value[field]?.takeIf { it is JsonArray && it.isNotEmpty() }?.let { put(field, it) }
                    }
                    if (id == "physics_import") listOf("imported", "disabled", "missing_parameters", "fps").forEach { field ->
                        value[field]?.let { put(field, it) }
                    }
                })
            }
        }
    }
    registerCommandOperations("view", "Observe model poses with a fixed camera, source layers or coverage. poses returns one labeled sheet; shared parameters are overridden per tile. Reuse canvas rectangles across comparisons. Static sampling does not simulate physics. Inspect only relevant regions; images are not proof of unobserved poses.",
        mapOf("compare" to "view_compare_history", "motion" to "view_sample_motion", "poses" to "view_render_poses", "model" to "view_render_model", "layer" to "view_render_layer", "context" to "view_render_context", "coverage" to "view_check_coverage"), false)
    registerCommandOperations("parameter", "Create, update, or delete a parameter definition. kind=blend_shape creates a blend-shape parameter (default 0..1, neutral key at 0). A parameter alone produces no motion: form/deform author its object bindings and keys. Deleting collapses keyed axes at the prior default and removes blend bindings.",
        mapOf("create" to "parameter_create", "update" to "parameter_update", "delete" to "parameter_delete"), true)
    registerCommandOperations("asset", "Use local artwork from your host image generator. split partitions a source layer into polygon-inside/remainder (canvas pixels), before motion authoring; hidden artwork is not generated. For additions prepare a reference, import/register PNG, preview, add. Reference/view handles preserve placement. Generated art is not proof of model motion.",
        mapOf("split" to "asset_split_artwork", "components" to "asset_split_components", "depth" to "asset_split_depth", "reference" to "asset_prepare_reference", "import" to "asset_import_png", "register" to "asset_register", "preview" to "asset_preview_composite", "add" to "layer_add_from_asset", "place" to "layer_set_placement", "finalize" to "layer_finalize_placement", "inspect" to "asset_inspect", "reprocess" to "asset_reprocess", "remove" to "layer_soft_delete", "restore" to "layer_restore"), true)
    registerCommandOperations("swing", "Generate regenerating sway on Warps or meshes (wrapped in a tight Warp): kind=lateral swings the tip left/right, kind=vertical up/down (or motions=[...] for both), each on -1/0/1 keys per segment parameter, with a matching pendulum unless physics_enabled=false. parallel keeps the tip edge level so hair with several strands in one Warp sways side by side. Changing a swing recomputes its forms; delete with bake=true to keep them as ordinary keys. Verify with view poses at the parameter endpoints.",
        mapOf("put" to "swing_put", "delete" to "swing_delete"), true)
    register("swing_preview", "Create or edit an isolated swing audition on a captured workspace state. begin resolves existing swings or neutral defaults; update/kinds/segments/preset/physics/select/handle use the same candidates as GUI handles. play runs a transient clock, cancel retains a queryable terminal session. Draft rigs and playback values never enter save/query baselines. Use swing_preview_get/render to inspect; swing_preview_commit persists the captured draft once.",
        WorkspaceSwingSessionSchemas.control(), WorkspaceOperationKind.SESSION) { WorkspaceOperationOutput(workspace.controlSwingPreview(it).report) }
    register("swing_preview_commit", "Commit an audition draft with its captured state; stale drafts fail without overwriting another edit. Retains the committed session and terminal result for job queries and retries.",
        objectSchema(buildJsonObject { put("state", string()); put("session_id", string()) }, listOf("state", "session_id")), WorkspaceOperationKind.DOCUMENT) {
        WorkspaceOperationOutput(workspace.controlSwingPreview(JsonObject(it + ("mode" to JsonPrimitive("commit")))).report)
    }
    register("swing_preview_get", "Read the draft, handle positions and transient playback pose of a swing audition. Optional time samples the audition at a fixed time without advancing its clock. stale reports a changed committed workspace; cancelled and committed sessions remain queryable.",
        objectSchema(buildJsonObject { put("session_id", string()); put("time", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 3600) }) }, listOf("session_id")), WorkspaceOperationKind.QUERY) {
        WorkspaceOperationOutput(workspace.swingPreviewFrame(it.text("session_id"), it["time"]?.jsonPrimitive?.float).report)
    }
    register("swing_preview_render", "Render an isolated swing audition at its transient playback pose, with optional parameter overrides and a fixed canvas-pixel bounds rectangle. The committed model and saved authored pose stay unchanged.",
        objectSchema(buildJsonObject { put("session_id", string()); put("parameters", previewValues); put("bounds", vector(4)); put("size", integer(128, 2048)) }, listOf("session_id", "bounds")), WorkspaceOperationKind.QUERY) {
        val bounds = it.getValue("bounds").jsonArray.map { value -> value.jsonPrimitive.float }
        val view = workspace.renderSwingPreview(it.text("session_id"), io.github.psd2live.project.WorkspaceModelViewRequest(
            parameters = it["parameters"]?.jsonObject.orEmpty().mapValues { value -> value.value.jsonPrimitive.float },
            frame = io.github.psd2live.project.WorkspaceViewFrame.CanvasRect(io.github.psd2live.core.Bounds(bounds[0], bounds[1], bounds[2], bounds[3])),
            output = io.github.psd2live.project.WorkspaceViewOutputSpec(it["size"]?.jsonPrimitive?.int ?: 512)))
        WorkspaceOperationOutput(view.toJson(), listOf(view.png))
    }
    registerCommandOperations("physics", "Author Cubism pendulums: put creates or patches a group by ID (inputs, outputs on pendulum vertices, 1..16 pendulums, normalization; enabled=false turns any group off, generated ones included). Groups with empty inputs or outputs can be saved and edited; they remain inactive with a diagnostic until configured. delete removes a user group or reverts a replaced generated one, simulate steps inputs and reports each output's peak, final value and settling, fit scales outputs to a standard sway, config sets evaluation order and fps, import reads a physics3.json. inspect scope=physics lists every group in evaluation order with origin and status. Author the output parameters' forms first; static view poses do not show settling.",
        mapOf("put" to "physics_put", "delete" to "physics_delete", "simulate" to "physics_simulate", "fit" to "physics_fit",
            "config" to "physics_config", "import" to "physics_import"), true)
    val simulationFields = buildJsonObject {
        put("mode", choices("put", "delete", "simulate", "bake", "clear_bake")); put("state", string()); put("id", string()); put("name", string())
        put("kind", choices("cloth", "hair")); put("targets", arraySchema(string(), 1, 64)); put("enabled", boolean())
        put("material", objectSchema(buildJsonObject {
            put("mass", number()); put("stretch", number()); put("bend", number()); put("damping", number()); put("goal", number()); put("slack", number())
            put("area", number()); put("anisotropy", number())
        }))
        put("material_preset", choices(*SimMaterialPreset.entries.map { it.jsonName }.toTypedArray()))
        put("groups", buildJsonObject { put("type", "object"); put("additionalProperties", string()) })
        put("glue_roles", buildJsonObject { put("type", "object"); put("additionalProperties", choices("ignore", "pin", "constraint")) })
        put("inputs", arraySchema(objectSchema(buildJsonObject {
            put("parameter", string()); put("weight", number()); put("type", choices("x", "angle")); put("reflect", boolean())
        }, listOf("parameter")), 0, 16))
        put("input_ranges", buildJsonObject { put("type", "object"); put("additionalProperties", arraySchema(number(), 2, 2)) })
        put("vertical", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("null")))) })
        put("outputs", buildJsonObject { put("type", "object"); put("additionalProperties", objectSchema(buildJsonObject {
            put("id", string()); put("range", buildJsonObject { put("type", "number"); put("minimum", 1.0); put("maximum", 100.0) })
            put("gain", buildJsonObject { put("type", "number"); put("minimum", 0.0); put("maximum", 3.0) })
        }, emptyList())) })
        put("modes", integer(1, 3)); put("keys", buildJsonObject { put("type", "integer"); put("enum", JsonArray(listOf(3, 5, 7, 9).map(::JsonPrimitive))) }); put("static_inputs", arraySchema(string(), 0, 4))
        put("blend_shapes", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("null")))) }); put("auto_bake", boolean())
        put("exaggeration", buildJsonObject { put("type", "number"); put("minimum", 1.0); put("maximum", 2.0) })
        put("output_names", buildJsonObject { put("type", "object"); put("additionalProperties", string()) })
        put("force_inputs", arraySchema(string(), 0, 16))
        put("training_clips", buildJsonObject { put("type", "object"); put("additionalProperties", buildJsonObject {
            put("type", "number"); put("minimum", io.github.psd2live.core.sim.RigSimEdit.TRAINING_WEIGHTS.start.toDouble()); put("maximum", io.github.psd2live.core.sim.RigSimEdit.TRAINING_WEIGHTS.endInclusive.toDouble()) }) })
        put("obstacles", arraySchema(objectSchema(buildJsonObject {
            put("mesh", string()); put("a", integer(0, 65535)); put("b", integer(0, 65535))
            put("radius", buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0) })
            put("radius_b", buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0) })
            put("friction", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
        }, listOf("mesh", "a", "radius")), 0, 32))
        put("hold", number()); put("release", number()); put("wind", vector(2))
    }
    registerJsonOperation("simulation", "2D cloth and hair simulation on ArtMeshes. It runs in the editor only; bake is what exports. put creates or patches a body by id: targets are mesh ids simulated together, material values are 0..1 (stretch near 1 keeps length; bend = how sharply it may curve; area = how firmly each triangle keeps its area, low lets it bunch up; anisotropy = how much softer it is across the grain, which runs away from the pins; goal = spring back to the drawn shape; slack = long-range give) except mass and damping (1/s); material_preset (cloth: cotton, silk, chiffon, wool, denim, leather, elastic; hair: hair, fine_hair, thick_hair, bangs) starts from a named material and material fields given with it override its values. Mass follows each vertex's share of the mesh area, so a finer mesh is the same material. " +
        "Pins come from the PIN vertex group; a glue is never a pin unless glue_roles sets its key (meshA|meshB from inspect scope=simulations) to pin (follow the other mesh) or constraint (both sides simulated). groups names the vertex group to use per kind. " +
        "inputs are the parameters that shake it, always as listed: a new body given none starts with the head and body angles, nodding and the body rising, sinking and leaning (those the model has), and an empty list means nothing shakes it and it cannot bake; cloth takes only ParamBodyAngleY of the vertical ones. input_ranges sets per input parameter the span [min, max] the bake trains over (default its whole range): the mode parameters reach their ends at the hardest motion within it, so a narrower span swings fully there and saturates past it. output_names renames the bake's parameters and pendulums by ID (inspect lists them), applied without baking again; leave one out to keep the name after the body. simulate runs it (settle, each input held at max for hold s then released, optional wind [x, y] px/s² with y up) and reports peaks, rest drift, stretch and setup notes. " +
        "bake (a few seconds) reduces it to what Cubism plays: static_inputs (default none) get exact corrections on their own axes, and the remaining motion becomes modes (1..3, default 2: the swing, then the bend and compression it leaves, each fitted over what the ones above leave) parameters ParamSim<id>_<k> (-30..30) with keys (3..9) each, driven by one pendulum PhysicsSim_<id> (a later mode may get a pendulum of its own, PhysicsSim_<id>_<k>, when that follows unseen motion better). " +
        "It first reads off the rig how far each input moves the body: one that moves it less than 1 px is left out (force_inputs keeps listed inputs in anyway), and one that moves it more up and down than sideways goes to one more parameter ParamSim<id>_Y driven by a pendulum of its own PhysicsSim_<id>_y, when they move it 2 px or more (vertical true always makes it, false leaves those inputs out, null is that default). Each input is then driven on its own - a step to each end of its training span and back, and a 0.25-3 Hz sweep - so each gets the weight that moves the body as it moves it; training_clips adds the model's own motions (clip ids or built-in names, each weighted 0.05..1) to learn from as well. " +
        "The parameters span the hardest of that without reaching their ends. It reports what it read of each input (inputs: motion_px, rigid, group, or dropped) and, on motion the fit never saw, what a viewer sees (visual: amp = the baked swing against the simulated one, lag_ms, settle and overshoot after the inputs stop, jitter against the simulation, clipped, shorten = how much shorter the strands get, static_px), then R², the 95th-percentile error in px, how much of their range the modes use, the share of frames at an end and the jerk against the simulation (1 = as smooth). compare plays named motions (clip ids or built-in names, Idle, Nod, Shake) through the baked model at gain 1 and the simulation and reports the same visual measures for each; read-only. " +
        "obstacles (replaces the list) are bodies the particles keep out of, riding a mesh the simulation does not move as the rig deforms it: a circle on vertex a of mesh, or with b a capsule from a to b (an arm, a leg, the torso); radius runs to radius_b at b, friction 0..1 takes that share of a touching particle's slide. They shape the simulation and its bake, and export to the runtime targets that play live simulations (p2lrt, web). " +
        "exaggeration (1..2, default 1.3) scales the mode swings as they are written back, without baking again. outputs, keyed by a mode parameter ID the bake gives (ParamSim<id>_<k>, ParamSim<id>_Y), writes that mode under another id, over ±range (1..100, default 30; keys and the pendulum output scale stretch with it) and swung gain times as far (0..3, default the exaggeration), all without baking again; the map replaces the previous one. Physics panel overrides of a simulation pendulum keep what they changed when it is baked again or its outputs change, and go when it is no longer written. blend_shapes true writes the modes as blend shapes where the Cubism target has them (their keys add to the keyforms instead of multiplying them), false as keyform axes; null (default) uses blend shapes only past 64 keyforms on a target. " +
        "With auto_bake (default true) every put bakes again in the same step and reports the bake or bake_error; otherwise, or after changing meshes or weights, the bake stays in place but stale (inspect shows it): bake again. clear_bake removes it. delete removes the simulation.",
        buildJsonObject { put("request", oneOf(listOf(
            variant("mode", "put", JsonObject(simulationFields - "mode" - "hold" - "release" - "wind"), listOf("state", "id")),
            variant("mode", "delete", buildJsonObject { put("state", string()); put("id", string()) }, listOf("state", "id")),
            variant("mode", "bake", buildJsonObject { put("state", string()); put("id", string()) }, listOf("state", "id")),
            variant("mode", "clear_bake", buildJsonObject { put("state", string()); put("id", string()) }, listOf("state", "id")),
            variant("mode", "compare", buildJsonObject { put("id", string()); put("motions", arraySchema(string(), 1, 8)) }, listOf("id", "motions")),
            variant("mode", "simulate", buildJsonObject {
                put("id", string()); put("wind", vector(2))
                for (field in listOf("hold", "release")) put(field, buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 20) })
            }, listOf("id"))
        ))) }, listOf("request"), true) { wrapped ->
        val a = wrapped.getValue("request").jsonObject
        when (a.text("mode")) {
            "put" -> workspace.putSimulation(JsonObject(a - "mode" - "state" - "hold" - "release" - "wind" - "bake"), a.text("state"), null)
                .let { (result, bake) -> JsonObject(result.compact() + bake) }
            "bake" -> workspace.bakeSimulation(a.text("id"), a.text("state")).let { (result, summary) -> JsonObject(result.compact() + summary) }
            "clear_bake" -> workspace.putSimulationBake(a.text("id"), null, a.text("state")).compact()
            "delete" -> workspace.deleteSimulation(a.text("id"), a.text("state")).compact()
            "compare" -> workspace.compareSimulation(a.text("id"), a.getValue("motions").jsonArray.map { it.jsonPrimitive.content })
            else -> workspace.reportSimulation(a.text("id"), a["hold"]?.jsonPrimitive?.float ?: 0.5f, a["release"]?.jsonPrimitive?.float ?: 1.5f,
                a["wind"]?.jsonArray?.let { it[0].jsonPrimitive.float to it[1].jsonPrimitive.float })
        }
    }
    registerJsonOperation("model_preset", "Model presets, each one undoable step that ends baked. front_hair / back_hair: one hair simulation (preset_front_hair / preset_back_hair) over every hair mesh of that kind, " +
        "each strand pinned at its own root; the legacy hair sway (ParamHairFront/Back, its warp and pendulum) is removed for that hair. classic_front_hair / classic_back_hair undo that: the preset simulation goes and the legacy sway returns. remove_clothing removes every clothing preset simulation. " +
        "clothing: reads topwear, bottomwear (skirt or trousers: name first, then a gap between legs from the crotch to the hem), neckwear, handwear (sleeves) and legwear, " +
        "and simulates each only where its art hangs loose: cloth standing out of the body column (ruffles, bows, flared hems, coat flaps), a top below the waist, a skirt below the hips once wider than them, neckwear below the knot, " +
        "and on sleeves and legwear only what is much thinner than the limb (cuff ruffles, ribbons); skin is never loose. Garments worn tight get no simulation and leave a clothing preset simulation they were in. " +
        "Writes preset_pin (tight held, loose free), preset_mass and preset_wind (growing with looseness); " +
        "the simulations are preset_top, preset_skirt, preset_trousers, preset_neckwear, preset_sleeves and preset_legwear. auto_weights recomputes those groups for the given layers, or every simulated mesh, and bakes the simulations they feed. " +
        "layers narrows a preset to those layer ids; omitted applies it to every recognized part. A mesh in a simulation of the user's own is refused. Reports the garments read and each bake.",
        buildJsonObject {
            put("preset", choices("front_hair", "back_hair", "clothing", "auto_weights", "classic_front_hair", "classic_back_hair", "remove_clothing")); put("state", string())
            put("layers", arraySchema(string(), 0, 256))
            put("sway", buildJsonObject { put("type", "boolean"); put("description", "For classic_front_hair/classic_back_hair: enable the restored legacy sway (default true).") })
        }, listOf("preset", "state"), true) { a ->
        when (val preset = a.text("preset")) {
            "classic_front_hair", "classic_back_hair" -> workspace.restoreClassicHair(preset == "classic_front_hair", a.text("state"), sway = a["sway"]?.jsonPrimitive?.boolean ?: true).compact()
            "remove_clothing" -> workspace.removeClothingPresets(a.text("state")).compact()
            else -> workspace.applyModelPreset(io.github.psd2live.core.sim.ModelPresets.Preset.parse(preset),
                a["layers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(), a.text("state"))
                .let { (result, report) -> JsonObject(result.compact() + report) }
        }
    }
    registerJsonOperation("vertex_group", "Editor-only per-vertex 0..1 weights on an ArtMesh that the simulation reads (never exported). kind: pin, stiffness, mass, damping, wind, goal. " +
        "rule: fill (every vertex value), outline (outline vertices value), gradient (along from->to canvas px, start at from to end at to), glue (vertices glued to another mesh take their glue weight x value), region (inside rect [x0, y0, x1, y1] canvas px). " +
        "mode combines with the existing group: replace (default), max, min, add, subtract. delete=true removes the group. inspect scope=vertex_groups lists groups.",
        buildJsonObject {
            put("state", string()); put("target", string()); put("name", string()); put("kind", choices("pin", "stiffness", "mass", "damping", "wind", "goal"))
            put("rule", choices("fill", "outline", "gradient", "glue", "region")); put("value", number())
            put("from", vector(2)); put("to", vector(2)); put("start", number()); put("end", number()); put("rect", vector(4))
            put("mode", choices("replace", "max", "min", "add", "subtract")); put("delete", boolean())
        }, listOf("state", "target", "name"), true) { a ->
        val target = a.text("target")
        val command = if (a["delete"]?.jsonPrimitive?.booleanOrNull == true) buildJsonObject {
            put("op", "vertex_group_delete"); put("target", target); put("name", a.text("name"))
        } else {
            require("rule" in a) { "rule is required unless delete=true" }
            JsonObject(a - "state" - "delete" + ("op" to JsonPrimitive("vertex_group_rule")))
        }
        workspace.authorRig(a.text("state"), JsonArray(listOf(command)), MutationAuthor.AGENT).compact()
    }
    registerJsonOperation("appearance", "Rename, show/hide or reorganize objects in one ordered edit. For an animated switch use form opacity keys instead of static visibility. Local reparenting changes inherited motion.",
        buildJsonObject { put("state", string()); put("edits", objectEditSchema().properties.getValue("edits")) }, listOf("state", "edits"), true) { a ->
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
        put("selectable", boolean()); put("quad", boolean()); put("user_data", string())
        put("partner_id", string()); put("linked", boolean()); put("open", boolean())
        put("label_type", string()); put("color", integer(Int.MIN_VALUE))
    }, listOf("action", "kind", "id"))
    registerJsonOperation("structure", "Edit static object properties, delete deformers, assign a deformer Part, or organize parameter folders and 2D links. Edits are ordered and use the same persistent structure journal as the UI. Parameter definitions use parameter instead.",
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
            put("distance", number()); put("replace", boolean())
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
    registerJsonOperation("canvas", "Use the canvas editor's persisted algorithms to create a Warp or Rotation, pair Glue vertices where both meshes rest (the default pose, as Cubism's glue), or edit mesh topology. Glue requires two different art-mesh ids (mesh_a and mesh_b) and fails when a mesh is missing, the ids match, or no vertices fall inside distance. Set replace to update an existing glue on that pair. Topology indices are from the current mesh and require fresh state. Creation accepts an optional stable ID; otherwise one is generated.",
        buildJsonObject { put("request", oneOf(canvasBranches)) }, listOf("request"), true) { a ->
        val input = a.getValue("request").jsonObject
        validateOperationSchema(input, oneOf(canvasBranches))
        val mode = input.text("mode")
        val command = WorkspaceCanvasCommands.create(workspace.currentPuppet() ?: error("No model is loaded"), mode, input)
        val writtenId = command.getValue("id").jsonPrimitive.content
        val result = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
        buildJsonObject {
            put("state", requireNotNull(result.state)); put("history_node_id", result.historyNodeId); put("project_id", requireNotNull(result.projectId)); put("id", writtenId)
            if (!result.applied) put("applied", false)
            result.geometryDiagnostics?.let { put("geometry_diagnostics", it) }
            if (mode == "glue") {
                put("mesh_a", command.getValue("mesh_a").jsonPrimitive.content)
                put("mesh_b", command.getValue("mesh_b").jsonPrimitive.content)
                val glue = workspace.currentPuppet()?.glues?.firstOrNull { it.id == writtenId }
                put("pair_count", glue?.pairs?.size ?: 0)
            }
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
    registerOperationVariants("path", "Inspect, create, delete, preview or deform ArtMeshes with Deform Paths. Points use mesh local coordinates and deform bakes MLS displacement into an exact keyform.", oneOf(pathBranches), true) { input ->
            val mode = input.text("mode")
            val puppet = workspace.currentPuppet() ?: error("No model is loaded")
            if (mode == "preview") {
                val preview = WorkspacePathEdits.preview(puppet, input)
                val base64 = preview["previewImage"]?.jsonPrimitive?.contentOrNull
                WorkspaceOperationOutput(JsonObject(preview - "previewImage"), listOfNotNull(base64?.let { java.util.Base64.getDecoder().decode(it) }))
            } else {
                val result = when (mode) {
                    "get", "list" -> WorkspacePathEdits.inspect(puppet, input)
                    "put" -> {
                        val (pathId, command) = WorkspacePathEdits.createPutCommand(puppet, input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", requireNotNull(res.state)); put("history_node_id", res.historyNodeId); put("project_id", requireNotNull(res.projectId))
                            put("path_id", pathId)
                            put("target", input.text("target"))
                        }
                    }
                    "delete" -> {
                        val (pathId, command) = WorkspacePathEdits.createDeleteCommand(input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", requireNotNull(res.state)); put("history_node_id", res.historyNodeId); put("project_id", requireNotNull(res.projectId))
                            put("deleted", pathId)
                        }
                    }
                    "deform" -> {
                        val command = WorkspacePathEdits.createDeformCommand(input)
                        val res = workspace.authorRig(input.text("state"), buildJsonArray { add(command) }, MutationAuthor.AGENT)
                        buildJsonObject {
                            put("state", requireNotNull(res.state)); put("history_node_id", res.historyNodeId); put("project_id", requireNotNull(res.projectId))
                            put("target", input.text("target"))
                            put("key", input.getValue("key"))
                            if (res.affectedObjectIds.isNotEmpty()) put("changed", JsonArray(res.affectedObjectIds.map(::JsonPrimitive)))
                            res.geometryDiagnostics?.let { put("geometry_diagnostics", it) }
                        }
                    }
                    else -> error("Unknown path mode: $mode")
                }
                WorkspaceOperationOutput(result)
            }
    }

    fun skeletonMap(value: JsonObject) = buildJsonObject {
        put("type", "object"); put("additionalProperties", value)
    }
    val ikSettings = objectSchema(buildJsonObject {
        put("chainLength", integer(1, 32)); put("iterations", integer(1, 256))
        put("tolerancePx", buildJsonObject { put("type", "number"); put("minimum", 0.001); put("maximum", 10) })
        put("bendDirection", integer(-1, 1))
    })
    val boneFields = buildJsonObject {
        put("id", string()); put("name", string()); put("parent", string())
        put("role", choices(*io.github.psd2live.core.BoneRole.entries.map { it.name }.toTypedArray()))
        put("side", choices(*Side.entries.map { it.name }.toTypedArray()))
        put("head", vector(2)); put("tail", vector(2))
        put("drawables", arraySchema(string(), 0, 256)); put("chainIndex", integer(0))
        put("direction", number()); put("minAngle", number()); put("maxAngle", number())
        put("blendWidth", number())
        put("connected", boolean()); put("parameterOverride", string()); put("mirror", string())
        put("ik", ikSettings)
    }
    val skeletonBranches = listOf(
        variant("mode", "get", buildJsonObject {}, emptyList()),
        variant("mode", "propose", buildJsonObject {}, emptyList()),
        variant("mode", "auto", buildJsonObject { put("state", string()) }, listOf("state")),
        variant("mode", "put", buildJsonObject {
            put("state", string()); put("spec", objectSchema(buildJsonObject {
                put("version", integer(1, 10)); put("enabled", boolean())
                put("symmetryAxisX", number())
                put("savedPoses", skeletonMap(skeletonMap(number())))
                put("ikTargets", skeletonMap(objectSchema(buildJsonObject {
                    put("x", number()); put("y", number()); put("enabled", boolean())
                }, listOf("x", "y"))))
                put("manualWeights", skeletonMap(objectSchema(buildJsonObject {
                    put("positions", arraySchema(number(), 0, Int.MAX_VALUE))
                    put("triangles", arraySchema(integer(0), 0, Int.MAX_VALUE))
                    put("weights", arraySchema(skeletonMap(number()), 0, Int.MAX_VALUE))
                }, listOf("positions", "triangles", "weights"))))
                put("sampling", objectSchema(buildJsonObject {
                    put("tolerancePx", buildJsonObject { put("type", "number"); put("minimum", 0.25); put("maximum", 4.0) })
                    put("minimumStepDegrees", buildJsonObject { put("type", "number"); put("minimum", 2.5); put("maximum", 20.0) })
                    put("maxMeshKeyforms", integer(100, 1200))
                    put("jointMeshSegments", buildJsonObject { put("type", "integer"); put("enum", JsonArray((4..32 step 2).map(::JsonPrimitive))) })
                }))
                put("bones", arraySchema(objectSchema(boneFields,
                    listOf("id", "role", "head", "tail")), 0, 128))
            }, listOf("enabled", "bones")))
        }, listOf("state", "spec")),
        variant("mode", "enable", buildJsonObject { put("state", string()); put("enabled", boolean()) }, listOf("state", "enabled")),
        variant("mode", "bone", buildJsonObject { put("state", string()); put("bone", objectSchema(boneFields, listOf("id"))) }, listOf("state", "bone")),
        variant("mode", "move", buildJsonObject { put("state", string()); put("bone_id", string()); put("end", choices("head", "tail")); put("point", vector(2)) },
            listOf("state", "bone_id", "end", "point")),
        variant("mode", "bind", buildJsonObject { put("state", string()); put("drawable_id", string()); put("bone_id", string()) }, listOf("state", "drawable_id")),
        variant("mode", "remove", buildJsonObject { put("state", string()); put("bone_id", string()) }, listOf("state", "bone_id")),
        variant("mode", "delete", buildJsonObject { put("state", string()) }, listOf("state")),
        variant("mode", "pose", buildJsonObject { put("bone_id", string()); put("target", vector(2)); put("ik", boolean()) }, listOf("bone_id", "target")),
    )
    registerJsonOperation("skeleton", "Read, infer and edit the authored skeleton before it is baked to Cubism. put replaces the complete armature; bone upserts one bone (existing fields are retained); move keeps connected joints together; bind assigns a drawable to one bone or unbinds when bone_id is omitted; delete removes the whole armature so auto or put can create a new one; pose solves FK/IK into parameter values without changing history. Apply returned pose values with preview or use them as motion keys.",
        buildJsonObject { put("request", oneOf(skeletonBranches)) }, listOf("request"), true) { a ->
        val input = a.getValue("request").jsonObject
        validateOperationSchema(input, oneOf(skeletonBranches))
        val queries = if (input.text("mode") in setOf("get", "propose", "pose")) workspace.captureQueries() else null
        when (input.text("mode")) {
            "get" -> buildJsonObject { put("state", requireNotNull(queries).snapshot().state); requireNotNull(queries).skeletonSpec()?.let { put("spec", it.toJson()) } }
            "propose" -> buildJsonObject { put("state", requireNotNull(queries).snapshot().state); put("spec", requireNotNull(queries).proposeSkeleton().toJson()) }
            "pose" -> requireNotNull(queries).solveSkeletonPose(input)
            else -> workspace.editSkeleton(input.text("state"), input).compact()
        }
    }

    val motionHandle = vector(2)
    val motionKey = objectSchema(buildJsonObject {
        put("time", number()); put("value", number())
        put("interpolation", choices(*io.github.psd2live.core.MotionInterpolation.entries.map { it.name }.toTypedArray()))
        put("out", motionHandle); put("in", motionHandle)
    }, listOf("time", "value"))
    val motionClip = objectSchema(buildJsonObject {
        put("id", string()); put("name", string()); put("builtin", string())
        put("loop", boolean()); put("duration", number()); put("fps", number())
        put("fade_in", number()); put("fade_out", number()); put("enabled", boolean())
        put("curves", arraySchema(objectSchema(buildJsonObject {
            put("parameter", string()); put("keys", arraySchema(motionKey, 1, 4096))
        }, listOf("parameter", "keys")), 0, 256))
    }, listOf("id", "name"))
    val motionSelection = arraySchema(objectSchema(buildJsonObject { put("parameter", string()); put("time", number()) }, listOf("parameter", "time")), 1, 4096)
    val copiedKeys = arraySchema(objectSchema(buildJsonObject { put("parameter", string()); put("key", motionKey) }, listOf("parameter", "key")), 1, 4096)
    val motionBranches = listOf(
        variant("mode", "list", buildJsonObject {}, emptyList()),
        variant("mode", "get", buildJsonObject { put("id", string()) }, listOf("id")),
        variant("mode", "sample", buildJsonObject { put("id", string()); put("time", number()); put("loop", boolean()) }, listOf("id", "time")),
        variant("mode", "put", buildJsonObject { put("state", string()); put("clip", motionClip) }, listOf("state", "clip")),
        variant("mode", "create", buildJsonObject { put("state", string()); put("id", string()); put("name", string()); put("from_builtin", choices(*MotionClips.BUILTIN_NAMES.toTypedArray())) }, listOf("state")),
        variant("mode", "duplicate", buildJsonObject { put("state", string()); put("id", string()); put("new_id", string()) }, listOf("state", "id")),
        variant("mode", "rename", buildJsonObject { put("state", string()); put("id", string()); put("name", string()) }, listOf("state", "id", "name")),
        variant("mode", "properties", buildJsonObject { put("state", string()); put("id", string()); put("loop", boolean()); put("duration", number()); put("fps", number()); put("fade_in", number()); put("fade_out", number()); put("enabled", boolean()) }, listOf("state", "id")),
        variant("mode", "delete", buildJsonObject { put("state", string()); put("id", string()) }, listOf("state", "id")),
        variant("mode", "seed_builtin", buildJsonObject { put("state", string()); put("builtin", choices(*MotionClips.BUILTIN_NAMES.toTypedArray())); put("id", string()) }, listOf("state", "builtin")),
        variant("mode", "set_key", buildJsonObject { put("state", string()); put("id", string()); put("parameter", string()); put("key", motionKey) }, listOf("state", "id", "parameter", "key")),
        variant("mode", "delete_key", buildJsonObject { put("state", string()); put("id", string()); put("parameter", string()); put("time", number()) }, listOf("state", "id", "parameter", "time")),
        variant("mode", "remove_curve", buildJsonObject { put("state", string()); put("id", string()); put("parameter", string()) }, listOf("state", "id", "parameter")),
        variant("mode", "pose", buildJsonObject { put("state", string()); put("id", string()); put("time", number()); put("values", previewValues) }, listOf("state", "id", "time", "values")),
        variant("mode", "move_keys", buildJsonObject { put("state", string()); put("id", string()); put("selection", motionSelection); put("dt", number()); put("dv", number()); put("normalized", boolean()) }, listOf("state", "id", "selection", "dt")),
        variant("mode", "delete_keys", buildJsonObject { put("state", string()); put("id", string()); put("selection", motionSelection) }, listOf("state", "id", "selection")),
        variant("mode", "paste_keys", buildJsonObject { put("state", string()); put("id", string()); put("time", number()); put("keys", copiedKeys) }, listOf("state", "id", "time", "keys")),
        variant("mode", "replace_keys", buildJsonObject { put("state", string()); put("id", string()); put("keys", arraySchema(objectSchema(buildJsonObject { put("parameter", string()); put("from_time", number()); put("key", motionKey) }, listOf("parameter", "from_time", "key")), 1, 4096)) }, listOf("state", "id", "keys")),
        variant("mode", "preset", buildJsonObject { put("state", string()); put("builtin", choices(*MotionClips.BUILTIN_NAMES.toTypedArray())); put("action", choices("update", "reset", "delete", "restore")); put("values", previewValues); put("disabled", boolean()) }, listOf("state", "builtin", "action")),
    )
    registerJsonOperation("motion", "Read and edit persistent motion clips and parameter timelines. create makes a named blank clip or copies a generated motion with its current knobs; duplicate copies a clip under a unique name; rename resolves name collisions; properties updates playback/export fields, dropping keys after a shortened duration. sample returns exact interpolated values without authoring a pose. pose keys a parameter map; move_keys/delete_keys/paste_keys/replace_keys preserve timeline interpolation and handles. preset edits generated-motion knobs and lifecycle. preview_pose applies FK/IK and records optional auto keys atomically. Export writes authored clips to motion3.json.",
        buildJsonObject { put("request", oneOf(motionBranches)) }, listOf("request"), true) { a ->
        val input = a.getValue("request").jsonObject
        validateOperationSchema(input, oneOf(motionBranches))
        val queries = if (input.text("mode") in setOf("list", "get", "sample")) workspace.captureQueries() else null
        when (input.text("mode")) {
            "list" -> buildJsonObject {
                put("state", requireNotNull(queries).snapshot().state)
                putJsonArray("clips") { requireNotNull(queries).motionClips().forEach { add(buildJsonObject {
                    put("id", it.id); put("name", it.name); it.builtin?.let { builtin -> put("builtin", builtin) }
                    put("duration", it.duration); put("enabled", it.enabled); put("curves", it.curves.size)
                }) } }
                putJsonArray("builtins") { MotionClips.BUILTIN_NAMES.forEach { add(JsonPrimitive(it)) } }
            }
            "get" -> buildJsonObject {
                put("state", requireNotNull(queries).snapshot().state)
                val id = input.text("id")
                val clip = requireNotNull(queries).motionClips().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Motion not found: $id")
                put("clip", MotionClips.toJson(clip))
            }
            "sample" -> buildJsonObject {
                put("state", requireNotNull(queries).snapshot().state)
                val id = input.text("id")
                val clip = requireNotNull(queries).motionClips().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Motion not found: $id")
                val time = input.getValue("time").jsonPrimitive.double
                put("id", id); put("time", time)
                putJsonObject("values") {
                    MotionClips.sampleAll(clip, time, input["loop"]?.jsonPrimitive?.booleanOrNull ?: clip.loop)
                        .forEach { (parameter, value) -> put(parameter.raw, value) }
                }
            }
            else -> workspace.editMotion(input.text("state"), input).compact()
        }
    }

    registerCommandOperations("revision", "Save, checkpoint, inspect history or restore a chosen snapshot. Every mutation returns a new state; chain it. Restore is a write, not preview. Keep your own task plan; checkpoints preserve useful progress.",
        mapOf("checkpoint" to "history_checkpoint", "list" to "history_list", "restore" to "history_checkout"), true)
}

private fun parseTarget(value: String): WorkspaceKeyformTargetRef {
    val pair = value.split(':', limit = 2)
    require(pair.size == 2 && pair[0] in setOf("mesh", "warp", "rotation", "part", "glue") && pair[1].isNotBlank()) { "Use kind:id from inspect" }
    return WorkspaceKeyformTargetRef(pair[0], pair[1])
}
internal fun WorkspaceMutationResult.compact() = buildJsonObject {
    put("state", requireNotNull(state)); put("history_node_id", historyNodeId); put("project_id", requireNotNull(projectId))
    // Emitted only when false: a no-op is the case a caller has to act on, and leaving it off a normal
    // response keeps the common payload small.
    if (!applied) put("applied", false)
    if (affectedObjectIds.isNotEmpty()) put("changed", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
    geometryDiagnostics?.let { put("geometry_diagnostics", it) }
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
