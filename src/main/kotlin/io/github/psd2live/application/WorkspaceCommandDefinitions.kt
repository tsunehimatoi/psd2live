package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.project.WorkspaceCreateParameterRequest
import io.github.psd2live.project.WorkspaceHistorySnapshot
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceKeyformTargetRef
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspaceLayerSnapshot
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceObjectSnapshot
import io.github.psd2live.project.WorkspaceParameterSnapshot
import io.github.psd2live.project.WorkspacePixelRect
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.github.psd2live.project.WorkspaceRenderedView
import io.github.psd2live.project.WorkspaceTaskSnapshot
import io.github.psd2live.project.WorkspaceTaskStatus
import io.github.psd2live.project.WorkspaceUpdateParameterRequest
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.project.WorkspaceMutationResult

import io.github.psd2live.core.Bounds
import kotlinx.serialization.json.*
import java.util.Base64

internal fun registerWorkspaceCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
    registerCatalogParameterCommands(catalog, workspace)
    registerCatalogQueriesCommands(catalog, workspace)
    registerCatalogHistoryCommands(catalog, workspace)
    registerCatalogRenderCommands(catalog, workspace)
    registerCatalogPoseCommands(catalog, workspace)
    registerCatalogPhysicsCommands(catalog, workspace)
    registerCatalogSwingCommands(catalog, workspace)
    registerCatalogAssetCommands(catalog, workspace)
    registerCatalogSourceCommands(catalog, workspace)
    registerAssetCommands(catalog, workspace)
    registerSourceCommands(catalog, workspace)
    registerObservationCommands(catalog, workspace)
}

