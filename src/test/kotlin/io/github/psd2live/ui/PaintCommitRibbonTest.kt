package io.github.psd2live.ui

import io.github.psd2live.agent.AgentHistoryNodeSnapshot
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import java.awt.Color
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A mouth's outline ribbons are generated from the mouth layer, and a paint commit regenerates their
 * pixels from whatever the mouth now holds. The ribbons therefore have to be rebuilt with the same
 * contour: a ribbon that keeps the shape of the mouth it was drawn for ends up sampling outside the
 * slice its regenerated layer was packed into, and reads as the art tearing apart around the mouth -
 * which is what erasing half a mouth used to produce, twice over.
 */
class PaintCommitRibbonTest {

    private lateinit var viewModel: PSD2LiveViewModel
    private lateinit var editor: CanvasEditor

    private fun loadModel(): RigPreviewModel {
        val config = PipelineConfig(
            atlasSize = 2048,
            exportMotions = false,
            generatePhysics = false,
            exportCmo3 = false,
            exportMoc3 = false,
            exportJson = false,
        )
        val preview = PSD2LivePipeline().buildPreview(Path.of("examples/ds/psd-input/ds.psd"), config)
        viewModel = PSD2LiveViewModel()
        editor = viewModel.canvasEditor
        viewModel.installProjectState(
            viewModel.state.value.copy(
                previewModel = preview,
                analysis = preview.analysis,
                historySnapshot = AgentHistorySnapshot(
                    "node_1",
                    listOf(AgentHistoryNodeSnapshot("node_1", null, "rev_1", "Initial", "USER", null, "2026-01-01T00:00:00Z", true)),
                ),
            )
        )
        return preview
    }

    private fun preview(): RigPreviewModel = viewModel.state.value.previewModel!!

    private fun mouthLayerId(preview: RigPreviewModel): String =
        preview.analysis.layers.first { it.semantic.tag.name == "MOUTH" && !it.source.id.raw.contains("lip") }
            .source.id.raw

    /** The drawable the mouth layer paints into; its id is what the drawable maps are keyed by. */
    private fun mouthDrawableId(preview: RigPreviewModel, layerId: String): String =
        preview.rig.puppet.drawables.first { preview.rig.layerIdByDrawableId[it.id.raw] == layerId }.id.raw

    private fun ribbonIds(preview: RigPreviewModel): List<String> =
        preview.rig.puppet.drawables.filter { it.id.raw.endsWith("_lip_0") || it.id.raw.endsWith("_lip_1") }
            .map { it.id.raw }

    /** Every drawable vertex whose texture coordinate falls outside the slice that layer was packed into. */
    private fun outsideOwnSlice(preview: RigPreviewModel): List<String> {
        val violations = mutableListOf<String>()
        for (drawable in preview.rig.puppet.drawables) {
            val mesh = drawable.mesh ?: continue
            val placement = preview.atlas.placementByLayerId[preview.rig.layerIdByDrawableId[drawable.id.raw]]
                ?: run { violations += "${drawable.id.raw}: no atlas slice"; continue }
            val page = preview.atlas.pages.getOrNull(placement.page)
                ?: run { violations += "${drawable.id.raw}: no atlas page"; continue }
            val scale = placement.scale.coerceAtLeast(1)
            var outside = 0
            for (index in mesh.uvs.indices step 2) {
                val localX = (mesh.uvs[index] * page.image.width - placement.x) / scale
                val localY = (mesh.uvs[index + 1] * page.image.height - placement.y) / scale
                if (localX < -1f || localY < -1f || localX > placement.width + 1f || localY > placement.height + 1f) {
                    outside++
                }
            }
            if (outside > 0) violations += "${drawable.id.raw}: $outside of ${mesh.uvs.size / 2} vertices outside their slice"
        }
        return violations
    }

    /** The drawable's geometry in canvas space: what the model draws, at the default pose. */
    private fun canvasBox(puppet: PuppetModel, drawableId: String): Bounds? {
        val mesh = restMeshesToCanvasSpace(puppet).drawables.firstOrNull { it.id.raw == drawableId }?.mesh ?: return null
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (index in mesh.positions.indices step 2) {
            left = minOf(left, mesh.positions[index]); right = maxOf(right, mesh.positions[index])
            top = minOf(top, mesh.positions[index + 1]); bottom = maxOf(bottom, mesh.positions[index + 1])
        }
        return Bounds(left, top, right, bottom)
    }

    private fun boxes(preview: RigPreviewModel): Map<String, Bounds?> =
        preview.rig.puppet.drawables.associate { it.id.raw to canvasBox(preview.rig.puppet, it.id.raw) }

