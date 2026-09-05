package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.util.Base64

internal fun registerAssetWorkflowTools(server: Server, workspace: AgentWorkspace) {
    val descriptions = mapOf(
        "asset_prepare_reference" to "Prepare a persistent generation reference from a SOURCE layer: clean image and separately annotated context, canvas mapping, target anchors, existing parent Warp and chosen solid matte. Generated images should use this matte, not claim transparency. Anchor x/y are source canvas coordinates; source_canvas_rect is optional crop. Images are returned clean first, annotated context second.",
        "asset_register" to "Create an immutable placement instance without changing pixels or model. mode=frame maps declared generated_pixel_rect to source_canvas_rect (defaults to complete PNG/reference); mode=landmarks fits generated_anchors (PNG pixel coordinates) to target_anchors (canvas); mode=absolute sets transform. x/y locate the original PNG origin, not its alpha crop. Reflection requires mirror_x/mirror_y, unequal scales require allow_stretch. Returns actual transformed raster and orientation/fit diagnostics; preview composite next.",
        "asset_preview_composite" to "Preview placed assets in source-canvas context without adding layers or affecting rig analysis. placements are in painter order, each with registration_id, optional insertion=top/bottom/above/below and reference_layer_id. Explicit replace_layer_ids exclude original source to avoid false duplication. Natural overlap is expected. Returns source-raster preview, not a posed inverse transform.",
        "asset_reprocess" to "Reprocess a saved ORIGINAL generated PNG using actual solid_background, tolerance and optional foreground/background seed points plus edge_width in original PNG pixels. Returns a new immutable processed asset; register it again. Never resamples the already placed layer.",
    )
    for ((name, description) in descriptions) server.addTool(
        name=name, description=description, inputSchema=assetWorkflowSchema(name),
        toolAnnotations=ToolAnnotations(readOnlyHint=name=="asset_preview_composite",destructiveHint=false,idempotentHint=name=="asset_preview_composite",openWorldHint=false),
    ) { request ->
        try {
            val result=workspace.assetWorkflow(name,request.arguments ?: JsonObject(emptyMap()))
            CallToolResult(content=listOf(TextContent(result.metadata.toString()))+result.images.map { ImageContent(Base64.getEncoder().encodeToString(it),"image/png") },structuredContent=result.metadata)
        } catch(e: IllegalArgumentException) { workflowError(e) } catch(e: IllegalStateException) { workflowError(e) }
    }
    for (name in listOf("layer_set_placement","layer_finalize_placement")) server.addTool(
        name=name,
        description=if(name=="layer_set_placement") "Reposition an imported layer from an absolute registration instance, retaining its existing parent Warp and inherited motion. No unbound layer is created. Dedicated Warp/keyforms or finalized placement are protected. Uses original processed pixels, not a previous resampling. Requires current history HEAD."
            else "Mark registered placement complete while retaining the existing parent Warp and inherited animation. Rebuild and verify neutral placement before commit. Then author optional dedicated Warp/physics. Does not unbind or move existing rigged subtrees.",
        inputSchema=ToolSchema(properties=buildJsonObject {
            for(key in listOf("layer_id","registration_id","expected_history_head_node_id","task_id")) putJsonObject(key) { put("type","string") }
        },required=listOf("layer_id","expected_history_head_node_id")+if(name=="layer_set_placement") listOf("registration_id") else emptyList()),
        toolAnnotations=ToolAnnotations(readOnlyHint=false,destructiveHint=false,idempotentHint=false,openWorldHint=false),
    ) { request ->
        try {
            val a=request.arguments ?: JsonObject(emptyMap())
            val result=if(name=="layer_set_placement") workspace.setLayerPlacement(a.text("layer_id"),a.text("registration_id"),a.text("expected_history_head_node_id"),a["task_id"]?.jsonPrimitive?.content)
                else workspace.finalizeLayerPlacement(a.text("layer_id"),a.text("expected_history_head_node_id"),a["task_id"]?.jsonPrimitive?.content)
            val json=result.toJson()
            CallToolResult(content=listOf(TextContent(json.toString())),structuredContent=json)
        } catch(e: IllegalArgumentException) { workflowError(e) } catch(e: IllegalStateException) { workflowError(e) }
    }
}

