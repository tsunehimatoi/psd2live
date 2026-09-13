package io.github.psd2live.agent

import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal fun poseSheetColumns(count: Int): Int {
    require(count in 1..9) { "Provide 1..9 poses" }
    return ceil(sqrt(count.toDouble())).toInt()
}

/** A sheet is an observation, not a new canvas spatial reference. Tile image rectangles exclude labels. */
internal fun renderPoseSheet(
    views: List<AgentRenderedView>,
    output: AgentViewOutputSpec,
    columns: Int = poseSheetColumns(views.size),
    compareVersions: Boolean = false,
): AgentWorkflowResult {
    require(views.size in 1..9 && columns in 1..minOf(3, views.size)) { "Invalid pose sheet layout" }
    require(output.targetLongEdge in 128..4096 && output.maxBytes in 65536..16777216) { "Invalid sheet output budget" }
    val first = views.first()
    require(views.all { it.canvasRect == first.canvasRect && (compareVersions || (it.revisionId == first.revisionId &&
        it.includedLayerIds == first.includedLayerIds && it.annotatedLayerIds == first.annotatedLayerIds &&
        it.annotatedDeformerIds == first.annotatedDeformerIds && it.pointIndices == first.pointIndices)) }) {
        "Pose sheet requires the same revision, camera and composition"
    }
    val sources = views.map { view ->
        val image = requireNotNull(ImageIO.read(view.png.inputStream())) { "Invalid pose PNG" }
        require(image.width == view.renderedWidth && image.height == view.renderedHeight) { "Pose dimensions disagree" }
        image
    }
    val rows = (views.size + columns - 1) / columns
    val labelHeight = 24
    val aspect = first.canvasRect.width.toDouble() / first.canvasRect.height
    require(aspect.isFinite() && aspect > 0) { "Invalid pose camera" }
    // One common image size: per-image PNG budgets may have reduced different poses differently.
    val maxWidth = minOf(output.targetLongEdge / columns, sources.minOf { it.width })
    val maxHeight = minOf(output.targetLongEdge / rows - labelHeight, sources.minOf { it.height })
    var tileWidth = floor(minOf(maxWidth.toDouble(), maxHeight * aspect)).toInt()
    var tileHeight = floor(tileWidth / aspect).toInt()
    var png: ByteArray
    while (true) {
        require(tileWidth >= 1 && tileHeight >= 1 && maxOf(tileWidth, tileHeight) >= 64) {
            "Sheet budget would hide detail; request fewer poses or a larger target_long_edge/max_bytes"
        }
        val sheet = BufferedImage(columns * tileWidth, rows * (tileHeight + labelHeight), BufferedImage.TYPE_INT_ARGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            sources.forEachIndexed { index, source ->
                val x = index % columns * tileWidth
                val y = index / columns * (tileHeight + labelHeight)
                graphics.color = Color(32, 35, 42)
                graphics.fillRect(x, y, tileWidth, labelHeight)
                graphics.color = Color.WHITE
                graphics.drawString(('A'.code + index).toChar().toString(), x + 6, y + 17)
                graphics.drawImage(source, x, y + labelHeight, tileWidth, tileHeight, null)
            }
        } finally {
            graphics.dispose()
        }
        png = ByteArrayOutputStream().also { ImageIO.write(sheet, "png", it) }.toByteArray()
        if (png.size <= output.maxBytes) break
        tileWidth = floor(tileWidth * 0.8).toInt()
        tileHeight = floor(tileWidth / aspect).toInt()
    }
    val commonParameters = first.appliedParameters.filter { (id, value) -> views.all { it.appliedParameters[id] == value } }
    val metadata = buildJsonObject {
        if (!compareVersions) put("revisionId", first.revisionId)
        put("physicsSimulated", false)
        put("comparison", if (compareVersions) "versions" else "poses")
        put("width", columns * tileWidth)
        put("height", rows * (tileHeight + labelHeight))
        putJsonArray("canvasRect") {
            listOf(first.canvasRect.left, first.canvasRect.top, first.canvasRect.right, first.canvasRect.bottom).forEach { add(JsonPrimitive(it)) }
        }
        putJsonObject("commonParameters") { commonParameters.forEach { (id, value) -> put(id, value) } }
        putJsonArray("tiles") {
            views.forEachIndexed { index, view ->
                add(buildJsonObject {
                    put("id", ('A'.code + index).toChar().toString())
                    put("viewId", view.viewId)
                    if (compareVersions) put("revisionId", view.revisionId)
                    putJsonArray("imageRect") {
                        listOf(index % columns * tileWidth, index / columns * (tileHeight + labelHeight) + labelHeight,
                            tileWidth, tileHeight).forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonObject("parameters") { (view.appliedParameters - commonParameters.keys).forEach { (id, value) -> put(id, value) } }
                    if (view.outOfRangeParameters.isNotEmpty()) putJsonArray("outOfRangeParameters") {
                        view.outOfRangeParameters.forEach { issue -> add(buildJsonObject {
                            put("id", issue.id); put("value", issue.value); put("min", issue.min); put("max", issue.max)
                        }) }
                    }
                })
            }
        }
    }
    return AgentWorkflowResult(metadata, listOf(png))
}