private fun registerCatalogParameterCommands(catalog: WorkspaceCommands, parameter: WorkspaceParameterPort) {
	catalog.register(
		name = "parameter_create",
		description = "Create a real Cubism parameter in the authoritative rig. kind=blend_shape stores an additive blend-shape parameter (square keys, neutral at 0) instead of a keyform axis. It survives layer/mesh rebuilds, history checkout, restart and export.",
		inputSchema = parameterCreateSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.createParameter(
				WorkspaceCreateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedState = request.requiredString("state"),
					name = request.requiredString("name"),
					min = request.float("min", -1f),
					max = request.float("max", 1f),
					default = request.float("default", 0f),
					kind = request.optionalString("kind") ?: "normal",
					repeat = request.boolean("repeat", false),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "parameter_update",
		description = "Update a Cubism parameter's name, numeric range/default, kind, or repeat flag. The stable parameter ID is not renamed.",
		inputSchema = parameterUpdateSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.updateParameter(
				WorkspaceUpdateParameterRequest(
					id = request.requiredString("parameter_id"),
					expectedState = request.requiredString("state"),
					name = request.optionalString("name"),
					min = request.optionalFloat("min"),
					max = request.optionalFloat("max"),
					default = request.optionalFloat("default"),
					kind = request.optionalString("kind"),
					repeat = request.optionalBoolean("repeat"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}

	catalog.register(
		name = "parameter_delete",
		description = "Delete a Cubism parameter and safely collapse every drawable, deformer, part and glue keyform axis at its prior default. History remains recoverable.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("parameter_id") { put("type", "string") }
				putJsonObject("state") { put("type", "string") }
				putJsonObject("task_id") { put("type", "string") }
			},
			required = listOf("parameter_id", "state"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult {
			parameter.deleteParameter(
				parameterId = request.requiredString("parameter_id"),
				expectedState = request.requiredString("state"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}
}

private fun registerCatalogQueriesCommands(catalog: WorkspaceCommands, queries: WorkspaceQueries) {

	catalog.register(
		name = "history_list",
		description = "Read the append-only branch-preserving workspace history and current HEAD. Nodes cannot be edited or deleted.",
		hints = READ_ONLY,
	) {
		mutationResult { queries.history().toJson() }
	}

    catalog.register(
        name = "physics_simulate",
        description = "Run the exported physics (Cubism's evaluation, 60 fps) from rest: inputs jump to their values at t=0, hold for hold seconds, then return to defaults. Per output: start/peak/final and settle time while held and after release, plus sampled [t, value]. Read-only; checks swing size, overshoot and settling that static poses cannot.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("inputs") { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "number") })
                put("description", "Parameter values to step to, e.g. {\"ParamAngleX\": 30}") }
            putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Groups to run; default every active group") }
            putJsonObject("hold") { put("type", "number"); put("minimum", 0); put("maximum", 20) }
            putJsonObject("duration") { put("type", "number"); put("minimum", 0.1); put("maximum", 20) }
            putJsonObject("samples") { put("type", "integer"); put("minimum", 2); put("maximum", 60) }
        }, required = listOf("inputs")), hints = READ_ONLY,
    ) { request -> mutationResult { queries.simulatePhysics(request.arguments) } }

}

private fun registerCatalogHistoryCommands(catalog: WorkspaceCommands, history: WorkspaceHistoryPort) {
    catalog.register(
        name = "history_checkpoint",
        hints = MUTATING,
        description = "Append an explicit history checkpoint even when the model is unchanged.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject { putJsonObject("summary") { put("type", "string") } }, required = listOf("summary")),
    ) { request -> mutationResult { history.checkpoint(request.requiredString("summary")).toJson() } }

	catalog.register(
		name = "history_checkout",
		description = "Move workspace HEAD to any immutable history node and rebuild that exact editable source/rig state. Old branches remain available.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("node_id") { put("type", "string"); put("description", "History node to restore") }
			},
			required = listOf("node_id"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult { history.checkoutHistory(request.requiredString("node_id"), MutationAuthor.AGENT).toJson() }
	}
}

private fun registerCatalogRenderCommands(catalog: WorkspaceCommands, render: WorkspaceRenderPort) {
	catalog.register(
		name = "view_render_layer",
		description = "Render one layer directly from RGBA model data as transparent or checkerboard PNG. This is not a UI screenshot. For differences, separation, or missing-pixel completion, pass this View to Nano Banana Pro/NBP or GPT Image 2; never redraw it with Python/PIL/OpenCV.",
		inputSchema = viewSchema(includeBackground = true),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			val layerId = request.requiredString("layer_id")
			val background = request.arguments.get("background")?.jsonPrimitive?.contentOrNull
				?.uppercase()?.let(WorkspaceViewBackground::valueOf) ?: WorkspaceViewBackground.TRANSPARENT
			render.captureObservation().renderLayer(layerId, background, request.outputSpec())
		}
	}

	catalog.register(
		name = "view_render_context",
		description = "Composite visible layers into one focused PNG around a layer. object_scale below 1 includes surrounding context; this never returns a PSD or UI screenshot. Use it as a reference for Nano Banana Pro/NBP or GPT Image 2 when painted pixels must be changed or reconstructed.",
		inputSchema = viewSchema(includeBackground = true, includeFocus = true),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			render.captureObservation().renderContext(
				layerId = request.requiredString("layer_id"),
				objectScale = request.float("object_scale", 0.65f),
				aspectRatio = request.float("aspect_ratio", 1f),
				background = request.background(),
				output = request.outputSpec(),
			)
		}
	}

	catalog.register(
		name = "view_render_model",
		description = "Evaluate a rig pose, composite selected layers into one PNG, and render only an explicit canvas rectangle or a focused object region. Returns reversible pixel-to-canvas placement metadata.",
		inputSchema = modelViewSchema(),
		hints = READ_ONLY,
	) { request ->
		renderResult {
			val annotatePathWidth = request.arguments.get("annotate_path_width")?.jsonPrimitive?.content == "true"
			val annotatePathHardness = request.arguments.get("annotate_path_hardness")?.jsonPrimitive?.content == "true"
			val annotatePathRadius = request.arguments.get("annotate_path_radius")?.jsonPrimitive?.content == "true"
			render.captureObservation().renderModel(
				WorkspaceModelViewRequest(
					parameters = request.floatMap("parameters"),
					includeLayerIds = request.optionalStringSet("include_layer_ids"),
					annotateLayerIds = request.optionalStringSet("annotate_layer_ids").orEmpty(),
					annotateDeformerIds = request.optionalStringSet("annotate_deformer_ids").orEmpty(),
					annotatePathIds = request.optionalStringSet("annotate_path_ids").orEmpty(),
					annotatePathWidth = annotatePathWidth || annotatePathRadius,
					annotatePathHardness = annotatePathHardness || annotatePathRadius,
					annotatePathRadius = annotatePathRadius,
					pointIndices = request.arguments.get("point_indices")?.jsonPrimitive?.content == "true",
					frame = request.viewFrame(),
					background = request.background(),
					output = request.outputSpec(),
				),
			)
		}
	}

    catalog.register(
        name = "view_check_coverage",
        description = "Measure whether selected layers cover an explicitly expected canvas rectangle at one pose. For scalp gaps select hair layers, not the opaque face underneath. Returns uncovered pixel bounds and a mapped image; the region's semantic expectation is caller-supplied.",
        inputSchema = WorkspaceCommandSchema(properties = JsonObject(requireNotNull(modelViewSchema().properties) + buildJsonObject {
            putJsonObject("alpha_threshold") { put("type","integer"); put("minimum",1); put("maximum",255); put("default",128) }
        }), required = listOf("viewport", "include_layer_ids")), hints = READ_ONLY,
    ) { request ->
        try {
            val frame=request.viewFrame()
            require(frame is WorkspaceViewFrame.CanvasRect) { "Use canvas_rect for the expected coverage region" }
            val view=render.captureObservation().renderModel(WorkspaceModelViewRequest(parameters=request.floatMap("parameters"),
                includeLayerIds=request.optionalStringSet("include_layer_ids"), frame=frame,
                background=WorkspaceViewBackground.TRANSPARENT, output=request.outputSpec()))
            val image=javax.imageio.ImageIO.read(view.png.inputStream()) ?: error("Invalid rendered PNG")
            val measurement=measureCoverage(image,request.arguments.get("alpha_threshold")?.jsonPrimitive?.int ?: 128)
            val metadata=buildJsonObject { put("view",view.toJson()); put("coverage",measurement) }
            WorkspaceOperationOutput(metadata, listOf(view.png))
        } catch(e: IllegalArgumentException) { throw e }
          catch(e: IllegalStateException) { throw e }
    }
}

private fun registerCatalogPoseCommands(catalog: WorkspaceCommands, render: WorkspaceRenderPort) {
    catalog.register(
        name = "view_render_poses",
        description = "Compare 1..9 poses in one labeled sheet, in input order. parameters are shared; poses override them. target_long_edge/max_bytes bound the entire sheet. Each tile imageRect [x,y,width,height] maps to the shared canvasRect [left,top,right,bottom]; labels are excluded. Static poses, not physics or revision comparison. Rendered off-screen from one captured committed version; use this, not preview_set, to compare poses without moving the user's parameter sliders.",
        inputSchema = WorkspaceCommandSchema(properties = JsonObject(requireNotNull(modelViewSchema().properties) + buildJsonObject {
            putJsonObject("poses") { put("type","array"); put("minItems",1); put("maxItems",9)
                putJsonObject("items") { put("type","object"); putJsonObject("additionalProperties") { put("type","number") } }
            }
            putJsonObject("columns") { put("type","integer"); put("minimum",1); put("maximum",3); put("description","Row-major columns; omit for a compact grid") }
        }), required = listOf("viewport", "poses")), hints = READ_ONLY,
    ) { request ->
        try {
            val poses = request.arguments.getValue("poses").jsonArray
            require(poses.size in 1..9)
            val columns = request.arguments.get("columns")?.jsonPrimitive?.int ?: poseSheetColumns(poses.size)
            require(columns in 1..minOf(3, poses.size)) { "columns must be 1..min(3, pose count)" }
            val sharedParameters = request.arguments.get("parameters")?.jsonObject?.mapValues { it.value.jsonPrimitive.float }.orEmpty()
            val output = request.outputSpec()
            val rows = (poses.size + columns - 1) / columns
            val tileOutput = output.copy(targetLongEdge = maxOf(128, output.targetLongEdge / maxOf(columns, rows)))
            val frame=request.viewFrame()
            require(frame is WorkspaceViewFrame.CanvasRect) { "Use canvas_rect for a fixed comparison camera" }
            val observation = render.captureObservation()
            val revision = observation.snapshot().revisionId
            val pathWidth = request.boolean("annotate_path_width", false)
            val pathHardness = request.boolean("annotate_path_hardness", false)
            val pathRadius = request.boolean("annotate_path_radius", false)
            val views=poses.map { pose -> observation.renderModel(WorkspaceModelViewRequest(
                parameters=sharedParameters + pose.jsonObject.mapValues { it.value.jsonPrimitive.float },
                includeLayerIds=request.optionalStringSet("include_layer_ids"), frame=frame,
                background=request.background(), output=tileOutput,
                annotateLayerIds=request.optionalStringSet("annotate_layer_ids").orEmpty(),
                annotateDeformerIds=request.optionalStringSet("annotate_deformer_ids").orEmpty(),
                annotatePathIds=request.optionalStringSet("annotate_path_ids").orEmpty(),
                annotatePathWidth=pathWidth || pathRadius,
                annotatePathHardness=pathHardness || pathRadius,
                annotatePathRadius=pathRadius,
                pointIndices=request.boolean("point_indices",false))) }
            check(views.all { it.revisionId == revision }) { "Observation returned another revision" }
            val sheet = renderPoseSheet(views, output, columns)
            WorkspaceOperationOutput(sheet.metadata, sheet.images)
        } catch(e: IllegalArgumentException) { throw e }
          catch(e: IllegalStateException) { throw e }
    }
}

private fun registerCatalogPhysicsCommands(catalog: WorkspaceCommands, physics: WorkspacePhysicsPort) {
    catalog.register(
        name = "physics_put",
        description = "Create or change a physics group by ID; only given fields change. An existing ID (generated or user) is the base, a new ID starts as a 10-unit hair pendulum fed by head/body X and Z. " +
            "inputs/outputs/segments replace their lists; length/mobility/delay/acceleration set every segment (length is the whole strand) and output_scale every output. " +
            "A pendulum has 1..16 segments; output vertex k reads the tip of segment k as an angle relative to segment k-1 (vertex 1: to gravity). " +
            "Editing a generated group replaces it until physics_delete; a user group on a generated group's output replaces that group. enabled=false turns any group off. " +
            "Parameters must exist and the outputs need authored forms; physics drives parameters, not Warps. Enables physics in the same commit unless only turning a group off.",
        inputSchema = physicsPutSchema(), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        physics.putPhysics(arguments, request.requiredString("state"), request.optionalString("task_id")).toJson()
    } }

    catalog.register(
        name = "physics_delete",
        description = "Delete a user physics group, or return a replaced generated group to its generated values. Generated groups themselves are turned off with physics_put enabled=false.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        physics.deletePhysics(request.requiredString("id"), request.requiredString("state")).toJson()
    } }

    catalog.register(
        name = "physics_config",
        description = "Set the physics evaluation order and/or rate. Cubism runs groups in order and a later group reads an earlier group's outputs in the same step; order lists group IDs to run first, the rest follow in their current order. fps is the project's physics rate: physics steps at it and physics3.json and the CMO3 declare it (default 60; 0 is unlimited: physics steps with each rendered frame and no Fps is declared). The preview's own frame rate is a user setting and follows the display by default.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("order") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            putJsonObject("fps") { put("type", "integer"); put("minimum", 0); put("maximum", 240) }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("state")), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        physics.configurePhysics(arguments["order"]?.jsonArray?.map { it.jsonPrimitive.content }, arguments["fps"]?.jsonPrimitive?.intOrNull,
            request.requiredString("state")).toJson()
    } }

    catalog.register(
        name = "physics_import",
        description = "Import a Cubism physics3.json (absolute path) as user groups: an existing ID is replaced (a generated group until physics_delete), imported groups run after the current ones in file order, other user groups on the same outputs are turned off, and the file's Fps becomes the project's. Reports missing parameters; create them or edit the groups.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("path") { put("type", "string") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("path", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        val (result, report) = physics.importPhysics(request.requiredString("path"), request.requiredString("state"))
        kotlinx.serialization.json.JsonObject(result.toJson() + report)
    } }

    catalog.register(
        name = "physics_fit",
        description = "Scale a group's outputs so a standard head sway (the panel's response curve: pulled fully right for 1 s, then let go) swings each output to target percent of its parameter's end (default 100). Outputs that do not move keep their scale. " +
            "observed_peaks fits to a measured response instead of the standard sway: keys are output indexes (\"0\", \"1\"...), each mapped to the reach physics_audition reported as peaks (1 = the parameter's end), as the panel's Fit after dragging the pendulum. At least one output must have moved.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("target") { put("type", "number"); put("minimum", 10); put("maximum", 300) }
            putJsonObject("observed_peaks") {
                put("type", "object"); put("minProperties", 1)
                putJsonObject("additionalProperties") { put("type", "number"); put("minimum", 0) }
            }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        val target = (request.arguments.get("target")?.jsonPrimitive?.floatOrNull ?: 100f) / 100f
        val observed = request.arguments["observed_peaks"]?.let(WorkspacePhysicsEdits::observedPeaks)
        physics.fitPhysics(request.requiredString("id"), target, request.requiredString("state"), observed).toJson()
    } }
}

