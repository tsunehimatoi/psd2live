package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal fun objectEditSchema() = ToolSchema(properties = buildJsonObject {
    putJsonObject("expected_history_head_node_id") { put("type", "string") }
    putJsonObject("task_id") { put("type", "string") }
    putJsonObject("edits") {
        put("type", "array"); put("minItems", 1); put("maxItems", 128)
        putJsonObject("items") {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") {
                for((key, values) in mapOf("action" to listOf("rename", "visibility", "move", "bind"),
                    "kind" to listOf("mesh", "warp", "rotation", "part"), "before_kind" to listOf("mesh", "part"), "space" to listOf("local"))) {
                    putJsonObject(key) { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
                }
                for(key in listOf("id", "name")) putJsonObject(key) { put("type", "string") }
                for(key in listOf("parent_id", "before_id")) putJsonObject(key) { put("type", JsonArray(listOf("string", "null").map(::JsonPrimitive))) }
                putJsonObject("visible") { put("type", "boolean") }
            }
            put("required", JsonArray(listOf("action", "kind", "id").map(::JsonPrimitive)))
            put("description", "rename: name. visibility: visible (mesh/part). move: parent_id (null=root), optional before_id and before_kind for organizational siblings. bind: mesh to deformer parent_id. Deformer move and mesh bind require space=local, retaining local keys but changing inherited appearance. Organizational moves leave deformation unchanged.")
        }
    }
}, required = listOf("edits", "expected_history_head_node_id"))