private fun workflowError(e: Exception) = CallToolResult(content=listOf(TextContent(e.message ?: "Invalid workflow request")),isError=true)

internal fun processingSchema(): JsonObject = buildJsonObject {
    put("type","object")
    putJsonObject("properties") {
        putJsonObject("edge_width") { put("type","integer");put("minimum",0);put("maximum",8);put("default",3) }
        for(key in listOf("foreground_points","background_points")) putJsonObject(key) {
            put("type","array"); put("description","Hints in ORIGINAL generated PNG pixel coordinates, before registration/cropping")
            put("items",pointSchema())
        }
    }
}
private fun pointSchema() = buildJsonObject { put("type","object");putJsonObject("properties") {
    putJsonObject("x") { put("type","number") };putJsonObject("y") { put("type","number") }
};putJsonArray("required") { add(JsonPrimitive("x"));add(JsonPrimitive("y")) } }
private fun rectSchema() = buildJsonObject { put("type","object");putJsonObject("properties") {
    for(key in listOf("left","top","width","height")) putJsonObject(key) { put("type","number") }
};putJsonArray("required") { listOf("left","top","width","height").forEach { add(JsonPrimitive(it)) } } }
private fun assetWorkflowSchema(name: String): ToolSchema {
    val fields=buildJsonObject {
        val strings=when(name) {
            "asset_prepare_reference" -> listOf("layer_id","piece_id","background_color","occlusion")
            "asset_register" -> listOf("asset_id","reference_id","mode")
            "asset_reprocess" -> listOf("asset_id","solid_background")
            else -> listOf("background_color")
        }
        strings.forEach { key -> putJsonObject(key) { put("type","string") } }
        when(name) {
            "asset_prepare_reference","asset_register" -> {
                putJsonObject("target_anchors") { put("type","object");put("additionalProperties",pointSchema());put("description","Named root/tip/side landmarks in SOURCE CANVAS coordinates") }
                put("source_canvas_rect",rectSchema())
            }
        }
        if(name=="asset_prepare_reference" || name=="asset_preview_composite") putJsonObject("target_long_edge") { put("type","integer");put("minimum",64);put("maximum",4096) }
        if(name=="asset_register") {
            put("generated_pixel_rect",rectSchema())
            putJsonObject("generated_anchors") { put("type","object");put("additionalProperties",pointSchema());put("description","Named points in the FULL original generated PNG, before transparent crop; must match target anchor names") }
            for(key in listOf("mirror_x","mirror_y","allow_stretch")) putJsonObject(key) { put("type","boolean");put("default",false) }
            putJsonObject("transform") { put("type","object");putJsonObject("properties") {
                for(key in listOf("x","y","scale_x","scale_y","rotation_degrees")) putJsonObject(key) { put("type","number") }
                for(key in listOf("mirror_x","mirror_y")) putJsonObject(key) { put("type","boolean") }
            };putJsonArray("required") { listOf("x","y","scale_x").forEach { add(JsonPrimitive(it)) } } }
        }
        if(name=="asset_reprocess") {
            put("processing",processingSchema());putJsonObject("background_tolerance") { put("type","integer");put("minimum",0);put("maximum",64) }
        }
        if(name=="asset_preview_composite") {
            put("source_canvas_rect",rectSchema())
            putJsonObject("replace_layer_ids") { put("type","array");putJsonObject("items") { put("type","string") } }
            putJsonObject("placements") { put("type","array");put("minItems",1);putJsonObject("items") {
                put("type","object");putJsonObject("properties") { for(key in listOf("registration_id","insertion","reference_layer_id")) putJsonObject(key) { put("type","string") } }
                putJsonArray("required") { add(JsonPrimitive("registration_id")) }
            } }
        }
    }
    return ToolSchema(properties=fields,required=when(name) {
        "asset_prepare_reference" -> listOf("layer_id","piece_id","background_color","target_anchors")
        "asset_register" -> listOf("asset_id","mode")
        "asset_reprocess" -> listOf("asset_id")
        else -> listOf("placements","replace_layer_ids")
    })
}
