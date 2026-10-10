package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*

/** Exact public edit summaries; fields are derived from the domain result, not inferred from requests. */
internal object WorkspaceAuthoringResultSchemas {
    private val s = WorkspaceResultSchema
    private val changed = s.array(s.handle(), 1, Int.MAX_VALUE)
    val compactFields = s.identity + mapOf("applied" to s.constant(false), "changed" to changed,
        "geometry_diagnostics" to WorkspaceGeometrySafetySchemas.report)
    private val compact = s.obj(compactFields, s.identity.keys)
    private fun compactWith(fields: Map<String, JsonObject>) = s.obj(compactFields + fields, s.identity.keys + fields.keys)
    private val normalizedFields = s.identity + mapOf("revision" to s.handle(), "applied" to s.boolean())
    private val normalized = s.obj(normalizedFields +
        listOf("affectedLayerIds", "affectedParameterIds", "affectedObjectIds").associateWith { changed }, normalizedFields.keys)
    private val key = s.dictionary(s.number())
    private val pose = s.obj(s.identity + mapOf("values" to key, "locked" to s.array(s.handle())))

    private val compactOperations = setOf(
        "settings_update", "layer_mesh_update", "rig_deform", "keyform_apply", "vertex_group_update",
        "object_edit_appearance", "rig_edit_structure", "simulation_delete", "simulation_clear_bake",
        "source_paint_brush", "source_paint_pencil", "source_paint_eraser", "source_paint_bucket", "source_paint_shape", "source_paint_clear",
        "skeleton_auto", "skeleton_put", "skeleton_enable", "skeleton_bone", "skeleton_move", "skeleton_bind", "skeleton_remove", "skeleton_delete",
        "motion_put", "motion_delete", "motion_seed_builtin", "motion_set_key", "motion_delete_key", "motion_remove_curve",
        "motion_pose", "motion_move_keys", "motion_delete_keys", "motion_paste_keys", "motion_replace_keys", "motion_preset",
        "motion_create", "motion_duplicate", "motion_rename", "motion_properties",
    )
    private val normalizedOperations = setOf(
        "parameter_create", "parameter_update", "parameter_delete", "swing_put", "swing_delete",
        "physics_put", "physics_delete", "physics_fit", "physics_config",
        "history_checkpoint", "history_checkout", "layer_add_from_asset", "layer_set_placement", "layer_finalize_placement", "layer_soft_delete", "layer_restore", "layer_import_images", "layer_set_bounds", "layer_cancel_import",
    )

