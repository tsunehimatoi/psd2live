package io.github.psd2live.agent

import androidx.compose.ui.graphics.Color
import io.github.psd2live.ui.LayerPaintEngine
import io.github.psd2live.ui.PaintShape
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import java.awt.image.BufferedImage

/** Paints in canvas pixels with the same raster engine as the interactive paint tools. */
internal fun AgentWorkspaceDocument.paintSource(arguments: JsonObject): AgentWorkspaceDocument {
    val id = arguments.getValue("layer_id").jsonPrimitive.content
    val layer = source.layers.singleOrNull { it.id.raw == id && id !in deletedLayerIds }
        ?: throw IllegalArgumentException("Source layer not found: $id")
    // The analyzer cannot construct a rig from an entirely transparent source. Preserve its
    // pixels for history restoration and remove it from generation, as the layer delete path does.
    if (arguments.getValue("mode").jsonPrimitive.content == "clear") {
        require(source.layers.any { it.id.raw != id && it.id.raw !in deletedLayerIds }) {
            "Cannot clear the last active source layer; the analyzer requires artwork"
        }
        return copy(deletedLayerIds = deletedLayerIds + id)
    }
    require(source.widthPx.toLong() * source.heightPx <= 16_777_216) { "Painting requires a canvas of at most 16 megapixels" }
    val width = source.widthPx
    val height = source.heightPx
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val raster = BufferedImage(layer.raster.width, layer.raster.height, BufferedImage.TYPE_INT_ARGB)
    val original = layer.raster.rgba
    for (i in 0 until layer.raster.width * layer.raster.height) {
        val at = i * 4
        raster.setRGB(i % layer.raster.width, i / layer.raster.width,
            ((original[at + 3].toInt() and 255) shl 24) or
                ((original[at].toInt() and 255) shl 16) or
                ((original[at + 1].toInt() and 255) shl 8) or
                (original[at + 2].toInt() and 255))
    }
    val graphics = image.createGraphics()
    try {
        graphics.drawImage(raster, layer.bounds.left, layer.bounds.top,
            layer.bounds.width, layer.bounds.height, null)
    } finally { graphics.dispose() }
    val mode = arguments.getValue("mode").jsonPrimitive.content
    val rgba = arguments["color"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(0, 0, 0, 255)
    require(rgba.size == 4 && rgba.all { it in 0..255 }) { "color must be four RGBA bytes" }
    val color = Color(rgba[0], rgba[1], rgba[2], rgba[3])
    val opacity = arguments["opacity"]?.jsonPrimitive?.float ?: 1f
    require(opacity.isFinite() && opacity in 0f..1f) { "opacity must be 0..1" }
    fun point(value: JsonElement): Pair<Float, Float> {
        val numbers = value.jsonArray.map { it.jsonPrimitive.float }
        require(numbers.size == 2 && numbers.all(Float::isFinite)) { "point must be [x,y]" }
        return numbers[0] to numbers[1]
    }
    when (mode) {
        "brush", "eraser" -> {
            val points = arguments.getValue("points").jsonArray.map(::point)
            require(points.size in 1..512) { "Use 1..512 stroke points" }
            val radius = arguments["radius"]?.jsonPrimitive?.float ?: 8f
            val hardness = arguments["hardness"]?.jsonPrimitive?.float ?: 1f
            require(radius.isFinite() && radius in 0.5f..512f && hardness.isFinite() && hardness in 0f..1f)
            val stroke = LayerPaintEngine.Stroke(width, height)
            val tip = LayerPaintEngine.Tip(radius, hardness)
            if (points.size == 1) stroke.addSegment(points[0].first, points[0].second,
                points[0].first, points[0].second, tip)
            else points.zipWithNext().forEach { (a, b) -> stroke.addSegment(a.first, a.second, b.first, b.second, tip) }
            stroke.bounds?.let { stroke.land(image, color, opacity, mode == "eraser", it) }
        }
        "bucket" -> {
            val (x, y) = point(arguments.getValue("point"))
            val tolerance = arguments["tolerance"]?.jsonPrimitive?.int ?: 0
            require(tolerance in 0..255)
            LayerPaintEngine.floodFill(image, x.toInt(), y.toInt(), color, tolerance)
        }
        "shape" -> {
            val (x0, y0) = point(arguments.getValue("from"))
            val (x1, y1) = point(arguments.getValue("to"))
            val shape = PaintShape.valueOf(arguments.getValue("shape").jsonPrimitive.content.uppercase())
            val strokeWidth = arguments["stroke_width"]?.jsonPrimitive?.float ?: 1f
            require(strokeWidth.isFinite() && strokeWidth in 1f..512f)
            LayerPaintEngine.drawShape(image, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(),
                shape, color, opacity, strokeWidth, arguments["filled"]?.jsonPrimitive?.boolean ?: false)
        }
        else -> throw IllegalArgumentException("Unknown paint mode: $mode")
    }
    var left = width; var top = height; var right = 0; var bottom = 0
    for (y in 0 until height) for (x in 0 until width) if (image.getRGB(x, y) ushr 24 != 0) {
        left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
    }
    if (right <= left || bottom <= top) {
        require(source.layers.any { it.id.raw != id && it.id.raw !in deletedLayerIds }) {
            "Cannot erase the last active source layer; the analyzer requires artwork"
        }
        return copy(deletedLayerIds = deletedLayerIds + id)
    }
    val output = ByteArray((right - left) * (bottom - top) * 4)
    for (y in top until bottom) for (x in left until right) {
        val pixel = image.getRGB(x, y)
        val at = ((y - top) * (right - left) + x - left) * 4
        output[at] = (pixel ushr 16).toByte(); output[at + 1] = (pixel ushr 8).toByte()
        output[at + 2] = pixel.toByte(); output[at + 3] = (pixel ushr 24).toByte()
    }
    val updated = (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
        bounds = LayerBounds(left, top, right - left, bottom - top),
        raster = LayerRaster(right - left, bottom - top, output),
        sourceAssetId = null, sourceSpatialReferenceId = null, derived = true,
    )
    return copy(source = WorkspaceSourceArt(width, height,
        source.layers.map { if (it.id.raw == id) updated else it }, source.groups))
}
