package io.github.psd2live.ui

import io.github.psd2live.agent.AgentHistoryNodeSnapshot
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.core.AtlasPacker
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.CubismRuntimeAsset
import io.github.psd2live.core.CubismRuntimeBundle
import io.github.psd2live.core.LayerSemantic
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigAnchors
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a paint commit must not do is move the art it did not touch.
 *
 * The commit keeps every deformer of the live rig and only replaces the painted layer's raster, so
 * the rebuilt mesh has to stay normalized against the very frames those deformers were laid out on.
 * A mesh rebuilt against frames derived from the freshly painted bounds - which is what happens when
 * the frames are recomputed from the edited analysis - is rescaled against its parent deformer's
 * lattice: the drawable visibly squashes or drifts at the neutral pose, and stretches further as soon
 * as the model is posed.
 */
class PaintCommitGeometryTest {

    private class TestLayer(
        override val id: LayerId,
        override val name: String,
        override val order: Int,
        override val bounds: LayerBounds,
        override val raster: LayerRaster,
    ) : SourceLayer {
        override val groupPath: String = ""
        override val opacity: Float = 1f
        override val clipped: Boolean = false
        override val blend: LayerBlend = LayerBlend.Normal
    }

    private class TestSourceArt(
        override val widthPx: Int,
        override val heightPx: Int,
        override val layers: List<SourceLayer>,
    ) : SourceArt

    private class Fixture(val viewModel: PSD2LiveViewModel, val editor: CanvasEditor, val model: RigPreviewModel)

    private fun opaqueRaster(width: Int, height: Int): LayerRaster =
        LayerRaster(width, height, ByteArray(width * height * 4) { 0xFF.toByte() })

    private fun layer(id: String, tag: SemanticTag, side: Side, box: LayerBounds, order: Int): ClassifiedLayer {
        val raster = opaqueRaster(box.width, box.height)
        return ClassifiedLayer(
            source = TestLayer(LayerId(id), id, order, box, raster),
            semantic = LayerSemantic(tag = tag, side = side, normalizedName = id, confidence = 1f),
            bounds = Bounds(box.left.toFloat(), box.top.toFloat(), (box.left + box.width).toFloat(), (box.top + box.height).toFloat()),
            centroidX = box.left + box.width * 0.5f,
            centroidY = box.top + box.height * 0.5f,
            opaquePixels = box.width * box.height,
        )
    }

    /**
     * A head with one hair layer and one mouth, so the hair hangs under the hair physics warp - the
     * deformer whose frame is the hair layers' own union, and therefore the one that moves when the
     * hair is painted past its old bounds.
     */
    private fun fixture(paintOverrides: Map<String, String?> = emptyMap()): Fixture {
        val hairBox = LayerBounds(72, 24, 112, 60)
        val layers = listOf(
            layer("body", SemanticTag.NECK, Side.NONE, LayerBounds(48, 150, 160, 90), order = 7),
            layer("hair", SemanticTag.FRONT_HAIR, Side.NONE, hairBox, order = 6),
            layer("face", SemanticTag.FACE, Side.NONE, LayerBounds(80, 40, 96, 100), order = 5),
            layer("eye_l", SemanticTag.EYEWHITE, Side.LEFT, LayerBounds(92, 70, 28, 18), order = 4),
            layer("eye_r", SemanticTag.EYEWHITE, Side.RIGHT, LayerBounds(136, 70, 28, 18), order = 3),
            layer("irides_l", SemanticTag.IRIDES, Side.LEFT, LayerBounds(100, 74, 12, 10), order = 2),
            layer("irides_r", SemanticTag.IRIDES, Side.RIGHT, LayerBounds(144, 74, 12, 10), order = 1),
            layer("mouth", SemanticTag.MOUTH, Side.NONE, LayerBounds(116, 106, 24, 10), order = 0),
        )
        val anchors = RigAnchors(
            character = Bounds(0f, 0f, 256f, 256f),
            face = Bounds(80f, 40f, 176f, 140f),
            body = Bounds(48f, 150f, 208f, 240f),
            faceCenterX = 128f,
            faceCenterY = 90f,
            chinX = 128f,
            chinY = 140f,
            shoulderY = 150f,
            hipY = 200f,
        )
        val source = TestSourceArt(256, 256, layers.map { it.source })
        val analysis = PipelineAnalysis(
            source = source,
            layers = layers,
            anchors = anchors,
            warnings = emptyList(),
            preview = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB),
        )
        // Export sidecars are irrelevant here and cost the most time in the commit.
        val config = PipelineConfig(
            exportMotions = false,
            generatePhysics = false,
            exportCmo3 = false,
            exportMoc3 = false,
            exportJson = false,
            parentOverrides = paintOverrides,
        )
        val atlas = AtlasPacker.pack(layers, requestedSize = 256, padding = 2)
        val rig = RigBuilder.build(analysis, atlas, config)
        val bundle = CubismRuntimeBundle(
            manifestPath = "preview.model3.json",
            assets = listOf(CubismRuntimeAsset("preview.model3.json", byteArrayOf())),
        )
        val previewModel = RigPreviewModel(analysis, atlas, rig, config, bundle, baseRig = rig)