    fun forOperation(id: String): JsonObject? = when (id) {
        in WorkspaceWarpControlEdits.supported -> WorkspaceWarpControlSchemas.result(id)
        "swing_preview", "swing_preview_commit", "swing_preview_get" -> WorkspaceSwingSessionSchemas.result
        "swing_preview_render" -> WorkspaceObservationResultSchemas.forOperation("view_render_model")
        in compactOperations -> compact
        in normalizedOperations -> normalized
        "physics_import" -> s.obj(normalizedFields + mapOf("imported" to s.array(s.handle()), "disabled" to s.array(s.handle()),
            "missing_parameters" to s.dictionary(s.array(s.handle())), "fps" to s.integer(0, 240)), normalizedFields.keys + "imported")
        "preview_set", "preview_reset" -> pose
        "preview_physics" -> s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "workspace_id" to s.handle(),
            "settled" to s.boolean(), "outputs" to key, "values" to key))
        "preview_pose" -> s.obj(s.identity + mapOf("values" to key, "locked" to s.array(s.handle()),
            "keyed" to s.array(s.obj(mapOf("parameter" to s.handle(), "time" to s.number(0))))))
        "preview_playback", "preview_playback_get" -> s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(),
            "workspace_id" to s.handle(), "clip_id" to s.handle(), "time" to s.number(0), "playing" to s.boolean(),
            "tracking" to s.boolean(), "smooth_tracking" to s.boolean(), "pointer_active" to s.boolean(), "values" to key, "animation" to s.boolean(),
            "elapsed" to s.number(0), "active_motion" to s.handle()),
            setOf("project_id", "state", "workspace_id", "time", "playing", "tracking", "pointer_active", "values", "animation", "elapsed"))
        "layer_classify" -> s.obj(s.identity + mapOf("layer_id" to s.handle(), "applied" to s.constant(false)), s.identity.keys + "layer_id")
        "rig_create_warp" -> compactWith(mapOf("target" to s.handle()))
        "canvas_warp", "canvas_rotation", "canvas_topology" -> s.obj(s.identity + mapOf("id" to s.handle(), "applied" to s.constant(false), "geometry_diagnostics" to WorkspaceGeometrySafetySchemas.report), s.identity.keys + "id")
        "canvas_glue" -> s.obj(s.identity + mapOf("id" to s.handle(), "applied" to s.constant(false), "mesh_a" to s.handle(), "mesh_b" to s.handle(), "pair_count" to s.integer(0), "geometry_diagnostics" to WorkspaceGeometrySafetySchemas.report),
            s.identity.keys + setOf("id", "mesh_a", "mesh_b", "pair_count"))
        "path_put" -> s.obj(s.identity + mapOf("path_id" to s.handle(), "target" to s.handle()))
        "path_delete" -> s.obj(s.identity + ("deleted" to s.handle()))
        "path_deform" -> s.obj(s.identity + mapOf("target" to s.handle(), "key" to key, "changed" to changed, "geometry_diagnostics" to WorkspaceGeometrySafetySchemas.report), s.identity.keys + setOf("target", "key"))
        "source_split_polygon", "source_split_components", "source_split_depth" -> WorkspaceJobResultSchemas.split
        in WorkspaceGenerationUpdate.supported -> WorkspaceJobResultSchemas.generationUpdate
        "source_get_components" -> s.obj(mapOf("project_id" to s.handle(), "state" to s.handle(), "revision" to s.handle(),
            "layer_id" to s.handle(), "can_split" to s.boolean(), "count" to s.integer(0), "components" to s.array(s.obj(mapOf(
                "index" to s.integer(0), "canvas_x" to s.number(), "canvas_y" to s.number())))))
        else -> WorkspaceAnimationResultSchemas.forOperation(id) ?: WorkspacePathResultSchemas.forOperation(id)
            ?: WorkspaceObservationResultSchemas.forOperation(id)
            ?: WorkspaceHistoryPhysicsResultSchemas.forOperation(id)
            ?: WorkspaceAssetResultSchemas.forOperation(id)
            ?: WorkspaceSimulationResultSchemas.forOperation(id)
            ?: WorkspaceInspectionResultSchemas.forOperation(id)
    }
}

internal object WorkspacePathResultSchemas {
    private val s = WorkspaceResultSchema
    private val point = s.obj(mapOf("x" to s.number(), "y" to s.number(), "corner" to s.boolean(),
        "a" to s.integer(), "b" to s.integer(), "c" to s.integer(), "wa" to s.number(), "wb" to s.number(), "wc" to s.number()))
    private val fields = mapOf("id" to s.handle(), "target" to s.handle(), "level" to s.integer(), "width" to s.number(), "hardness" to s.number(),
        "closed" to s.boolean(), "pointCount" to s.integer(0), "points" to s.array(point), "meshBounds" to s.vector(4))
    private val paths = s.obj(mapOf("paths" to s.array(s.obj(fields, fields.keys - setOf("points", "meshBounds")))))
    private val preview = s.obj(mapOf("vertexCount" to s.integer(0), "maxDisplacement" to s.number(0), "avgDisplacement" to s.number(0),
        "width" to s.number(), "hardness" to s.number(), "showWidth" to s.boolean(), "showHardness" to s.boolean(),
        "baseBounds" to s.vector(4), "deformedBounds" to s.vector(4)))
    fun forOperation(id: String): JsonObject? = when (id) {
        "path_get", "path_list" -> paths
        "path_preview" -> preview
        else -> null
    }
}

