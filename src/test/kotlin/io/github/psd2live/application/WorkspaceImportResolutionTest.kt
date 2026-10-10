package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** File imports, assets and registrations keep their pixels; placement only sets the canvas rectangle. */
class WorkspaceImportResolutionTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    private fun disc(size: Int) = RasterImage(size, size, discPixels(size))

    private fun pupil(model: RigPreviewModel, id: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }

    @Test fun aLargeFileImportKeepsItsPixelsAndGetsItsOwnMesh() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val original = simulationFixture(runtime)
        val file = temporary.resolve("disc.png").also { Files.write(it, PngCodec.write(disc(1024))) }
        val added = WorkspaceImageLayerCommands(runtime).importImages(original.projectId, original.state, listOf(file), null, "Import", MutationAuthor.USER)
        val id = added.mutation.affectedLayerIds.single()
        val capture = runtime.capture()
        val imported = capture.document.source.layers.single { it.id.raw == id }
        // Larger than the 64 x 96 canvas: fitted, every source pixel kept.
        assertTrue(imported.raster.width > 900 && imported.raster.width == imported.raster.height)
        assertTrue(abs(imported.canvasRect().width - 64f) < 1e-3f, "${imported.canvasRect()}")
        validateRegisteredNeutral(capture.model, setOf(id))
        // Meshed at canvas density: a few dozen vertices, not thousands.
        assertTrue(pupil(capture.model, id).mesh!!.vertexCount < 200, "${pupil(capture.model, id).mesh!!.vertexCount} vertices")
        // The mesh is the user's, created once in the journal; the generators never mesh the layer, and nothing else is frozen.
        val creations = capture.document.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
        assertEquals(listOf(id), creations.map { it.getValue("layer_id").jsonPrimitive.content })
        assertEquals(original.document.generationSource, capture.document.generationSource)
        assertTrue(capture.model.baseRig.puppet.drawables.none { capture.model.baseRig.layerIdByDrawableId[it.id.raw] == id })
        assertEquals(1, capture.model.rig.puppet.drawables.count { capture.model.rig.layerIdByDrawableId[it.id.raw] == id })
        val cold = builder.build(capture.document)
        assertContentEquals(pupil(capture.model, id).mesh!!.positions, pupil(cold, id).mesh!!.positions)
    }

    private fun asset(size: Int, rect: Bounds): WorkspacePngAsset {
        val image = disc(size)
        return WorkspacePngAsset(WorkspaceImportedPngAsset("asset-test", "sha", size, size,
            WorkspaceCanvasPlacement("canvas_top_left_y_down", rect, size, size, rect.width / size, rect.height / size, "view")), image.rgba)
    }

    @Test fun anAssetIsAddedAtItsOwnResolutionOverItsPlacement() {
        val document = runBlocking { simulationFixture(WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })) }.document
        val asset = asset(1024, Bounds(20f, 12f, 52f, 44f))
        val (added, id) = document.addLayer(asset, WorkspaceAddLayerRequest("asset-test", "", "Pupil", layerId = "pupil"))
        val layer = added.source.layers.single { it.id.raw == id }
        // Trimmed to its visible pixels, which keep their resolution; the rectangle scales with them.
        val trimmed = layer.raster.width
        assertTrue(trimmed in 900..1024 && layer.raster.height == trimmed)
        val rect = layer.canvasRect()
        assertTrue(abs(rect.width - trimmed * 32f / 1024) < 1e-3f, "$rect")
        assertTrue(abs(rect.left - (20f + (1024 - trimmed) / 2 * 32f / 1024)) < 0.05f, "$rect")
        val space = LayerSpace.of(layer)
        assertTrue(abs(space.scaleX - 32f) < 1e-2f)
        // Untrimmed, the raster is the asset itself over the full placement.
        val (whole, wholeId) = document.addLayer(asset, WorkspaceAddLayerRequest("asset-test", "", "Pupil", layerId = "whole", trimTransparent = false))
        val full = whole.source.layers.single { it.id.raw == wholeId }
        assertContentEquals(asset.rgba, full.raster.rgba)
        assertEquals(LayerCanvasRect(20f, 12f, 32f, 32f), full.canvasRect())
        assertNull(full.storedCanvasRect)
    }

    @Test fun aRegistrationPlacesByRectangleWithoutResampling() {
        val asset = asset(64, Bounds(0f, 0f, 64f, 64f))
        fun registration(transform: JsonObject) = buildJsonObject { put("asset_id", asset.public.id); put("transform", transform) }
        val placed = placedAsset(asset, registration(buildJsonObject { put("x", 10.25); put("y", 3); put("scale_x", 0.5) }))
        assertSame(asset.rgba, placed.rgba)
        assertEquals(64, placed.public.pixelWidth)
        assertEquals(Bounds(10.25f, 3f, 42.25f, 35f), placed.public.placement.canvasRect)
        assertEquals(0.5f, placed.public.placement.canvasUnitsPerPixelX)

        // Explicit reflection reverses columns exactly.
        val mirrored = placedAsset(asset, registration(buildJsonObject { put("x", 42.25); put("y", 3); put("scale_x", 0.5); put("mirror_x", true) }))
        assertEquals(Bounds(10.25f, 3f, 42.25f, 35f), mirrored.public.placement.canvasRect)
        for (y in listOf(10, 32, 50)) for (x in listOf(5, 20, 40)) for (c in 0..3)
            assertEquals(asset.rgba[(y * 64 + 63 - x) * 4 + c], mirrored.rgba[(y * 64 + x) * 4 + c])

        // A rotation is rasterized at the asset's own density into its bounding box.
        val rotated = placedAsset(asset, registration(buildJsonObject { put("x", 40); put("y", 0); put("scale_x", 0.5); put("rotation_degrees", 30) }))
        val box = rotated.public.placement.canvasRect
        assertTrue(abs(rotated.public.pixelWidth * 0.5f - box.width) <= 0.5f, "${rotated.public.pixelWidth} over $box")
        assertTrue(abs(rotated.public.placement.canvasUnitsPerPixelX - 0.5f) < 0.02f)
    }
}
