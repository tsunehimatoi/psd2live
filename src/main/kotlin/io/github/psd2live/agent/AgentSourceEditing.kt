package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.geom.Path2D
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

internal fun registerSourceEditing(server: Server, workspace: AgentWorkspace) {
    val text = buildJsonObject { put("type", "string") }
    val number = buildJsonObject { put("type", "number") }
    val point = buildJsonObject { put("type", "array"); put("items", number); put("minItems", 2); put("maxItems", 2) }
    val source = buildJsonObject {
        put("type", "object"); putJsonObject("properties") {
            listOf("path", "name", "role", "side").forEach { put(it, text) }
            put("x", number); put("y", number)
        }; putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("name")) }; put("additionalProperties", false)
    }
    val specs = mapOf(
        "asset_import_psd" to ToolSchema(properties = buildJsonObject { put("path", text) }, required = listOf("path")),
        "asset_create_artwork" to ToolSchema(properties = buildJsonObject {
            putJsonObject("width") { put("type", "integer"); put("minimum", 1); put("maximum", 8192) }
            putJsonObject("height") { put("type", "integer"); put("minimum", 1); put("maximum", 8192) }
            putJsonObject("layers") { put("type", "array"); put("items", source); put("minItems", 1); put("maxItems", 32) }
        }, required = listOf("width", "height", "layers")),
        "asset_split_artwork" to ToolSchema(properties = buildJsonObject {
            put("state", text); put("layer_id", text)
            putJsonObject("polygon") { put("type", "array"); put("items", point); put("minItems", 3); put("maxItems", 32) }
            putJsonObject("names") { put("type", "array"); put("items", text); put("minItems", 2); put("maxItems", 2) }
        }, required = listOf("state", "layer_id", "polygon", "names"))
    )
    for ((name, schema) in specs) server.addTool(name, "Generic source artwork editing", schema,
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)) { request ->
        val arguments = request.arguments ?: JsonObject(emptyMap())
        val result = when (name) {
            "asset_import_psd" -> workspace.importPsd(arguments.text("path"))
            "asset_create_artwork" -> workspace.createArtwork(arguments)
            else -> workspace.splitArtwork(arguments)
        }
        val value = buildJsonObject {
            put("state", result.historyNodeId)
            put("layers", JsonArray(result.affectedLayerIds.map(::JsonPrimitive)))
        }
        CallToolResult(content = listOf(TextContent(value.toString())), structuredContent = value)
    }
}

internal fun sourceArtwork(arguments: JsonObject): Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> {
    val width = arguments.getValue("width").jsonPrimitive.int; val height = arguments.getValue("height").jsonPrimitive.int
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Canvas exceeds 16 megapixels" }
    val files = arguments.getValue("layers").jsonArray
    require(files.size in 1..32)
    val overrides = mutableMapOf<String, LayerClassificationOverride>()
    var pixels = 0L
    val layers = files.mapIndexed { index, entry ->
        val a = entry.jsonObject
        val file = Path.of(a.text("path"))
        require(file.isAbsolute && Files.isRegularFile(file) && Files.size(file) in 8..67_108_864) { "Invalid local PNG path or file budget" }
        val bytes = Files.readAllBytes(file)
        require(bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte)) { "Artwork requires PNG" }
        val image = requireNotNull(ImageIO.read(bytes.inputStream())) { "Cannot decode PNG" }
        pixels += image.width.toLong() * image.height
        require(pixels <= 33_554_432) { "Artwork exceeds total raster budget" }
        val rgba = ByteArray(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val value = image.getRGB(x, y); val offset = (y * image.width + x) * 4
            rgba[offset] = (value ushr 16).toByte(); rgba[offset + 1] = (value ushr 8).toByte()
            rgba[offset + 2] = value.toByte(); rgba[offset + 3] = (value ushr 24).toByte()
        }
        val id = "artwork:${UUID.randomUUID()}"
        a["role"]?.jsonPrimitive?.content?.let { role -> overrides[id] = LayerClassificationOverride(type = LayerType.PRESET,
            tag = enumValueOf<SemanticTag>(role.uppercase()), side = enumValueOf<Side>((a["side"]?.jsonPrimitive?.content ?: "none").uppercase())) }
        WorkspaceSourceLayer(LayerId(id), a.text("name"), "", SourceLayerKind.Raster, true, files.lastIndex - index,
            LayerBounds(a["x"]?.jsonPrimitive?.int ?: 0, a["y"]?.jsonPrimitive?.int ?: 0, image.width, image.height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(image.width, image.height, rgba), null, null, false)
    }
    return WorkspaceSourceArt(width, height, layers, emptyList()) to overrides
}