private fun registerCatalogSwingCommands(catalog: WorkspaceCommands, swing: WorkspaceSwingPort) {
    catalog.register(
        name = "swing_put",
        description = "Create or replace a regenerating swing by ID. Each target Warp gets a -1/0/1 axis per parameter, recomputed from its current forms whenever the rig rebuilds; mesh targets are wrapped in a tight Warp in the same commit. " +
            "kind=lateral swings the tip left/right, kind=vertical up/down: a bend across the pinned edge, or a stretch along it (vertical under a top pivot bounces). Pass motions=[{kind:lateral,...},{kind:vertical,...}] to combine both on the same Warp; each direction has its own fields, parameters and pendulum. " +
            "parallel (0..1) keeps the tip edge level while bending, so every column sways alongside the others: use it high for hair whose several strands share one Warp. " +
            "Missing parameters are created (-1..1) as ParamSwing<id>[_X|_Y][_<segment>]. Omit physics to size the pendulums from the target, physics_enabled=false for none; lateral pendulums take head/body X and Z, vertical ones head/body pitch. Segments (1..3) share a direction's pendulum with a vertex per segment. Rotation deformers are not targets.",
        inputSchema = swingSchema(), hints = MUTATING,
    ) { request -> mutationResult {
        val arguments = request.arguments
        val parsed = io.github.psd2live.core.SwingAuthoring.request(arguments)
        swing.putSwing(parsed.edit, parsed.estimatePhysics, request.requiredString("state"), request.optionalString("task_id")).toJson()
    } }

    catalog.register(
        name = "swing_delete",
        description = "Delete a swing by ID. bake=true first writes its current forms as ordinary keys and keeps its pendulum, so the motion can be edited by hand; otherwise its generated axes and created parameters go with it.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("bake") { put("type", "boolean") }
            putJsonObject("state") { put("type", "string") }
        }, required = listOf("id", "state")), hints = MUTATING,
    ) { request -> mutationResult {
        swing.deleteSwing(request.requiredString("id"), request.arguments.get("bake")?.jsonPrimitive?.booleanOrNull ?: false,
            request.requiredString("state")).toJson()
    } }
}

private fun registerCatalogAssetCommands(catalog: WorkspaceCommands, asset: WorkspaceAssetPort) {
    catalog.register(
        name = "asset_inspect",
        description = "Inspect actual staged PNG pixels, spatial placement and transparency counts. Use for quick usability/alpha diagnosis, then trial assembly. Overlapping hair, minor tone differences and hidden-root/edge variation are not automatic rejection reasons; judge depth, seams and intended motion in composition.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject { putJsonObject("asset_id") { put("type", "string") } }, required = listOf("asset_id")),
        hints = READ_ONLY,
    ) { request ->
        try {
            val preview = asset.inspectAsset(request.requiredString("asset_id"))
            val metadata = buildJsonObject {
                put("asset", preview.asset.toJson()); put("transparentPixels", preview.transparentPixels); put("translucentPixels", preview.translucentPixels)
                put("image_order", "processed, original (when retained)")
            }
            WorkspaceOperationOutput(metadata, listOf(preview.png) + listOfNotNull(preview.originalPng))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw failure
        }
    }

    catalog.register(
        name = "asset_import_png",
        description = "Stage a PNG from original pixels, painting or image generation against the captured workspace state. Omit solid_background for native alpha; reference_id imports need explicit registration. Returns the committed auxiliary state without adding Rig history.",
        inputSchema = pngImportSchema(), hints = MUTATING,
    ) { request ->
        val result = asset.importPng(request.arguments)
        WorkspaceOperationOutput(result.metadata, result.images)
    }

	catalog.register(
		name = "layer_add_from_asset",
		description = "Add a staged PNG as a real editable source layer, generate its mesh/rig through the normal pipeline, and append one immutable history node. No approval step is required.",
		inputSchema = addLayerSchema(),
		hints = MUTATING,
	) { request ->
		mutationResult {
			asset.addLayer(
				WorkspaceAddLayerRequest(
					assetId = request.requiredString("asset_id"),
					expectedState = request.requiredString("state"),
					name = request.requiredString("name"),
					layerId = request.optionalString("layer_id"),
					groupPath = request.optionalString("group_path").orEmpty(),
					insertion = request.layerInsertion(),
					semanticTag = request.optionalString("semantic_tag") ?: "unknown",
					side = request.optionalString("side") ?: "none",
					visible = request.boolean("visible", true),
					opacity = request.float("opacity", 1f),
					trimTransparent = request.boolean("trim_transparent", true),
                    registrationId = request.optionalString("registration_id"),
					parentDeformerId = request.optionalString("parent_deformer_id"),
					taskId = request.optionalString("task_id"),
				),
			).toJson()
		}
	}
}