    private fun startErasing(layerId: String) {
        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_ERASER)
        assertNotNull(editor.ensurePaintSession(layerId), "no paint session for $layerId")
    }

    private fun erase(x: Int, y: Int, width: Int, height: Int) {
        val session = editor.paintSession!!
        session.edit(java.awt.Rectangle(x, y, width, height)) { image ->
            val g = image.createGraphics()
            g.composite = java.awt.AlphaComposite.Clear
            g.fillRect(x, y, width, height)
            g.dispose()
        }
        session.recordStroke("erase")
    }

    @Test
    fun testErasingPartOfTheMouthRebuildsItsRibbons() {
        val built = loadModel()
        val mouthId = mouthLayerId(built)
        val ribbons = ribbonIds(built)
        assertEquals(2, ribbons.size, "the sample model must have both lip ribbons: $ribbons")
        val mouth = built.analysis.layers.first { it.source.id.raw == mouthId }.source.bounds
        val before = boxes(built)

        startErasing(mouthId)
        // The eraser takes the left half of the mouth, exactly the drag that used to break the ribbons.
        erase(mouth.left, mouth.top, mouth.width / 2, mouth.height)
        editor.commitPaintSession(rebuildMesh = true)

        val committed = preview()
        assertTrue(outsideOwnSlice(committed).isEmpty(), outsideOwnSlice(committed).toString())
        val after = boxes(committed)
        for (ribbon in ribbons) {
            assertNotNull(after[ribbon], "$ribbon must survive a partial erase")
            assertTrue(
                after[ribbon]!!.left > before[ribbon]!!.left + 1f,
                "$ribbon must follow the remaining mouth: was ${before[ribbon]}, now ${after[ribbon]}",
            )
        }
        // A mouth whose box grew past its old bounds must not fly out of the face: its geometry grid
        // closes it onto the mouth curve, which lands near the mouth, never a canvas away.
        val mouthBox = after[mouthDrawableId(committed, mouthId)]!!
        assertTrue(
            mouthBox.centerX in (mouth.left - mouth.width).toFloat()..(mouth.left + 2 * mouth.width).toFloat() &&
                mouthBox.centerY in (mouth.top - mouth.height).toFloat()..(mouth.top + 2 * mouth.height).toFloat(),
            "the rebuilt mouth must stay around the painted region, was $mouthBox for $mouth",
        )
        // Only the mouth and its ribbons move; every other drawable keeps the geometry it had.
        val mouthDrawable = mouthDrawableId(committed, mouthId)
        for ((id, box) in before) {
            if (id == mouthDrawable || id in ribbons) continue
            assertEquals(box, after[id], "$id moved")
        }
    }

    @Test
    fun testErasingTheWholeMouthTakesItsRibbonsWithIt() {
        val built = loadModel()
        val mouthId = mouthLayerId(built)
        val ribbons = ribbonIds(built)
        val before = boxes(built)

        startErasing(mouthId)
        erase(0, 0, editor.paintSession!!.docWidth, editor.paintSession!!.docHeight)
        editor.commitPaintSession(rebuildMesh = true)

        val committed = preview()
        val after = boxes(committed)
        for (ribbon in ribbons) {
            assertTrue(ribbon !in after, "$ribbon has no layer left to sample and must be dropped")
        }
        assertTrue(outsideOwnSlice(committed).isEmpty(), outsideOwnSlice(committed).toString())
        val mouthDrawable = mouthDrawableId(committed, mouthId)
        for ((id, box) in before) {
            if (id == mouthDrawable || id in ribbons) continue
            assertEquals(box, after[id], "$id moved")
        }
    }

    @Test
    fun testPaintingTheMouthBackRestoresItsRibbons() {
        val built = loadModel()
        val mouthId = mouthLayerId(built)
        val ribbons = ribbonIds(built)
        val mouth = built.analysis.layers.first { it.source.id.raw == mouthId }.source.bounds

        startErasing(mouthId)
        erase(0, 0, editor.paintSession!!.docWidth, editor.paintSession!!.docHeight)
        editor.commitPaintSession(rebuildMesh = true)
        assertTrue(ribbonIds(preview()).isEmpty(), "the erase must have dropped the ribbons")

        // Painting the mouth back is what a full rebuild would have generated ribbons for again.
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        val session = editor.ensurePaintSession(mouthId)
        assertNotNull(session)
        session.edit(java.awt.Rectangle(mouth.left + 4, mouth.top + 6, mouth.width - 8, mouth.height - 12)) { image ->
            val g = image.createGraphics()
            g.color = Color(0xFFB03060.toInt())
            g.fillRect(mouth.left + 4, mouth.top + 6, mouth.width - 8, mouth.height - 12)
            g.dispose()
        }
        session.recordStroke("paint mouth")
        editor.commitPaintSession(rebuildMesh = true)

        val committed = preview()
        assertTrue(outsideOwnSlice(committed).isEmpty(), outsideOwnSlice(committed).toString())
        val restored = ribbonIds(committed)
        assertEquals(ribbons.size, restored.size, "ribbons must come back with the mouth: $ribbons -> $restored")
        for (ribbon in restored) {
            val layerId = committed.rig.layerIdByDrawableId[ribbon]
            assertNotNull(committed.analysis.layers.firstOrNull { it.source.id.raw == layerId }, "$ribbon has no layer")
        }
    }
}