internal object WorkspaceAnimationResultSchemas {
    private val s = WorkspaceResultSchema
    private val values = s.dictionary(s.number())
    private val boneFields = linkedMapOf(
        "id" to s.handle(), "name" to s.string(), "parent" to s.handle(),
        "role" to s.choices(*BoneRole.entries.map { it.name }.toTypedArray()), "side" to s.choices(*Side.entries.map { it.name }.toTypedArray()),
        "head" to s.vector(2), "tail" to s.vector(2), "drawables" to s.array(s.string()), "chainIndex" to s.integer(),
        "direction" to s.number(), "minAngle" to s.number(), "maxAngle" to s.number(), "blendWidth" to s.number(),
        "connected" to s.boolean(), "parameterOverride" to s.string(), "mirror" to s.string(),
        "ik" to s.obj(mapOf("chainLength" to s.integer(1, 32), "iterations" to s.integer(1, 256), "tolerancePx" to s.number(0.001, 10), "bendDirection" to s.integer(-1, 1))),
    )
    private val bone = s.obj(boneFields, boneFields.keys - setOf("parent", "blendWidth", "connected", "parameterOverride", "mirror"))
    private val skeletonFields = linkedMapOf(
        "version" to buildJsonObject { put("type", "integer"); put("const", 10) }, "enabled" to s.boolean(), "symmetryAxisX" to s.number(),
        "manualWeights" to s.dictionary(s.obj(mapOf("positions" to s.array(s.number()), "triangles" to s.array(s.integer(0)), "weights" to s.array(values)))),
        "ikTargets" to s.dictionary(s.obj(mapOf("x" to s.number(), "y" to s.number(), "enabled" to s.boolean()))),
        "savedPoses" to s.dictionary(values), "sampling" to s.obj(mapOf("tolerancePx" to s.number(0.25, 4), "minimumStepDegrees" to s.number(2.5, 20), "maxMeshKeyforms" to s.integer(100, 1200), "jointMeshSegments" to s.integer(4, 32))),
        "bones" to s.array(bone),
    )
    val skeleton = s.obj(skeletonFields, skeletonFields.keys - "symmetryAxisX")
    private val motionKeyFields = mapOf("time" to s.number(), "value" to s.number(),
        "interpolation" to s.choices(*MotionInterpolation.entries.map { it.name }.toTypedArray()), "out" to s.vector(2), "in" to s.vector(2))
    private val motionKey = s.obj(motionKeyFields, setOf("time", "value"))
    private val motionFields = linkedMapOf("id" to s.handle(), "name" to s.string(), "builtin" to s.string(), "loop" to s.boolean(),
        "duration" to s.number(), "fps" to s.number(), "fade_in" to s.number(), "fade_out" to s.number(), "enabled" to s.boolean(),
        "curves" to s.array(s.obj(mapOf("parameter" to s.handle(), "keys" to s.array(motionKey)))))
    private val motion = s.obj(motionFields, motionFields.keys - "builtin")
    private val motionSummaryFields = mapOf("id" to s.handle(), "name" to s.string(), "builtin" to s.string(),
        "duration" to s.number(), "enabled" to s.boolean(), "curves" to s.integer(0))

    fun forOperation(id: String): JsonObject? = when (id) {
        "skeleton_get" -> s.obj(mapOf("state" to s.handle(), "spec" to skeleton), setOf("state"))
        "skeleton_propose" -> s.obj(mapOf("state" to s.handle(), "spec" to skeleton))
        "skeleton_pose" -> s.obj(mapOf("state" to s.handle(), "project_id" to s.handle(), "values" to values))
        "motion_get" -> s.obj(mapOf("state" to s.handle(), "clip" to motion))
        "motion_sample" -> s.obj(mapOf("state" to s.handle(), "id" to s.handle(), "time" to s.number(), "values" to values))
        "motion_list" -> s.obj(mapOf("state" to s.handle(), "clips" to s.array(s.obj(motionSummaryFields, motionSummaryFields.keys - "builtin")), "builtins" to s.array(s.handle())))
        else -> null
    }
}