private fun registerCatalogSourceCommands(catalog: WorkspaceCommands, source: WorkspaceSourcePort) {
	catalog.register(
		name = "layer_delete",
		description = "Delete a layer: its pixels leave the project and its meshes leave the rig, with the glue, masks, paths and weights on them; a layer the generators read leaves their input, so the rig regenerates without it. One undoable history edit (history_checkout brings it back). The last layer cannot be deleted.",
		inputSchema = WorkspaceCommandSchema(
			properties = buildJsonObject {
				putJsonObject("layer_id") { put("type", "string"); put("description", "Stable layer ID to delete") }
				putJsonObject("state") { put("type", "string"); put("description", "Opaque state from workspace_inspect or the preceding result") }
				putJsonObject("task_id") { put("type", "string"); put("description", "Optional long-task correlation ID") }
			},
			required = listOf("layer_id", "state"),
		),
		hints = MUTATING,
	) { request ->
		mutationResult {
			source.deleteLayer(
				layerId = request.requiredString("layer_id"),
				expectedState = request.requiredString("state"),
				taskId = request.optionalString("task_id"),
			).toJson()
		}
	}
    catalog.register(
        name = "layer_restore",
        description = "Restore layers an older version soft-deleted (layer_delete removes layers for good; undo it with history_checkout). Omit layer_ids to restore them all; restoring active layers is a no-op.",
        inputSchema = WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("state") { put("type", "string") }
            putJsonObject("layer_ids") {
                put("type", "array"); put("minItems", 1); put("maxItems", 256)
                putJsonObject("items") { put("type", "string"); put("minLength", 1) }
            }
        }, required = listOf("state")), hints = MUTATING,
    ) { request -> mutationResult {
        source.restoreDeletedLayers(request.arguments["layer_ids"]?.jsonArray?.map { it.jsonPrimitive.content },
            request.requiredString("state")).toJson()
    } }
}

private fun physicsPutSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
    properties = buildJsonObject {
        listOf("id", "name", "state", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        putJsonObject("enabled") { put("type", "boolean") }
        fun JsonObjectBuilder.type() = putJsonObject("type") { put("type", "string"); putJsonArray("enum") { add("x"); add("angle") }
            put("description", "x: sideways travel; angle: tilt") }
        putJsonObject("inputs") { put("type", "array"); put("maxItems", 16)
            put("description", "What moves the root. Weight is % of the normalized range")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("parameter") { put("type", "string") }
                putJsonObject("weight") { put("type", "number"); put("minimum", 0); put("maximum", 100) }
                type(); putJsonObject("reflect") { put("type", "boolean") }
            }; putJsonArray("required") { add("parameter") } } }
        putJsonObject("outputs") { put("type", "array"); put("maxItems", 16)
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("parameter") { put("type", "string") }
                putJsonObject("vertex") { put("type", "integer"); put("minimum", 1); put("maximum", 16) }
                putJsonObject("scale") { put("type", "number"); put("description", "Parameter units per radian of the segment's swing") }
                putJsonObject("weight") { put("type", "number"); put("minimum", 0); put("maximum", 100) }
                putJsonObject("reflect") { put("type", "boolean") }
            }; putJsonArray("required") { add("parameter") } } }
        putJsonObject("segments") { put("type", "array"); put("minItems", 1); put("maxItems", 16)
            put("description", "Root to tip; omitted fields keep the current segment's")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") {
                putJsonObject("length") { put("type", "number"); put("exclusiveMinimum", 0) }
                putJsonObject("mobility") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("description", "Shakiness") }
                putJsonObject("delay") { put("type", "number"); put("exclusiveMinimum", 0); put("description", "Reaction speed; higher reacts faster") }
                putJsonObject("acceleration") { put("type", "number"); put("minimum", 0); put("description", "Settling speed") }
            } } }
        putJsonObject("segment_count") { put("type", "integer"); put("minimum", 1); put("maximum", 16); put("description", "Resize the strand, copying the last segment") }
        putJsonObject("length") { put("type", "number"); put("exclusiveMinimum", 0) }
        putJsonObject("mobility") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
        putJsonObject("delay") { put("type", "number"); put("exclusiveMinimum", 0) }
        putJsonObject("acceleration") { put("type", "number"); put("minimum", 0) }
        putJsonObject("output_scale") { put("type", "number") }
        putJsonObject("normalization") { put("type", "object"); put("description", "Span a full-range input maps to")
            putJsonObject("properties") { listOf("position", "angle").forEach { key -> putJsonObject(key) { put("type", "object")
                putJsonObject("properties") { listOf("min", "default", "max").forEach { putJsonObject(it) { put("type", "number") } } } } } } }
    },
    required = listOf("id", "state"),
)

private fun swingSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
    properties = buildJsonObject {
        listOf("id", "name", "state", "task_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
        putJsonObject("targets") { put("type", "array"); put("minItems", 1); putJsonObject("items") { put("type", "string") }
            put("description", "Warp or mesh IDs; meshes sharing a parent are wrapped in one Warp") }
        fun JsonObjectBuilder.shapeFields() {
            putJsonObject("magnitude") { put("type", "number"); put("minimum", 0); put("maximum", 0.7); put("description", "Tip travel at ±1 as a fraction of the pinned-edge-to-tip length") }
            putJsonObject("lift") { put("type", "number"); put("minimum", -0.5); put("maximum", 0.5); put("description", "Extra rise (+) or droop (-) of a bending tip at ±1") }
            putJsonObject("softness") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("description", "0 bends evenly; 1 keeps the root stiff") }
            putJsonObject("zoom") { put("type", "number"); put("minimum", -0.5); put("maximum", 0.5); put("description", "Tip width change at ±1") }
            putJsonObject("parallel") { put("type", "number"); put("minimum", 0); put("maximum", 1)
                put("description", "0 swings the lattice like one board (tip edge tilts); 1 keeps the tip edge level, columns swaying side by side like separate strands") }
            putJsonObject("flip") { put("type", "boolean") }
            putJsonObject("physics") { put("type", "object"); put("description", "Pendulum override; omit to size it from the target")
                putJsonObject("properties") { listOf("length", "mobility", "delay", "acceleration", "output_scale").forEach { key -> putJsonObject(key) { put("type", "number") } } } }
        }
        fun JsonObjectBuilder.motionFields() {
            putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("lateral"); add("vertical") } }
            putJsonObject("segments") { put("type", "integer"); put("minimum", 1); put("maximum", 3) }
            putJsonObject("parameters") { put("type", "array"); put("minItems", 1); put("maxItems", 3); putJsonObject("items") { put("type", "string") }
                put("description", "One per segment, root first") }
            shapeFields()
        }
        motionFields()
        putJsonObject("motions") { put("type", "array"); put("minItems", 1); put("maxItems", 2)
            put("description", "One entry per direction; replaces the flat kind/segments/shape fields")
            putJsonObject("items") { put("type", "object"); putJsonObject("properties") { motionFields() } } }
        putJsonObject("fulcrum") { put("type", "string"); putJsonArray("enum") { listOf("auto", "top", "bottom", "left", "right").forEach { add(it) } }
            put("description", "Pinned lattice edge; auto hangs tall targets from the top and pivots wide ones on the side nearer the body center") }
        putJsonObject("tilt") { put("type", "number"); put("minimum", -75); put("maximum", 75)
            put("description", "Degrees the swing rectangle turns about the pinned edge's midpoint, for art hanging at a slant; + turns the tip toward the right end of a top/bottom edge or the bottom end of a side edge") }
        putJsonObject("offset_along") { put("type", "number"); put("minimum", -1); put("maximum", 1)
            put("description", "Moves the swing rectangle's pinned edge toward the tip, in pinned-edge-to-tip lengths; what lies behind it stays put") }
        putJsonObject("offset_across") { put("type", "number"); put("minimum", -1); put("maximum", 1)
            put("description", "Moves the swing rectangle along its pinned edge, in edge widths; + toward the right end of a top/bottom edge or the bottom end of a side edge") }
        putJsonObject("preset") { put("type", "string"); putJsonArray("enum") { listOf("hair", "accessory", "cloth").forEach { add(it) } } }
        putJsonObject("physics_enabled") { put("type", "boolean"); put("description", "Default true: generate the pendulums") }
    },
    required = listOf("id", "targets", "state"),
)

