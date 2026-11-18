package io.github.psd2live.application

import kotlinx.serialization.json.*
import io.github.psd2live.core.CanvasWeightStroke

internal object WorkspaceCanvasWeightSchemas {
    private fun text() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    private fun number(min: Float? = null, max: Float? = null) = buildJsonObject {
        put("type", "number"); min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }
    private fun choice(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private fun array(item: JsonObject, min: Int = 0, max: Int = 65536) = buildJsonObject {
        put("type", "array"); put("items", item); put("minItems", min); put("maxItems", max)
    }
    private fun obj(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    private val point = array(number(), 2, 2)
    private val pose = buildJsonObject { put("type", "object"); put("additionalProperties", number()) }
    val glue = obj(mapOf(
        "action" to choice("brush", "weights", "unglue", "remerge"), "id" to text(), "mesh_a" to text(), "mesh_b" to text(),
        "hits_a" to array(buildJsonObject { put("type", "integer"); put("minimum", 0) }),
        "hits_b" to array(buildJsonObject { put("type", "integer"); put("minimum", 0) }),
        "distance" to buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0) },
        "weight_mode" to choice("a", "b", "balance"), "delta" to number(-1f, 1f)), listOf("action", "mesh_a", "mesh_b"))
    private val common = mapOf("targets" to array(text(), 1, 64), "kind" to choice("pin", "stiffness", "mass", "damping", "wind", "goal"),
        "name" to text(), "pose" to pose, "mode" to choice("add", "subtract", "set", "smooth"), "strength" to number(0f, 1f))
    private fun variant(action: String, fields: Map<String, JsonObject>, required: List<String>) = obj(common + fields +
        ("action" to buildJsonObject { put("type", "string"); put("const", action) }), listOf("action", "targets", "kind") + required)
    val weights = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        val brushFields = mapOf("points" to array(point, 1, CanvasWeightStroke.POINTS_PER_BATCH),
            "point_batches" to array(array(point, 1, CanvasWeightStroke.POINTS_PER_BATCH), 1, CanvasWeightStroke.MAX_BATCHES), "radius" to number(0.5f, 4096f), "hardness" to number(0f, 0.95f),
            "shape" to choice("circle", "line", "rectangle"), "angle" to number(), "aspect" to number(0.1f, 10f),
            "falloff" to choice("smooth", "sphere", "root", "inverse_square", "sharp", "linear", "constant", "random"),
            "connected_only" to buildJsonObject { put("type", "boolean") })
        // Outer inventory permits fields; branches still strictly delimit each action's fields.
        put("properties", JsonObject(common + brushFields + mapOf("from" to point, "to" to point, "action" to choice("brush", "gradient", "invert"))))
        put("required", JsonArray(listOf("action", "targets", "kind").map(::JsonPrimitive)))
        put("oneOf", JsonArray(listOf(variant("brush", brushFields - "point_batches", listOf("points", "radius")),
            variant("brush", brushFields - "points", listOf("point_batches", "radius")),
            variant("gradient", mapOf("from" to point, "to" to point), listOf("from", "to")),
            variant("invert", emptyMap(), emptyList()))))
    }
    fun request(id: String): JsonObject = when (id) {
        "canvas_glue_edit" -> glue
        "vertex_group_paint" -> obj(mapOf("edit" to weights, "auto_bake" to buildJsonObject { put("type", "boolean") }), listOf("edit"))
        else -> error("Unknown canvas weight operation: $id")
    }
}

/** Public callers and completed GUI gestures enter the same candidate document operations. */
internal fun registerCanvasWeightOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort) {
    val s = WorkspaceResultSchema
    val output = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle(), 1, Int.MAX_VALUE)), s.identity.keys)
    val descriptions = mapOf(
        "canvas_glue_edit" to "Edit the canvas Glue seam using selected current vertex indices: brush extends its welds, remerge rebuilds selected welds (empty selections rebuild both outlines), unglue removes selected welds, and weights paints directional A/B or balanced pull by delta. Every continuous Glue record on the unordered mesh pair retains its own animation channels and order when weights or selected welds are removed. Welds are made where both meshes rest (the default pose, as Cubism's glue), so a weld does nothing at rest; index validation is against the captured candidate. Later atomic batch members see the edited topology.",
        "vertex_group_paint" to "Paint or invert simulation vertex weights using the canvas algorithms. Targets are current mesh IDs. Brush points and radius use canvas pixels (X right, Y down) at pose; shape, falloff, angle and aspect match the UI tip. connected_only confines the reach to the mesh component under each segment. A stroke uses maximum coverage before add/subtract/set or four smoothing passes. Long gestures use point_batches (up to 256 arrays of 4096 points); adjacent batches remain one continuous stroke and one undo operation. Coordinates may lie outside the canvas. auto_bake defaults to false; the previous bake stays active until Bake updates it using the painted weights. Gradient reaches 1 at from and 0 at to. Omit name to edit the first group of kind or allocate its unique default name. invert skips meshes without that group. Groups remain editor metadata; their baked simulation affects exported models.")
    for (id in WorkspaceCanvasWeightEdits.supported) {
        val business = WorkspaceCanvasWeightSchemas.request(id)
        val properties = business.getValue("properties").jsonObject + ("state" to s.handle())
        // Keep discriminated branches under edit, so batch context removal preserves them exactly.
        val schema = JsonObject(business + mapOf("properties" to JsonObject(properties),
            "required" to JsonArray(listOf(JsonPrimitive("state")) + business.getValue("required").jsonArray)))
        registry.register(WorkspaceOperationDefinition(id, descriptions.getValue(id), schema, WorkspaceOperationKind.DOCUMENT,
            batchable = true, resultSchema = output)) { request, context ->
            val operation = WorkspaceDocumentOperation(id, JsonObject(request - "state"))
            validateOperationSchema(operation.request, business)
            WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
                "Edited canvas weights", listOf(operation), context.author).compact())
        }
    }
}
