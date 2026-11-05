package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceDocument

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.geom.Path2D
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

internal fun registerSourceCommands(catalog: WorkspaceCommands, workspace: WorkspaceSourcePort) {
    val text = buildJsonObject { put("type", "string") }
    val number = buildJsonObject { put("type", "number") }
    val point = buildJsonObject { put("type", "array"); put("items", number); put("minItems", 2); put("maxItems", 2) }
    val source = buildJsonObject {
        put("type", "object"); putJsonObject("properties") {
            listOf("path", "name").forEach { put(it, text) }
            putJsonObject("role") { put("type", "string"); put("enum", JsonArray(SemanticTag.entries.map { JsonPrimitive(it.name.lowercase()) })) }
            putJsonObject("side") { put("type", "string"); put("enum", JsonArray(Side.entries.map { JsonPrimitive(it.name.lowercase()) })) }
            for (coordinate in listOf("x", "y")) putJsonObject(coordinate) {
                put("type", "integer"); put("minimum", Int.MIN_VALUE); put("maximum", Int.MAX_VALUE)
                put("description", "Layer origin in canvas pixels")
            }
        }; putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("name")) }; put("additionalProperties", false)
    }
    val specs = mapOf(
        "asset_import_psd" to WorkspaceCommandSchema(properties = buildJsonObject {
            put("path", text)
            putJsonObject("discard_unsaved") { put("type", "boolean") }
            put("modifiers", WorkspaceResultSchema.choices("recommended", "empty"))
        }, required = listOf("path")),
        "asset_create_artwork" to WorkspaceCommandSchema(properties = buildJsonObject {
            putJsonObject("discard_unsaved") { put("type", "boolean") }
            putJsonObject("width") { put("type", "integer"); put("minimum", 1); put("maximum", 8192) }
            putJsonObject("height") { put("type", "integer"); put("minimum", 1); put("maximum", 8192) }
            putJsonObject("layers") { put("type", "array"); put("items", source); put("minItems", 1); put("maxItems", 32) }
        }, required = listOf("width", "height", "layers")),
        "asset_split_artwork" to WorkspaceCommandSchema(properties = buildJsonObject {
            put("state", text); put("layer_id", text)
            putJsonObject("polygon") { put("type", "array"); put("items", point); put("minItems", 3); put("maxItems", 32) }
            putJsonObject("names") { put("type", "array"); put("items", text); put("minItems", 2); put("maxItems", 2) }
            putJsonObject("piece_ids") { put("type", "array"); put("items", text); put("minItems", 2); put("maxItems", 2); put("uniqueItems", true) }
        }, required = listOf("state", "layer_id", "polygon", "names")),
        "asset_split_components" to WorkspaceCommandSchema(properties = buildJsonObject {
            put("state", text); put("layer_id", text)
            putJsonObject("names") { put("type", "array"); put("items", text); put("minItems", 2); put("uniqueItems", true) }
            putJsonObject("piece_ids") { put("type", "array"); put("items", text); put("minItems", 2); put("uniqueItems", true) }
            putJsonObject("sides") { put("type", "array"); put("minItems", 2)
                putJsonObject("items") { put("type", "string"); put("enum", JsonArray(Side.entries.map { JsonPrimitive(it.name.lowercase()) })) } }
        }, required = listOf("state", "layer_id", "names")),
        "asset_split_depth" to WorkspaceCommandSchema(properties = buildJsonObject {
            put("state", text)
            putJsonObject("source_id") { put("type", "string"); put("minLength", 1); put("description", "Raw drawable ID of the mesh to slice; inspect objects first") }
            putJsonObject("middle_ids") { put("type", "array"); put("items", text); put("minItems", 1); put("uniqueItems", true)
                put("description", "Raw IDs of meshes to place between the rear and new front slices") }
            for (field in listOf("front_layer_id", "front_mesh_id", "back_layer_id", "back_mesh_id", "glue_id")) putJsonObject(field) {
                put("type", "string"); put("minLength", 1); put("description", "Optional unique new ID; omit to allocate one")
            }
            putJsonObject("names") { put("type", "array"); put("items", text); put("minItems", 2); put("maxItems", 2); put("uniqueItems", true)
                put("description", "Optional names in rear, front order; otherwise use the source name with back/front suffixes") }
        }, required = listOf("state", "source_id", "middle_ids"))
    )
    for ((name, schema) in specs) catalog.register(name, if (name == "asset_split_depth")
        "Split one authored mesh into independently paintable back and front slices joined by directional Glue, with middle meshes between them. The source mesh and its layer are replaced by the two new slices (new layer and mesh IDs; back_* fields name the back), which carry its channels, keyforms, blend shapes, paths and weights; the back takes its Glues, masks and simulation targets, and the front follows the back. Draw-order animation is replaced by fixed slice order. Only undo returns to the source; later references to its IDs fail naming the slices. Imported CMO3 models are unsupported. IDs are raw, without mesh: prefixes."
        else if (name == "asset_split_artwork" || name == "asset_split_components")
            "Split a source layer into parts (by a canvas polygon, or by its current mesh islands as source_get_components lists them). The parts replace the layer: each becomes its own source layer and mesh carrying the original's keyforms, channels, blend shapes, paths, weights, Glue and simulation targets at this point, and the original leaves the source art. layer_restore cannot bring it back; only undo returns to it, and later references to its IDs fail naming the parts. On an imported CMO3 model the original is soft-deleted instead."
        else "Generic source artwork editing", schema,
        hints = WorkspaceCommandHints(readOnlyHint = false, destructiveHint = false, openWorldHint = false)) { request ->
        val arguments = request.arguments
        val result = when (name) {
            "asset_import_psd" -> workspace.importPsd(arguments.text("path"), arguments["discard_unsaved"]?.jsonPrimitive?.boolean ?: false,
                when (arguments["modifiers"]?.jsonPrimitive?.content) {
                    null, "recommended" -> WorkspaceInitialModifiers.RECOMMENDED
                    "empty" -> WorkspaceInitialModifiers.EMPTY
                    else -> error("Unknown initial modifier group")
                })
            "asset_create_artwork" -> workspace.createArtwork(arguments)
            "asset_split_components" -> workspace.splitMeshComponents(arguments.text("state"), listOf(JsonObject(arguments - "state")))
            "asset_split_depth" -> workspace.splitDepth(arguments.text("state"), JsonObject(arguments - "state"))
            else -> workspace.splitArtwork(arguments)
        }
        WorkspaceOperationOutput(result.sourceResult())
    }
}

internal fun io.github.psd2live.project.WorkspaceMutationResult.sourceResult(): JsonObject = JsonObject(lifecycleResult() +
    ("layers" to JsonArray(affectedLayerIds.map(::JsonPrimitive))))