private fun pngImportSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
        putJsonObject("state") { put("type", "string") }
        putJsonObject("png_path") { put("type", "string"); put("description", "Absolute local PNG path from the image generator; avoids transferring base64 through model context") }
        putJsonObject("reference_id") { put("type", "string"); put("description", "Reference package from asset_prepare_reference. V2 import keeps raw PNG, removes declared matte, and requires asset_register before adding a layer.") }
        put("processing", processingSchema())
        putJsonObject("solid_background") { put("type", "string"); put("pattern", "^#[0-9a-fA-F]{6}$"); put("description", "Actual generated matte color. Default generation to pure white #FFFFFF for dark hair or pure black #000000 for light hair to avoid colored fringe; do not guess or automatically strip alpha when omitted. Removes only border-connected near-color pixels, not a baked checkerboard. Inspect remaining matte in composition.") }
        putJsonObject("background_tolerance") { put("type", "integer"); put("minimum", 0); put("maximum", 64); put("default", 16) }
        putJsonObject("require_transparency") { put("type", "boolean"); put("description", "Set true for separated hair: reject fully opaque or empty results after cleanup.") }

		putJsonObject("png_base64") {
			put("type", "string")
			put("description", "PNG bytes encoded as Base64, optionally as a data:image/png;base64 URI")
		}
		putJsonObject("source_pixel_rect") {
			put("type", "object")
			put("description", "Optional crop rectangle in the referenced View's pixel coordinates")
			putJsonObject("properties") {
				listOf("left", "top", "width", "height").forEach { name ->
					putJsonObject(name) { put("type", "integer") }
				}
			}
			putJsonArray("required") {
				listOf("left", "top", "width", "height").forEach { add(JsonPrimitive(it)) }
			}
		}
	},
	required = listOf("state", "reference_id"),
)

private fun parameterCreateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = parameterProperties(includeRequiredValues = true),
	required = listOf("parameter_id", "state", "name"),
)

private fun parameterUpdateSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = parameterProperties(includeRequiredValues = false),
	required = listOf("parameter_id", "state"),
)

private fun parameterProperties(includeRequiredValues: Boolean): JsonObject = buildJsonObject {
	putJsonObject("parameter_id") {
		put("type", "string")
		put("description", "Stable Cubism ID. Creation accepts 1-64 ASCII letters/digits/underscores and must start with a letter")
	}
	putJsonObject("state") {
		put("type", "string")
		put("description", "Opaque state from workspace_inspect or the preceding result")
	}
	putJsonObject("name") { put("type", "string") }
	putJsonObject("min") { put("type", "number"); if (includeRequiredValues) put("default", -1) }
	putJsonObject("max") { put("type", "number"); if (includeRequiredValues) put("default", 1) }
	putJsonObject("default") { put("type", "number"); if (includeRequiredValues) put("default", 0) }
	putJsonObject("kind") {
		put("type", "string")
		putJsonArray("enum") { listOf("normal", "blend_shape").forEach { add(JsonPrimitive(it)) } }
		if (includeRequiredValues) put("default", "normal")
	}
	putJsonObject("repeat") { put("type", "boolean"); if (includeRequiredValues) put("default", false) }
	putJsonObject("task_id") { put("type", "string") }
}

private fun addLayerSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
        putJsonObject("registration_id") { put("type", "string"); put("description", "Required for reference-package assets; immutable absolute placement from asset_register") }
		putJsonObject("asset_id") { put("type", "string") }
		putJsonObject("state") {
			put("type", "string")
			put("description", "Opaque state from workspace_inspect or the preceding result")
		}
		putJsonObject("name") { put("type", "string") }
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Optional stable ID; a unique agent: ID is generated when omitted")
		}
		putJsonObject("group_path") { put("type", "string") }
		putJsonObject("semantic_tag") {
			put("type", "string")
			put("description", "SemanticTag enum name in lowercase, for example front_hair")
			put("default", "unknown")
		}
		putJsonObject("side") {
			put("type", "string")
			putJsonArray("enum") { listOf("left", "right", "none").forEach { add(JsonPrimitive(it)) } }
			put("default", "none")
		}
		putJsonObject("insertion") {
			put("type", "object")
			put("description", "Painter-order placement: top, bottom, above or below a stable layer ID")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") { listOf("top", "bottom", "above", "below").forEach { add(JsonPrimitive(it)) } }
				}
				putJsonObject("reference_layer_id") { put("type", "string") }
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("visible") { put("type", "boolean"); put("default", true) }
		putJsonObject("opacity") { put("type", "number"); put("minimum", 0); put("maximum", 1); put("default", 1) }
		putJsonObject("trim_transparent") {
			put("type", "boolean")
			put("default", true)
			put("description", "Crop transparent padding after mapping the complete PNG to canvas units")
		}
		putJsonObject("parent_deformer_id") { put("type", "string") }
		putJsonObject("task_id") { put("type", "string") }
	},
	required = listOf("asset_id", "state", "name"),
)

