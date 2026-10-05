package io.github.psd2live.application

import kotlinx.serialization.json.*

internal object WorkspaceResultSchema {
    fun string() = buildJsonObject { put("type", "string") }
    fun handle() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    fun boolean() = buildJsonObject { put("type", "boolean") }
    fun number(minimum: Number? = null, maximum: Number? = null) = buildJsonObject {
        put("type", "number"); minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
    }
    fun integer(minimum: Int? = null, maximum: Int? = null) = buildJsonObject {
        put("type", "integer"); minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
    }
    fun constant(value: Boolean) = buildJsonObject { put("type", "boolean"); put("const", value) }
    fun constant(value: String) = buildJsonObject { put("type", "string"); put("const", value) }
    fun choices(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    fun array(item: JsonObject) = buildJsonObject { put("type", "array"); put("items", item) }
    fun array(item: JsonObject, minimum: Int, maximum: Int) = JsonObject(array(item) + mapOf("minItems" to JsonPrimitive(minimum), "maxItems" to JsonPrimitive(maximum)))
    fun vector(size: Int) = array(number(), size, size)
    fun dictionary(value: JsonObject) = buildJsonObject { put("type", "object"); put("additionalProperties", value) }
    fun nullable(value: JsonObject) = buildJsonObject { put("oneOf", JsonArray(listOf(value, buildJsonObject { put("type", "null") }))) }
    fun obj(fields: Map<String, JsonObject>, required: Set<String> = fields.keys) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    fun union(branches: List<JsonObject>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        // Only branch schemas constrain values: merging discriminator consts would invalidate other branches.
        val fields = branches.flatMap { it.getValue("properties").jsonObject.keys }.distinct()
        put("properties", JsonObject(fields.associateWith { JsonObject(emptyMap()) }))
        put("oneOf", JsonArray(branches))
    }

    val identity = linkedMapOf("project_id" to handle(), "state" to handle(), "history_node_id" to handle())

    /** Machine-readable errors retain their code-specific fields, without an unrestricted details object. */
    val failure: JsonObject by lazy {
        fun branch(code: JsonObject, details: Map<String, JsonObject> = emptyMap()) =
            obj(linkedMapOf("code" to code, "message" to string()) + details)
        union(listOf(
            branch(constant("quality_rejected"), mapOf("diagnostics" to WorkspaceQualitySchemas.report)),
            branch(constant("geometry_unsafe"), mapOf("diagnostics" to WorkspaceGeometryQualitySchemas.report)),
            branch(constant("invalid_request"), mapOf("field" to string())),
            branch(constant("output_contract"), mapOf("field" to string(), "operation" to handle())),
            branch(constant("invalid_edit"), mapOf("edit_index" to integer(0), "edit_operation" to handle())),
            branch(constant("state_conflict"), mapOf("expected_state" to string(), "actual_state" to string())),
            branch(constant("project_conflict"), mapOf("expected_project" to nullable(string()), "actual_project" to nullable(string()))),
            branch(choices("request_id_reused", "unsaved_changes", "workspace_busy", "permission_denied", "io_error",
                "invalid_argument", "invalid_state", "operation_failed")),
        ))
    }
}

internal fun validateWorkspaceResult(operation: String, schema: JsonObject, result: JsonObject) {
    try { validateOperationSchema(result, schema, "result") }
    catch (failure: WorkspaceValidationException) { throw WorkspaceOutputContractFailure(operation, failure) }
}

/** A native response includes exactly one successful data object or one structured failure. */
internal fun WorkspaceOperationDefinition.responseEnvelope(): JsonObject {
    val result = resultSchema
    val operation = WorkspaceResultSchema.constant(id)
    val success = WorkspaceResultSchema.obj(mapOf(
        "ok" to buildJsonObject { put("type", "boolean"); put("const", true) }, "operation" to operation, "data" to result))
    val failure = WorkspaceResultSchema.obj(mapOf(
        "ok" to buildJsonObject { put("type", "boolean"); put("const", false) }, "operation" to operation, "error" to WorkspaceResultSchema.failure))
    return JsonObject(WorkspaceResultSchema.union(listOf(success, failure)) + buildJsonObject {
        put("required", JsonArray(listOf(JsonPrimitive("ok"), JsonPrimitive("operation"))))
        result["\$defs"]?.let { put("\$defs", it) }
    })
}

internal object WorkspaceAuxiliaryResultSchemas {
    private val values = WorkspaceResultSchema.dictionary(WorkspaceResultSchema.number())
    private val pose = mapOf("values" to values, "locked" to WorkspaceResultSchema.array(WorkspaceResultSchema.handle()))
    private val snapshotFields = linkedMapOf("id" to WorkspaceResultSchema.handle(), "number" to WorkspaceResultSchema.integer(), "name" to WorkspaceResultSchema.string())
    private val snapshot = WorkspaceResultSchema.obj(snapshotFields + ("values" to values))
    private val annotation = WorkspaceResultSchema.obj(mapOf("title" to WorkspaceResultSchema.string(), "note" to WorkspaceResultSchema.string(), "hidden" to WorkspaceResultSchema.boolean()))

    fun forOperation(id: String): JsonObject {
        val identity = WorkspaceResultSchema.identity
        return when (id) {
            "snapshot_create", "snapshot_update", "snapshot_get" -> WorkspaceResultSchema.obj(identity + ("snapshot" to snapshot))
            "snapshot_delete", "history_annotation_put", "history_annotation_delete" -> WorkspaceResultSchema.obj(identity)
            "snapshot_apply" -> WorkspaceResultSchema.obj(identity + pose)
            "history_annotation_get" -> WorkspaceResultSchema.obj(identity + mapOf("node_id" to WorkspaceResultSchema.handle(), "annotation" to WorkspaceResultSchema.nullable(annotation)))
            "snapshot_list" -> WorkspaceResultSchema.obj(identity + mapOf(
                "items" to WorkspaceResultSchema.array(WorkspaceResultSchema.obj(snapshotFields)), "total" to WorkspaceResultSchema.integer(0), "next" to WorkspaceResultSchema.integer(1)),
                identity.keys + setOf("items", "total"))
            else -> error("Auxiliary operation has no result schema: $id")
        }
    }
}

internal object WorkspaceCanvasVisibilityResultSchemas {
    private val flags = WorkspaceResultSchema.dictionary(WorkspaceResultSchema.boolean())
    val canvas = WorkspaceResultSchema.obj(linkedMapOf("workspace_id" to WorkspaceResultSchema.handle(),
        "canvas_id" to WorkspaceResultSchema.handle(), "mode" to WorkspaceResultSchema.choices("edit", "preview"),
        "layers" to flags, "deformers" to flags,
        "isolated_layer_id" to WorkspaceResultSchema.nullable(WorkspaceResultSchema.handle()),
        "isolation_snapshot" to WorkspaceResultSchema.nullable(flags),
        "hidden_layer_ids" to WorkspaceResultSchema.array(WorkspaceResultSchema.handle())))

    fun forOperation(id: String): JsonObject = when (id) {
        "canvas_visibility" -> WorkspaceResultSchema.obj(WorkspaceResultSchema.identity +
            mapOf("applied" to WorkspaceResultSchema.boolean(), "canvas" to canvas))
        "canvas_visibility_get" -> WorkspaceResultSchema.obj(WorkspaceResultSchema.identity +
            mapOf("items" to WorkspaceResultSchema.array(canvas)))
        else -> error("Canvas visibility operation has no result schema: $id")
    }
}
