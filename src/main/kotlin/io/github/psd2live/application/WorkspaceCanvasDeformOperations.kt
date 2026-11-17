package io.github.psd2live.application

import kotlinx.serialization.json.*

internal object WorkspaceCanvasDeformSchemas {
    private fun number(min: Float? = null, max: Float? = null) = buildJsonObject {
        put("type", "number"); min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }
    private fun choice(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private fun array(item: JsonObject, min: Int, max: Int) = buildJsonObject {
        put("type", "array"); put("items", item); put("minItems", min); put("maxItems", max)
    }
    private fun obj(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    private val boolean = buildJsonObject { put("type", "boolean") }
    private val point = array(number(), 2, 2)
    private val coordinates = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    private val target = obj(mapOf("target" to buildJsonObject {
        put("type", "string"); put("pattern", "^(mesh|warp):.+$")
    }, "key" to coordinates, "vertices" to array(buildJsonObject {
        put("type", "integer"); put("minimum", 0)
    }, 0, 65536)), listOf("target"))
    private val common = mapOf("mode" to choice("edit", "deform"), "targets" to array(target, 1, 64), "pose" to coordinates,
        "radius" to number(0.5f, 4096f), "hardness" to number(0f, 0.95f), "strength" to number(0f, 1f),
        "shape" to choice("circle", "line", "rectangle"), "angle" to number(), "aspect" to number(0.1f, 10f),
        "falloff" to choice("smooth", "sphere", "root", "inverse_square", "sharp", "linear", "constant", "random"),
        "connected_only" to boolean)
    private fun branch(action: String): JsonObject = obj(common + mapOf(
        "action" to buildJsonObject { put("type", "string"); put("const", action) },
        "samples" to array(obj(mapOf("point" to point, "preserve_children" to boolean) +
            (if (action == "brush") mapOf("smooth" to boolean) else emptyMap()), listOf("point")), 2, 4096)) +
        (if (action == "inflate") mapOf("shrink" to boolean) else emptyMap()), listOf("action", "mode", "targets", "samples", "radius"))
    val stroke = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(common + mapOf("action" to choice("brush", "smooth", "inflate"), "shrink" to boolean,
            "samples" to array(obj(mapOf("point" to point, "preserve_children" to boolean, "smooth" to boolean), listOf("point")), 2, 4096))))
        put("required", JsonArray(listOf("action", "mode", "targets", "samples", "radius").map(::JsonPrimitive)))
        put("oneOf", JsonArray(listOf("brush", "smooth", "inflate").map(::branch)))
    }
    val request = obj(mapOf("stroke" to stroke, "correction_mode" to choice("delta", "absolute"), "edit_stage" to buildJsonObject { put("type", "string") }), listOf("stroke"))
}

internal fun registerCanvasDeformOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort) {
    val s = WorkspaceResultSchema
    val business = WorkspaceCanvasDeformSchemas.request
    val schema = JsonObject(business + mapOf("properties" to JsonObject(business.getValue("properties").jsonObject + ("state" to s.handle())),
        "required" to JsonArray(listOf(JsonPrimitive("state"), JsonPrimitive("stroke")))))
    val output = s.obj(WorkspaceAuthoringResultSchemas.compactFields, s.identity.keys)
    registry.register(WorkspaceOperationDefinition("canvas_deform_stroke",
        "Brush, smooth or inflate current meshes or one Warp using the same captured canvas gesture as the UI. Samples and radius are canvas pixels (X right, Y down) at pose. The first sample captures geometry and tip coverage; brush displacement is measured from that press, while smooth and inflate accumulate per segment. connected_only starts from the component under the pointer across the full mesh set. Omit vertices to affect all, or supply current vertex indices. Glue partners follow touched welded vertices. edit shifts rest geometry and preserves the image; deform writes the exact key at pose and retains other keyforms and channels. Include all bound axes in key, and at most one active blend shape. Warp preserve_children applies the UI Ctrl behavior. The complete stroke is one atomic document candidate and one history node.",
        schema, WorkspaceOperationKind.DOCUMENT, batchable = true, resultSchema = output)) { request, context ->
        val operation = WorkspaceDocumentOperation("canvas_deform_stroke", JsonObject(request - "state"))
        validateOperationSchema(operation.request, business)
        WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
            "Deformed canvas geometry", listOf(operation), context.author).compact())
    }
}