private fun viewSchema(includeBackground: Boolean, includeFocus: Boolean = false): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("layer_id") {
			put("type", "string")
			put("description", "Stable layer ID returned by project_list_layers")
		}
		if (includeBackground) {
			putJsonObject("background") {
				put("type", "string")
				putJsonArray("enum") {
					add(JsonPrimitive("transparent"))
					add(JsonPrimitive("checkerboard"))
				}
				put("default", "transparent")
			}
		}
		if (includeFocus) {
			putJsonObject("object_scale") {
				put("type", "number")
				put("minimum", 0.05)
				put("maximum", 4.0)
				put("default", 0.65)
				put("description", "Fraction of the fitted view occupied by the focused layer; values below 1 show surroundings")
			}
			putJsonObject("aspect_ratio") {
				put("type", "number")
				put("minimum", 0.1)
				put("maximum", 10.0)
				put("default", 1.0)
				put("description", "Output frame width divided by height; the layer is fitted without stretching")
			}
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
			put("description", "Maximum encoded PNG bytes; resolution is reduced without changing canvas placement when necessary")
		}
	},
	required = listOf("layer_id"),
)

private fun modelViewSchema(): WorkspaceCommandSchema = WorkspaceCommandSchema(
	properties = buildJsonObject {
		putJsonObject("annotate_deformer_ids") { put("type","array"); put("maxItems",16); putJsonObject("items") { put("type","string") };put("description","Warp IDs: posed lattice, name and stable ID; [] is a clean image") }
		putJsonObject("annotate_path_ids") { put("type","array"); put("maxItems",32); putJsonObject("items") { put("type","string") };put("description","Deform Path IDs to overlay on the mesh (e.g. ['path_1'] or ['*'] for all paths, or ['L2']/['L3']); [] is a clean image") }
		putJsonObject("annotate_path_width") { put("type","boolean"); put("default",false); put("description","Whether to draw influence width boundaries (outer falloff dashed circle) around deform path control points") }
		putJsonObject("annotate_path_hardness") { put("type","boolean"); put("default",false); put("description","Whether to draw hardness boundaries (inner core solid circle) around deform path control points") }
		putJsonObject("annotate_path_radius") { put("type","boolean"); put("default",false); put("description","Legacy alias for enabling both annotate_path_width and annotate_path_hardness") }
		putJsonObject("point_indices") { put("type","boolean");put("default",false) }
		putJsonObject("parameters") {
			put("type", "object")
			put("description", "Cubism parameter ID to value, for example {ParamAngleX: 10.0}. Omitted parameters render at their defaults, not at the authored pose; read workspace_inspect scope=preview and pass its values to start from it. Rendering never changes the authored pose.")
			putJsonObject("additionalProperties") { put("type", "number") }
		}
		putJsonObject("include_layer_ids") {
			put("type", "array")
			put("description", "Exact layer IDs to composite. Omit to follow the active canvas's local visibility, so layers the user hid, layers under a hidden deformer and layers outside a solo are missing; pass IDs for a result independent of the canvas. [] renders no model layers.")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("annotate_layer_ids") {
			put("type", "array")
			put("description", "Layer IDs to label and outline at their deformed positions")
			putJsonObject("items") { put("type", "string") }
		}
		putJsonObject("viewport") {
			put("type", "object")
			put("description", "Required camera: {mode:canvas_rect,left,top,width,height} or {mode:focus_layers,layer_ids,object_scale,aspect_ratio}")
			putJsonObject("properties") {
				putJsonObject("mode") {
					put("type", "string")
					putJsonArray("enum") {
						add(JsonPrimitive("canvas_rect"))
						add(JsonPrimitive("focus_layers"))
					}
				}
				listOf("left", "top", "width", "height", "object_scale", "aspect_ratio").forEach { name ->
					putJsonObject(name) { put("type", "number") }
				}
				putJsonObject("layer_ids") {
					put("type", "array")
					putJsonObject("items") { put("type", "string") }
				}
			}
			putJsonArray("required") { add(JsonPrimitive("mode")) }
		}
		putJsonObject("background") {
			put("type", "string")
			putJsonArray("enum") {
				add(JsonPrimitive("transparent"))
				add(JsonPrimitive("checkerboard"))
			}
			put("default", "transparent")
		}
		putJsonObject("target_long_edge") {
			put("type", "integer")
			put("minimum", 128)
			put("maximum", 4096)
			put("default", 1024)
			put("description", "Requested PNG long edge in pixels; independent of canvas units")
		}
		putJsonObject("max_bytes") {
			put("type", "integer")
			put("minimum", 65536)
			put("maximum", 16777216)
			put("default", 4194304)
		}
	},
	required = listOf("viewport"),
)

private suspend fun renderResult(block: suspend () -> WorkspaceRenderedView): WorkspaceOperationOutput {
    val view = block()
    return WorkspaceOperationOutput(view.toJson(), listOf(view.png))
}
private suspend fun mutationResult(block: suspend () -> JsonObject): WorkspaceOperationOutput = WorkspaceOperationOutput(block())
private fun WorkspaceCommandInput.requiredString(name: String): String =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("Missing required argument: $name")

private fun WorkspaceCommandInput.optionalString(name: String): String? =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun WorkspaceCommandInput.boolean(name: String, default: Boolean): Boolean =
	arguments.get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
		?: if (arguments.containsKey(name) == true) throw IllegalArgumentException("$name must be a boolean") else default

private fun WorkspaceCommandInput.optionalBoolean(name: String): Boolean? =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull()
			?: throw IllegalArgumentException("$name must be a boolean")
	}

private fun WorkspaceCommandInput.optionalFloat(name: String): Float? =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	}

private fun WorkspaceCommandInput.outputSpec(): WorkspaceViewOutputSpec =
	WorkspaceViewOutputSpec(
		targetLongEdge = integerOrDefault("target_long_edge", integerOrDefault("max_edge", 1024)),
		maxBytes = integerOrDefault("max_bytes", 4 * 1024 * 1024),
	)

private fun WorkspaceCommandInput.integerOrDefault(name: String, default: Int): Int =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
	} ?: default

private fun WorkspaceCommandInput.float(name: String, default: Float): Float =
	arguments.get(name)?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("$name must be numeric")
	} ?: default

private fun WorkspaceCommandInput.background(): WorkspaceViewBackground =
	arguments.get("background")?.jsonPrimitive?.contentOrNull
		?.uppercase()?.let(WorkspaceViewBackground::valueOf) ?: WorkspaceViewBackground.TRANSPARENT

private fun WorkspaceCommandInput.floatMap(name: String): Map<String, Float> =
	arguments.get(name)?.jsonObject?.mapValues { (id, value) ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Parameter $id must be numeric")
	}.orEmpty()

private fun WorkspaceCommandInput.optionalStringSet(name: String): Set<String>? =
	arguments.get(name)?.jsonArray?.map { value ->
		value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
			?: throw IllegalArgumentException("$name must contain non-empty strings")
	}?.toSet()

