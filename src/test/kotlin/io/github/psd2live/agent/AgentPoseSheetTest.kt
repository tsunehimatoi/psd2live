package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.*

class AgentPoseSheetTest {
    private fun view(id: String, size: Int = 256, angle: Float = 0f, noise: Boolean = false): AgentRenderedView {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val random = Random(71)
        for (y in 0 until size) for (x in 0 until size) {
            image.setRGB(x, y, if (noise) random.nextInt() or 0xff000000.toInt() else if (x >= size / 2) 0xffff0000.toInt() else 0)
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val rect = Bounds(100f, 200f, 300f, 400f)
        return AgentRenderedView(id, "r1", "model", emptyList(), png, size, size, size, size, rect, 1f, "unused",
            AgentViewSpatialMetadata(pixelWidth = size, pixelHeight = size, canvasWidth = 1000f, canvasHeight = 1000f,
                requestedViewRect = rect, viewRect = rect, canvasUnitsPerPixelX = 200f / size, canvasUnitsPerPixelY = 200f / size),
            appliedParameters = mapOf("AngleX" to angle, "EyeOpen" to 1f))
    }

    @Test fun sheetHasOneImageSharedScaleAndReversibleTileMapping() {
        val result = renderPoseSheet(listOf(view("v0"), view("v1", size = 128, angle = 30f)), AgentViewOutputSpec(512))
        val image = ImageIO.read(result.images.single().inputStream())
        val preview = Path.of("build", "test-artifacts", "pose-sheet.png")
        Files.createDirectories(preview.parent)
        Files.write(preview, result.images.single())
        val tiles = result.metadata.getValue("tiles").jsonArray
        assertEquals(256, image.width)
        assertEquals(buildJsonObject { put("EyeOpen", 1f) }, result.metadata.getValue("commonParameters").jsonObject)
        for ((index, tile) in tiles.withIndex()) {
            val data = tile.jsonObject
            assertEquals("v$index", data.getValue("viewId").jsonPrimitive.content)
            val rect = data.getValue("imageRect").jsonArray.map { it.jsonPrimitive.int }
            val (x, y, width, height) = rect
            assertEquals(listOf(index * 128, 24, 128, 128), rect)
            // Labels do not belong to canvas coordinates. Identical artwork has identical placement.
            assertEquals(0, image.getRGB(x + width / 4, y + height / 2) ushr 24)
            assertEquals(0xffff0000.toInt(), image.getRGB(x + width * 3 / 4, y + height / 2))
            val canvas = result.metadata.getValue("canvasRect").jsonArray.map { it.jsonPrimitive.float }
            val sheetX = x + width * 0.75f
            assertEquals(250f, canvas[0] + (sheetX - x) / width * (canvas[2] - canvas[0]))
        }
    }

    @Test fun outputBudgetAppliesToWholeSheetAndKeepsLabelsMappedAfterReduction() {
        val result = renderPoseSheet((0..8).map { view("v$it", noise = true) }, AgentViewOutputSpec(768, 65536))
        assertTrue(result.images.single().size <= 65536)
        val image = ImageIO.read(result.images.single().inputStream())
        assertTrue(maxOf(image.width, image.height) <= 768)
        val rect = result.metadata.getValue("tiles").jsonArray.last().jsonObject.getValue("imageRect").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(image.width, rect[0] + rect[2])
        assertEquals(image.height, rect[1] + rect[3])
    }

    @Test fun preservesRangeDiagnosticsAndRejectsMisleadingComparisons() {
        val first = view("v0")
        val second = view("v1", angle = 45f).copy(outOfRangeParameters = listOf(AgentParameterRangeDiagnostic("AngleX", 45f, -30f, 30f)))
        val result = renderPoseSheet(listOf(first, second), AgentViewOutputSpec())
        assertEquals(45f, result.metadata.getValue("tiles").jsonArray[1].jsonObject.getValue("outOfRangeParameters")
            .jsonArray.single().jsonObject.getValue("value").jsonPrimitive.float)
        assertFailsWith<IllegalArgumentException> { renderPoseSheet(listOf(first, second.copy(revisionId = "r2")), AgentViewOutputSpec()) }
        assertFailsWith<IllegalArgumentException> { renderPoseSheet(listOf(first, second.copy(canvasRect = Bounds(0f, 0f, 200f, 200f))), AgentViewOutputSpec()) }
        assertFailsWith<IllegalArgumentException> { renderPoseSheet(List(9) { first }, AgentViewOutputSpec(128)) }
    }
}