/** A binary source partition preserves original RGBA exactly; it does not invent hidden artwork. */
internal fun AgentWorkspaceDocument.splitSource(arguments: JsonObject): Pair<AgentWorkspaceDocument, List<String>> {
    val id = arguments.text("layer_id")
    val layer = source.layers.singleOrNull { it.id.raw == id && id !in deletedLayerIds } ?: error("Source layer not found")
    require(!layer.clipped && layer.blend == LayerBlend.Normal && layer.channelMask == ChannelMask.ALL) { "Split requires a normal, unmasked source layer" }
    val polygon = arguments.getValue("polygon").jsonArray.map { point -> point.jsonArray.map { it.jsonPrimitive.double } }
    require(polygon.size in 3..32 && polygon.all { it.size == 2 && it.all(Double::isFinite) })
    val names = arguments.getValue("names").jsonArray.map { it.jsonPrimitive.content }
    require(names.size == 2 && names.all { it.isNotBlank() })
    val shape = Path2D.Double()
    shape.moveTo(polygon.first()[0], polygon.first()[1]); polygon.drop(1).forEach { shape.lineTo(it[0], it[1]) }; shape.closePath()
    val sourceRaster = layer.raster
    val selected = sourceRaster.rgba.copyOf(); val remainder = sourceRaster.rgba.copyOf()
    var selectedPixels = 0; var remainingPixels = 0
    for (y in 0 until sourceRaster.height) for (x in 0 until sourceRaster.width) {
        val inside = shape.contains(layer.bounds.left + (x + 0.5) * layer.bounds.width / sourceRaster.width,
            layer.bounds.top + (y + 0.5) * layer.bounds.height / sourceRaster.height)
        val alpha = (y * sourceRaster.width + x) * 4 + 3
        if (inside) { remainder[alpha] = 0; if (selected[alpha].toInt() != 0) selectedPixels++ }
        else { selected[alpha] = 0; if (remainder[alpha].toInt() != 0) remainingPixels++ }
    }
    require(selectedPixels > 0 && remainingPixels > 0) { "Polygon must separate two nonempty painted regions" }
    val pieces = listOf(selected, remainder).mapIndexed { index, rgba ->
        (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            id = LayerId("split:${UUID.randomUUID()}"), name = names[index],
            raster = LayerRaster(sourceRaster.width, sourceRaster.height, rgba), sourceAssetId = null, sourceSpatialReferenceId = null, derived = true)
    }
    val ids = pieces.map { it.id.raw }
    val all = source.layers.flatMap { if (it.id.raw == id) listOf(it) + pieces else listOf(it) }
        .mapIndexed { index, item -> WorkspaceSourceLayer.copyOf(item, source.layers.size + 1 - index) }
    return copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, all, source.groups),
        deletedLayerIds = deletedLayerIds + id, layerVisibility = layerVisibility + (id to false) + ids.associateWith { true },
        layerOverrides = layerOverrides + ids.mapNotNull { newId -> layerOverrides[id]?.let { newId to it } }.toMap(),
        parentOverrides = parentOverrides + ids.mapNotNull { newId -> parentOverrides[id]?.let { newId to it } }.toMap()) to ids
}
