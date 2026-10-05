package io.github.psd2live.application

import kotlinx.serialization.json.*

/** Background operations publish and execute the same terminal business contract. */
internal object WorkspaceJobResultSchemas {
    private val s = WorkspaceResultSchema
    private val lifecycleFields = s.identity + mapOf("revision" to s.handle(), "applied" to s.boolean())
    private val lifecycle = s.obj(lifecycleFields)
    private val source = s.obj(lifecycleFields + ("layers" to s.array(s.handle())))
    private val batch = s.obj(lifecycleFields + mapOf("edit_count" to s.integer(1, 128), "changed" to s.array(s.handle()),
        "geometry_diagnostics" to WorkspaceGeometryQualitySchemas.report), lifecycleFields.keys + setOf("edit_count", "changed"))
    private val modelExport = s.obj(mapOf("state" to s.handle(), "revision" to s.handle(),
        "files" to s.array(s.obj(mapOf("path" to s.handle(), "bytes" to s.integer(0)))), "warnings" to s.array(s.string())))
    private val psdExport = s.obj(mapOf("state" to s.handle(), "path" to s.handle(), "bytes" to s.integer(0), "layers" to s.integer(0)))

    private val results = linkedMapOf(
        "project_import_psd" to source, "project_create_artwork" to source, "project_import_cmo3" to lifecycle,
        "project_open" to lifecycle, "project_save" to lifecycle, "project_save_as" to lifecycle,
        "project_export_model" to modelExport, "project_export_psd" to psdExport, "workspace_apply_edits" to batch,
        "workspace_preview_edits" to WorkspaceGeometryQualitySchemas.preview,
    ).apply {
        put("preview_pose", requireNotNull(WorkspaceAuthoringResultSchemas.forOperation("preview_pose")))
        put("paint_session_commit", WorkspacePaintSessionSchemas.commit)
        put("swing_preview", WorkspaceSwingSessionSchemas.result)
        put("swing_preview_commit", WorkspaceSwingSessionSchemas.result)
        put("simulation_preview", WorkspaceSimulationPreviewSchemas.result)
        put("simulation_preview_step", WorkspaceSimulationPreviewSchemas.result)
        for (id in WorkspaceWarpControlEdits.supported) put(id, WorkspaceWarpControlSchemas.result(id))
        for (id in WorkspaceSimulationEdits.supported + WorkspacePhysicsEdits.supported + WorkspaceRasterCommands.supported + WorkspaceLayerEdits.supported + WorkspaceAssetLayerEdits.supported + WorkspaceImagePlacementEdits.supported + WorkspaceImageLayerCommands.OP + WorkspaceGenerationCommands.supported + WorkspaceWarpEdits.supported)
            put(id, requireNotNull(WorkspaceAuthoringResultSchemas.forOperation(id)))
        for (id in WorkspacePartitionCommands.supported) put(id, source)
        for (id in WorkspaceAssetSessions.supported) put(id, requireNotNull(WorkspaceAssetResultSchemas.forOperation(id)))
        for (id in WorkspaceSamplingJobs.supported + WorkspaceObservationJobs.motion) {
            val schema = requireNotNull(WorkspaceAuthoringResultSchemas.forOperation(id) ?: WorkspaceObservationResultSchemas.forOperation(id))
            val identity = mapOf("project_id" to s.handle(), "state" to s.handle(), "revision" to s.handle())
            put(id, JsonObject(schema + mapOf("properties" to JsonObject(schema.getValue("properties").jsonObject + identity),
                "required" to JsonArray(schema.getValue("required").jsonArray + identity.keys.map(::JsonPrimitive)))))
        }
    }

    fun result(operation: String): JsonObject = results[operation] ?: error("Background operation has no result contract: $operation")

    private val base = linkedMapOf(
        "id" to s.handle(), "operation" to s.choices(*results.keys.toTypedArray()), "project_id" to s.handle(),
        "input_state" to s.handle(), "progress" to s.number(0, 1), "message" to s.string(),
        "created_at" to s.handle(), "updated_at" to s.handle(),
    )

    fun snapshot(operation: String? = null, includeResult: Boolean = true): JsonObject {
        val operations = if (operation == null) results else mapOf(operation to result(operation))
        val fields = base + ("operation" to s.choices(*operations.keys.toTypedArray()))
        fun branch(status: JsonObject, terminal: Boolean, extra: Map<String, JsonObject> = emptyMap()): JsonObject {
            val all = fields + mapOf("status" to status, "terminal" to s.constant(terminal)) + extra
            return s.obj(all, all.keys - "project_id")
        }
        val completed = if (includeResult) operations.map { (id, schema) ->
            branch(s.constant("completed"), true, mapOf("operation" to s.constant(id), "result" to schema))
        } else listOf(branch(s.constant("completed"), true))
        return s.union(listOf(
            branch(s.choices("queued", "running", "cancelling"), false),
            branch(s.constant("cancelled"), true),
            branch(s.constant("failed"), true, mapOf("error" to s.failure)),
        ) + completed)
    }

    private val allSnapshots by lazy { snapshot() }
    private val summarySnapshots by lazy { snapshot(includeResult = false) }

    fun operationOutput(id: String): JsonObject? = when (id) {
        in results -> snapshot(id)
        "job_get", "job_wait", "job_cancel" -> allSnapshots
        "job_list" -> s.obj(mapOf("items" to s.array(summarySnapshots), "total" to s.integer(0), "next" to s.integer(1)), setOf("items", "total"))
        "source_sample_color" -> s.obj(mapOf("layer_id" to s.handle(), "rgba" to JsonObject(s.array(s.integer(0, 255)) +
            mapOf("minItems" to JsonPrimitive(4), "maxItems" to JsonPrimitive(4)))))
        else -> null
    }
}