private fun WorkspaceCommandInput.viewFrame(): WorkspaceViewFrame {
	val viewport = arguments.get("viewport")?.jsonObject
		?: throw IllegalArgumentException("Missing required argument: viewport")
	fun requiredFloat(name: String): Float = viewport[name]?.jsonPrimitive?.floatOrNull
		?: throw IllegalArgumentException("viewport.$name must be numeric")
	fun optionalFloat(name: String, default: Float): Float = viewport[name]?.let { value ->
		value.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("viewport.$name must be numeric")
	} ?: default
	return when (viewport["mode"]?.jsonPrimitive?.contentOrNull) {
		"canvas_rect" -> {
			val left = requiredFloat("left")
			val top = requiredFloat("top")
			val width = requiredFloat("width")
			val height = requiredFloat("height")
			WorkspaceViewFrame.CanvasRect(Bounds(left, top, left + width, top + height))
		}

		"focus_layers" -> {
			val layerIds = viewport["layer_ids"]?.jsonArray?.map { value ->
				value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
					?: throw IllegalArgumentException("viewport.layer_ids must contain non-empty strings")
			}?.toSet().orEmpty()
			WorkspaceViewFrame.FocusLayers(
				layerIds = layerIds,
				objectScale = optionalFloat("object_scale", 0.65f),
				aspectRatio = optionalFloat("aspect_ratio", 1f),
			)
		}

		null -> throw IllegalArgumentException("Missing required argument: viewport.mode")
		else -> throw IllegalArgumentException("viewport.mode must be canvas_rect or focus_layers")
	}
}

private fun WorkspaceCommandInput.layerInsertion(): WorkspaceLayerInsertion {
	val insertion = arguments.get("insertion")?.jsonObject ?: return WorkspaceLayerInsertion.Top
	val mode = insertion["mode"]?.jsonPrimitive?.contentOrNull
		?: throw IllegalArgumentException("insertion.mode is required")
	fun reference(): String = insertion["reference_layer_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
		?: throw IllegalArgumentException("insertion.reference_layer_id is required for $mode")
	return when (mode) {
		"top" -> WorkspaceLayerInsertion.Top
		"bottom" -> WorkspaceLayerInsertion.Bottom
		"above" -> WorkspaceLayerInsertion.Above(reference())
		"below" -> WorkspaceLayerInsertion.Below(reference())
		else -> throw IllegalArgumentException("insertion.mode must be top, bottom, above, or below")
	}
}

internal fun WorkspaceProjectSnapshot.toJson(
	includeLayers: Boolean,
	layers: List<WorkspaceLayerSnapshot> = this.layers,
): JsonObject = buildJsonObject {
	projectId?.let { put("projectId", it) }
	put("revisionId", revisionId)
    put("state", state)
	historyHeadNodeId?.let { put("historyHeadNodeId", it) }
    projectFile?.let { put("projectFile", it) }
    put("projectDirty", projectDirty)
    put("projectSaving", projectSaving)
    projectSaveError?.let { put("projectSaveError", it) }
	put("loaded", loaded)
	inputName?.let { put("inputName", it) }
	canvasWidth?.let { put("canvasWidth", it) }
	canvasHeight?.let { put("canvasHeight", it) }
	put("busy", busy)
	put("status", status)
	put("persistenceStatus", persistenceStatus)
	persistenceError?.let { put("persistenceError", it) }
	selectedLayerId?.let { put("selectedLayerId", it) }
	put("layerCount", layers.size)
	put("totalLayerCount", this@toJson.layers.size)
	put("parameterCount", parameters.size)
	if (includeLayers) put("layers", JsonArray(layers.map(WorkspaceLayerSnapshot::toJson)))
}

private fun WorkspaceParameterSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("name", name)
	put("min", min)
	put("max", max)
	put("default", default)
	put("current", current)
	put("kind", kind)
}

private fun WorkspaceLayerSnapshot.toJson(): JsonObject = buildJsonObject {
	put("id", id)
	put("sourceName", sourceName)
	put("rasterWidth", rasterWidth)
	put("rasterHeight", rasterHeight)
	put("canvasUnitsPerSourcePixelX", bounds.width / rasterWidth.coerceAtLeast(1).toFloat())
	put("canvasUnitsPerSourcePixelY", bounds.height / rasterHeight.coerceAtLeast(1).toFloat())
	put("groupPath", groupPath)
	put("order", order)
	put("semanticTag", semanticTag)
	put("side", side)
	put("classificationType", classificationType)
	put("parameterBinding", parameterBinding)
	put("switchId", switchId)
	put("confidence", confidence)
	put("visible", visible)
	put("deleted", deleted)
	put("derived", derived)
	sourceAssetId?.let { put("sourceAssetId", it) }
	sourceSpatialReferenceId?.let { put("sourceSpatialReferenceId", it) }
	putJsonObject("bounds") {
		put("left", bounds.left)
		put("top", bounds.top)
		put("right", bounds.right)
		put("bottom", bounds.bottom)
	}
	putJsonArray("sourcePixelToCanvas") {
		add(JsonPrimitive(bounds.width / rasterWidth.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.left))
		add(JsonPrimitive(0))
		add(JsonPrimitive(bounds.height / rasterHeight.coerceAtLeast(1).toFloat()))
		add(JsonPrimitive(bounds.top))
	}
	putJsonObject("opaqueBounds") {
		put("left", opaqueBounds.left)
		put("top", opaqueBounds.top)
		put("right", opaqueBounds.right)
		put("bottom", opaqueBounds.bottom)
	}
}

private fun WorkspaceHistorySnapshot.toJson(): JsonObject = buildJsonObject {
	put("headNodeId", headNodeId)
	putJsonArray("nodes") {
		nodes.forEach { node ->
			add(buildJsonObject {
				put("id", node.id)
				node.parentId?.let { put("parentId", it) }
				put("revisionId", node.revisionId)
				put("summary", node.summary)
				put("actor", node.actor)
				node.taskId?.let { put("taskId", it) }
				put("createdAt", node.createdAt)
				put("isHead", node.isHead)
			})
		}
	}
}

internal fun WorkspaceImportedPngAsset.toJson(): JsonObject = buildJsonObject {
    put("details", details)
	put("assetId", id)
	put("sha256", sha256)
	put("mimeType", "image/png")
	put("pixelWidth", pixelWidth)
	put("pixelHeight", pixelHeight)
	put("sourceSpatialReferenceId", placement.sourceViewId)
	putJsonObject("canvasRect") {
		put("left", placement.canvasRect.left)
		put("top", placement.canvasRect.top)
		put("right", placement.canvasRect.right)
		put("bottom", placement.canvasRect.bottom)
		put("width", placement.canvasRect.width)
		put("height", placement.canvasRect.height)
	}
	put("canvasUnitsPerPixelX", placement.canvasUnitsPerPixelX)
	put("canvasUnitsPerPixelY", placement.canvasUnitsPerPixelY)
}

