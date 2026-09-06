package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

internal fun rigGeometrySchema(edit: Boolean): ToolSchema = ToolSchema(
    properties=buildJsonObject {
        putJsonObject("target") {
            put("type","object");put("additionalProperties",false)
            putJsonObject("properties") {
                putJsonObject("kind") { put("type","string");put("enum",JsonArray(listOf("warp","mesh").map(::JsonPrimitive))) }
                putJsonObject("id") { put("type","string") }
            };put("required",JsonArray(listOf("kind","id").map(::JsonPrimitive)))
        }
        putJsonObject("coordinate") { put("type","object");if(edit)put("minProperties",1);putJsonObject("additionalProperties") { put("type","number") };put("description","Parameter pose; edits require at least one parameter and every bound geometry axis. Other parameters default.") }
        if(!edit) {
            putJsonObject("detail") { put("type","string");put("enum",JsonArray(listOf("summary","points").map(::JsonPrimitive)));put("default","summary") }
            putJsonObject("space") { put("type","string");put("enum",JsonArray(listOf("local","canvas").map(::JsonPrimitive)));put("default","local") }
            putJsonObject("offset") { put("type","integer");put("minimum",0) }
            putJsonObject("limit") { put("type","integer");put("minimum",1);put("maximum",256);put("default",64) }
        } else {
            putJsonObject("expected_history_head_node_id") { put("type","string") }
            putJsonObject("task_id") { put("type","string") }
            put("selection", geometrySelectionSchema())
            putJsonObject("range") {
                put("type","object");put("additionalProperties",false)
                putJsonObject("properties") {
                    putJsonObject("radius") { put("type","number");put("exclusiveMinimum",0) }
                    putJsonObject("feather") { put("type","number");put("minimum",0);put("maximum",0.5) }
                }
                put("description","Shared influence range. Each operation uses shared selection + range unless it supplies a complete selection override.")
            }
            putJsonObject("operations") {
                put("type","array");put("minItems",1);put("maxItems",32)
                putJsonObject("items") {
                    put("type","object");put("additionalProperties",false)
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type","string");put("enum",JsonArray(listOf("translate","scale","rotate","bend","curve","smooth").map(::JsonPrimitive))) }
                        for((key,size) in mapOf("delta" to 2,"pivot" to 2,"factors" to 2,"controls" to 4)) {
                            putJsonObject(key) { put("type","array");put("minItems",size);put("maxItems",size);putJsonObject("items") { put("type","number") } }
                        }
                        putJsonObject("axis") { put("type","string");put("enum",JsonArray(listOf("x","y").map(::JsonPrimitive))) }
                        for(key in listOf("amount","degrees","strength"))putJsonObject(key) { put("type","number") }
                        put("selection", geometrySelectionSchema())
                    }
                    put("required",JsonArray(listOf(JsonPrimitive("type"))))
                    put("description","Ordered, relative to each operation input bounds. delta/amount/curve are fractions of width/height; pivot is normalized. rotate degrees positive clockwise in local Y-down units. smooth pins boundary.")
                }
            }
        }
    }, required=if(edit)listOf("target","coordinate","operations","expected_history_head_node_id") else listOf("target"),
)

internal fun loadRigGeometryWorkflow(): String = requireNotNull(AgentWorkspace::class.java.classLoader.getResourceAsStream("agent/skills/rig-geometry.md"))
    .bufferedReader().use { it.readText() }

private fun geometrySelectionSchema(): JsonObject = buildJsonObject {
                            put("type","object");put("additionalProperties",false)
                            putJsonObject("properties") {
                                for((key,size) in mapOf("rect" to 4,"center" to 2,"line" to 4))putJsonObject(key) { put("type","array");put("minItems",size);put("maxItems",size);putJsonObject("items") { put("type","number") } }
                                putJsonObject("indices") { put("type","array");put("maxItems",512);putJsonObject("items") { put("type","integer");put("minimum",0) } }
                                putJsonObject("radius") { put("type","number");put("exclusiveMinimum",0) }
                                putJsonObject("feather") { put("type","number");put("minimum",0);put("maximum",0.5) }
                            }
                            put("description","Stable normalized rest domain: rect=[left,top,right,bottom], indices, center+radius, or line=[x0,y0,x1,y1]+radius. Smooth radial falloff; optional rectangle feather.")
}