        val viewModel = PSD2LiveViewModel()
        val editor = viewModel.canvasEditor
        viewModel.installProjectState(
            viewModel.state.value.copy(
                previewModel = previewModel,
                analysis = analysis,
                historySnapshot = AgentHistorySnapshot(
                    headNodeId = "node_1",
                    nodes = listOf(
                        AgentHistoryNodeSnapshot(
                            id = "node_1",
                            parentId = null,
                            revisionId = "rev_1",
                            summary = "Initial",
                            actor = "USER",
                            taskId = null,
                            createdAt = "2026-01-01T00:00:00Z",
                            isHead = true,
                        )
                    ),
                ),
            )
        )
        viewModel.selectLayer("hair")
        return Fixture(viewModel, editor, previewModel)
    }

    /** The drawable's geometry in canvas space: what the model actually draws, at the default pose. */
    private fun canvasMesh(puppet: PuppetModel, layerId: String): FloatArray {
        val canvasModel = restMeshesToCanvasSpace(puppet)
        val drawable = canvasModel.drawables.firstOrNull { it.name == layerId }
            ?: error("no drawable for $layerId in ${canvasModel.drawables.map { it.name }}")
        return drawable.mesh?.positions ?: error("drawable $layerId carries no mesh")
    }

    private fun box(positions: FloatArray): Bounds {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in positions.indices step 2) {
            left = minOf(left, positions[index])
            right = maxOf(right, positions[index])
            top = minOf(top, positions[index + 1])
            bottom = maxOf(bottom, positions[index + 1])
        }
        return Bounds(left, top, right, bottom)
    }

    /**
     * Fills [x0, x1) by [y0, y1) in the session the way a raster op does: the area is declared before
     * it is written, so the stroke can be given back and undone, and is recorded as one stroke.
     */
    private fun PaintSession.paintRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Color, name: String) {
        edit(java.awt.Rectangle(x0, y0, x1 - x0, y1 - y0)) { image ->
            for (x in x0 until x1) {
                for (y in y0 until y1) image.setRGB(x, y, color.rgb)
            }
        }
        recordStroke(name)
    }

    private fun assertBox(actual: Bounds, expected: Bounds, tolerance: Float, label: String) {
        assertEquals(expected.left, actual.left, tolerance, "$label left")
        assertEquals(expected.top, actual.top, tolerance, "$label top")
        assertEquals(expected.right, actual.right, tolerance, "$label right")
        assertEquals(expected.bottom, actual.bottom, tolerance, "$label bottom")
    }

    @Test
    fun testRebuiltMeshKeepsTheRigFramesWhenThePaintGrowsTheLayer() {
        val fixture = fixture()
        val editor = fixture.editor
        val faceBefore = canvasMesh(fixture.model.rig.puppet, "face")

        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        val session = editor.ensurePaintSession("hair")
        requireNotNull(session)

        // Extend the hair well past its old bottom edge (24..83), full width: the layer's bounds grow
        // from 60 to 116 px tall, which is what moves the hair frame if the commit recomputes it.
        session.paintRect(72, 84, 184, 140, Color.MAGENTA, "extend hair")
        editor.commitPaintSession(rebuildMesh = true)

        val committed = fixture.viewModel.state.value.previewModel!!
        val hairBox = box(canvasMesh(committed.rig.puppet, "hair"))
        // The painted region, as the commit cropped it: 72..183 by 24..139.
        assertBox(hairBox, Bounds(72f, 24f, 184f, 140f), tolerance = 4f, label = "rebuilt hair")

        // Every other drawable keeps the geometry it had: their rasters and the deformers are untouched.
        val faceAfter = canvasMesh(committed.rig.puppet, "face")
        assertEquals(faceBefore.size, faceAfter.size)
        for (index in faceBefore.indices) {
            assertEquals(faceBefore[index], faceAfter[index], 1e-3f, "face vertex $index moved")
        }
    }

    @Test
    fun testLayerParentedToTheRootPaintsInCanvasSpace() {
        // A layer the user detached from every deformer is drawn straight in canvas coordinates, and a
        // rebuild has to keep it there instead of handing it back a parent-local mesh.
        val fixture = fixture(paintOverrides = mapOf("hair" to "root"))
        val editor = fixture.editor
        val before = fixture.model.rig.puppet.drawables.first { it.name == "hair" }
        assertNull(before.parentDeformerId, "a root override must drop the deformer parent")

        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        val session = editor.ensurePaintSession("hair")
        requireNotNull(session)
        session.paintRect(72, 84, 184, 140, Color.MAGENTA, "extend hair")
        editor.commitPaintSession(rebuildMesh = true)

        val committed = fixture.viewModel.state.value.previewModel!!
        val after = committed.rig.puppet.drawables.first { it.name == "hair" }
        assertNull(after.parentDeformerId)
        assertBox(box(after.mesh!!.positions), Bounds(72f, 24f, 184f, 140f), tolerance = 4f, label = "root-parented hair")
    }

    @Test
    fun testPaintedLayerKeepsItsGeometryWhenOnlyTheTextureChanges() {
        val fixture = fixture()
        val editor = fixture.editor
        val editorPuppet = fixture.model.rig.puppet

        editor.setHierarchyMode(EditHierarchyMode.PAINT)
        editor.activateTool(CanvasTool.PAINT_BRUSH)
        val session = editor.ensurePaintSession("hair")
        requireNotNull(session)

        // Repaint inside the layer's own bounds: neither the bounds nor the frames move, so keeping the
        // existing mesh must keep the drawable exactly where it was.
        val before = canvasMesh(editorPuppet, "hair")
        session.paintRect(80, 40, 120, 60, Color.CYAN, "recolour hair")
        editor.commitPaintSession(rebuildMesh = false)

        val committed = fixture.viewModel.state.value.previewModel!!
        val after = canvasMesh(committed.rig.puppet, "hair")
        assertEquals(before.size, after.size)
        assertTrue(after.all { it.isFinite() }, "the kept mesh must stay finite")
        val beforeBox = box(before)
        val afterBox = box(after)
        assertBox(afterBox, beforeBox, tolerance = 4f, label = "kept hair")
    }
}