internal fun WorkspaceMutationResult.toJson(): JsonObject = buildJsonObject {
	put("historyNodeId", historyNodeId)
    state?.let { put("state", it) }
    projectId?.let { put("project_id", it) }
	put("revisionId", revisionId)
	put("summary", summary)
	// False means the request described the state the workspace was already in: no node was appended and
	// nothing needs re-reading. Stated on every response so a caller never has to infer it from an
	// unchanged revision.
	put("applied", applied)
	putJsonArray("affectedLayerIds") { affectedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedParameterIds") { affectedParameterIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("affectedObjectIds") { affectedObjectIds.forEach { add(JsonPrimitive(it)) } }
}

private fun WorkspaceObjectSnapshot.toJson(): JsonObject = buildJsonObject {
	putJsonObject("target") {
		put("kind", target.kind)
		put("id", target.id)
		target.secondaryId?.let { put("secondaryId", it) }
	}
	put("name", name)
	parentId?.let { put("parentId", it) }
	partId?.let { put("partId", it) }
	put("visible", visible)
	if (topologyInfo.isNotEmpty()) {
		putJsonObject("topologyInfo") {
			topologyInfo.forEach { (k, v) -> put(k, v) }
		}
	}
	geometry?.let { geom ->
		putJsonObject("geometry") {
			put("keyformCount", geom.keyformCount)
			putJsonArray("axes") {
				geom.axes.forEach { axis ->
					add(buildJsonObject {
						put("parameterId", axis.parameterId)
						putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
					})
				}
			}
			putJsonArray("cells") {
				geom.cells.forEach { cell ->
					add(buildJsonObject {
						putJsonObject("coordinate") {
							cell.coordinate.forEach { (p, v) -> put(p, v) }
						}
						cell.originX?.let { put("originX", it) }
						cell.originY?.let { put("originY", it) }
						cell.angle?.let { put("angle", it) }
						cell.scale?.let { put("scale", it) }
						cell.controlPoints?.let { cps ->
							putJsonArray("controlPoints") { cps.forEach { cp -> add(JsonPrimitive(cp)) } }
						}
						cell.positionDeltas?.let { pds ->
							putJsonArray("positionDeltas") { pds.forEach { pd -> add(JsonPrimitive(pd)) } }
						}
					})
				}
			}
		}
	}
	if (channels.isNotEmpty()) {
		putJsonArray("channels") {
			channels.forEach { track ->
				add(buildJsonObject {
					put("channel", track.channel)
					put("staticValue", track.staticValue)
					put("keyformCount", track.keyformCount)
					putJsonArray("axes") {
						track.axes.forEach { axis ->
							add(buildJsonObject {
								put("parameterId", axis.parameterId)
								putJsonArray("keys") { axis.keys.forEach { k -> add(JsonPrimitive(k)) } }
							})
						}
					}
				})
			}
		}
	}
}

private fun WorkspaceTaskSnapshot.toJson(includeEvents: Boolean = true): JsonObject = buildJsonObject {
	put("taskId", id)
	put("objective", objective)
	putJsonArray("plan") { plan.forEach { add(JsonPrimitive(it)) } }
	put("status", status.name.lowercase())
	currentStep?.let { put("currentStep", it) }
	put("progress", progress)
	put("inputRevisionId", inputRevisionId)
	put("inputHistoryHeadNodeId", inputHistoryHeadNodeId)
	put("createdAt", createdAt)
	put("updatedAt", updatedAt)
	putJsonArray("artifactIds") { artifactIds.forEach { add(JsonPrimitive(it)) } }
	if (includeEvents) putJsonArray("events") {
		events.forEach { event ->
			add(buildJsonObject {
				put("sequence", event.sequence)
				put("createdAt", event.createdAt)
				put("status", event.status.name.lowercase())
				put("message", event.message)
				putJsonArray("artifactIds") { event.artifactIds.forEach { add(JsonPrimitive(it)) } }
			})
		}
	}
}

internal fun WorkspaceRenderedView.toJson(): JsonObject = buildJsonObject {
	put("viewId", viewId)
	put("revisionId", revisionId)
	put("kind", kind)
	put("mimeType", "image/png")
	put("pngBytes", png.size)
	put("sha256", sha256)
	put("originalWidth", originalWidth)
	put("originalHeight", originalHeight)
	put("renderedWidth", renderedWidth)
	put("renderedHeight", renderedHeight)
	put("scale", scale)
	putJsonObject("appliedParameters") {
		appliedParameters.forEach { (id, value) -> put(id, value) }
	}
	putJsonArray("outOfRangeParameters") {
		outOfRangeParameters.forEach { diagnostic ->
			add(buildJsonObject {
				put("id", diagnostic.id)
				put("value", diagnostic.value)
				put("min", diagnostic.min)
				put("max", diagnostic.max)
			})
		}
	}
	putJsonArray("includedLayerIds") { includedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedLayerIds") { annotatedLayerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedDeformerIds") { annotatedDeformerIds.forEach { add(JsonPrimitive(it)) } }
	putJsonArray("annotatedPathIds") { annotatedPathIds.forEach { add(JsonPrimitive(it)) } }
	put("annotatedPathWidth", annotatedPathWidth)
	put("annotatedPathHardness", annotatedPathHardness)
	put("annotatedPathRadius", annotatedPathRadius)
	put("pointIndices",pointIndices)
	putJsonArray("objectIds") { objectIds.forEach { add(JsonPrimitive(it)) } }
	putJsonObject("canvasRect") {
		put("left", canvasRect.left)
		put("top", canvasRect.top)
		put("right", canvasRect.right)
		put("bottom", canvasRect.bottom)
	}
	putJsonObject("spatial") {
		put("spatialReferenceId", viewId)
		put("coordinateSpace", spatial.coordinateSpace)
		put("pixelOrigin", "top_left_edge")
		put("pixelWidth", spatial.pixelWidth)
		put("pixelHeight", spatial.pixelHeight)
		put("pixelAspectRatio", 1)
		put("colorSpace", "sRGB")
		put("alphaMode", "straight")
		put("canvasWidth", spatial.canvasWidth)
		put("canvasHeight", spatial.canvasHeight)
		put("canvasUnitsPerPixelX", spatial.canvasUnitsPerPixelX)
		put("canvasUnitsPerPixelY", spatial.canvasUnitsPerPixelY)
		putJsonObject("requestedViewRect") {
			put("left", spatial.requestedViewRect.left)
			put("top", spatial.requestedViewRect.top)
			put("right", spatial.requestedViewRect.right)
			put("bottom", spatial.requestedViewRect.bottom)
			put("width", spatial.requestedViewRect.width)
			put("height", spatial.requestedViewRect.height)
		}
		putJsonObject("viewRect") {
			put("left", spatial.viewRect.left)
			put("top", spatial.viewRect.top)
			put("right", spatial.viewRect.right)
			put("bottom", spatial.viewRect.bottom)
			put("width", spatial.viewRect.width)
			put("height", spatial.viewRect.height)
		}
		spatial.focusRect?.let { focus ->
			putJsonObject("focusRect") {
				put("left", focus.left)
				put("top", focus.top)
				put("right", focus.right)
				put("bottom", focus.bottom)
				put("width", focus.width)
				put("height", focus.height)
			}
		}
		putJsonArray("focusLayerIds") { spatial.focusLayerIds.forEach { add(JsonPrimitive(it)) } }
		spatial.objectScale?.let { put("objectScale", it) }
		putJsonArray("pixelToCanvas") {
			add(JsonPrimitive(spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.viewRect.left))
			add(JsonPrimitive(0))
			add(JsonPrimitive(spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(spatial.viewRect.top))
		}
		putJsonArray("canvasToPixel") {
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(-spatial.viewRect.left / spatial.canvasUnitsPerPixelX))
			add(JsonPrimitive(0))
			add(JsonPrimitive(1f / spatial.canvasUnitsPerPixelY))
			add(JsonPrimitive(-spatial.viewRect.top / spatial.canvasUnitsPerPixelY))
		}
		put("placementRule", "map_full_png_to_view_rect_preserve_aspect")
	}
}
