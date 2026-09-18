package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*
import org.umamo.runtime.keyform.keyIndexAt
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RigFastUpdateTest {

    private class DummySourceArt(
        override val widthPx: Int = 100,
        override val heightPx: Int = 100,
        override val layers: List<SourceLayer> = emptyList(),
        override val groups: List<SourceGroup> = emptyList(),
    ) : SourceArt

    private val dummySource = DummySourceArt()

    private val dummyBounds = Bounds(0f, 0f, 100f, 100f)
    private val dummyAnchors = RigAnchors(
        character = dummyBounds,
        face = dummyBounds,
        body = dummyBounds,
        faceCenterX = 50f,
        faceCenterY = 50f,
        chinX = 50f,
        chinY = 70f,
        shoulderY = 80f,
        hipY = 100f,
    )

    private val dummyAnalysis = PipelineAnalysis(
        source = dummySource,
        layers = emptyList(),
        anchors = dummyAnchors,
        warnings = emptyList(),
        preview = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB),
    )

    private val dummyAtlas = PackedAtlas(
        pages = listOf(AtlasPage(BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB), byteArrayOf())),
        placementByLayerId = emptyMap(),
    )

    private val param = Parameter(ParameterId("ParamAngleX"), "AngleX", -30f, 30f, 0f)

    private val basePuppet = PuppetModel(
        parameters = listOf(param),
        parts = emptyList(),
        deformers = emptyList(),
        drawables = listOf(
            Drawable(
                id = DrawableId("artmesh_1"),
                name = "ArtMesh1",
                parentDeformerId = null,
                blendMode = BlendMode.Normal,
                maskedBy = emptyList(),
                mesh = DrawableMesh(
                    positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f),
                    uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
                    indices = intArrayOf(0, 1, 2),
                ),
                geometryGrid = null,
            ),
        ),
        rootChildren = emptyList(),
        rootPartId = null,
    )

    private val baseRig = BuiltRig(
        puppet = basePuppet,
        pageByDrawableId = mapOf("artmesh_1" to 0),
        sourceBoundsByDrawableId = emptyMap(),
        layerIdByDrawableId = mapOf("artmesh_1" to "layer_1"),
        faceCenterX = 50f,
        faceCenterY = 50f,
        faceRadiusX = 20f,
        faceRadiusY = 20f,
        warnings = emptyList(),
    )

    private val dummyBundle = CubismRuntimeBundle(
        manifestPath = "psd2live-preview.model3.json",
        assets = listOf(
            CubismRuntimeAsset("psd2live-preview.model3.json", "{\"Version\":3,\"FileReferences\":{\"Moc\":\"psd2live-preview.moc3\",\"Textures\":[\"psd2live-preview.10/texture_00.png\"]}}".encodeToByteArray()),
            CubismRuntimeAsset("psd2live-preview.moc3", byteArrayOf()),
            CubismRuntimeAsset("psd2live-preview.10/texture_00.png", byteArrayOf()),
        ),
    )

    private val initialPreview = RigPreviewModel(
        analysis = dummyAnalysis,
        atlas = dummyAtlas,
        rig = baseRig,
        config = PipelineConfig(),
        runtimeBundle = dummyBundle,
        baseRig = baseRig,
    )

    private val pipeline = PSD2LivePipeline()

    private fun overlayWithDeltas(deltas: List<Float>): RigEditOverlay =
        RigEditOverlay.Empty.setKeyform(
            RigKeyformSetEdit(
                target = RigTargetRef(RigTargetKind.ART_MESH, "artmesh_1"),
                coordinate = mapOf("ParamAngleX" to 0f),
                geometry = RigKeyformGeometryEdit(positionDeltas = deltas),
            )
        )

    @Test
    fun canFastUpdateRigReturnsTrueWhenOnlyRigEditsChange() {
        val nextConfig = initialPreview.config.copy(
            rigEdits = overlayWithDeltas(listOf(1f, 1f, 1f, 1f, 1f, 1f))
        )
        assertTrue(pipeline.canFastUpdateRig(initialPreview, dummySource, nextConfig))
    }

    @Test
    fun canFastUpdateRigReturnsFalseWhenSourceDiffers() {
        val nextConfig = initialPreview.config.copy(
            rigEdits = overlayWithDeltas(listOf(1f, 1f, 1f, 1f, 1f, 1f))
        )
        val differentSource = DummySourceArt(widthPx = 500)
        assertFalse(pipeline.canFastUpdateRig(initialPreview, differentSource, nextConfig))
    }

    @Test
    fun canFastUpdateRigReturnsFalseWhenParentOverridesDiffer() {
        val nextConfig = initialPreview.config.copy(
            parentOverrides = mapOf("layer_1" to "deformer_root")
        )
        assertFalse(pipeline.canFastUpdateRig(initialPreview, dummySource, nextConfig))
    }

    @Test
    fun canFastUpdateRigReturnsFalseWhenDeletedLayersDiffer() {
        val nextConfig = initialPreview.config.copy(
            deletedLayerIds = setOf("layer_1")
        )
        assertFalse(pipeline.canFastUpdateRig(initialPreview, dummySource, nextConfig))
    }

    @Test
    fun canFastUpdateRigReturnsFalseWhenAtlasSizeDiffers() {
        val nextConfig = initialPreview.config.copy(
            atlasSize = 2048
        )
        assertFalse(pipeline.canFastUpdateRig(initialPreview, dummySource, nextConfig))
    }

    private fun deltasAt(model: PuppetModel, paramId: ParameterId, value: Float): FloatArray {
        val grid = model.drawables.first().geometryGrid!!
        val keyIdx = grid.keyIndexAt(paramId, value)
        return grid.cells.first { it.coordinate.contentEquals(intArrayOf(keyIdx)) }.form.positionDeltas
    }

    @Test
    fun updateRigEditsAppliesEditsOntoBaseRigWithoutMutatingBaseRig() {
        val nextConfig = initialPreview.config.copy(
            rigEdits = overlayWithDeltas(listOf(2f, 2f, 2f, 2f, 2f, 2f))
        )

        val updated = pipeline.updateRigEdits(initialPreview, nextConfig)

        // baseRig must remain unmutated and referenced
        assertEquals(initialPreview.baseRig, updated.baseRig)
        assertNull(initialPreview.baseRig.puppet.drawables.first().geometryGrid)

        // Updated rig must reflect the new keyform on the seeded axis
        val updatedGrid = updated.rig.puppet.drawables.first().geometryGrid
        assertNotNull(updatedGrid)
        assertEquals(3, updatedGrid.cells.size)
        assertEquals(2f, deltasAt(updated.rig.puppet, ParameterId("ParamAngleX"), 0f)[0])

        // Runtime bundle should be refreshed
        assertTrue(updated.runtimeBundle.assets.isNotEmpty())
    }

    @Test
    fun updateRigEditsSupportsConsecutiveEditsAndUndo() {
        val edit1Config = initialPreview.config.copy(
            rigEdits = overlayWithDeltas(listOf(1f, 1f, 1f, 1f, 1f, 1f))
        )
        val edit2Config = initialPreview.config.copy(
            rigEdits = overlayWithDeltas(listOf(5f, 5f, 5f, 5f, 5f, 5f))
        )

        // Stroke 1
        val state1 = pipeline.updateRigEdits(initialPreview, edit1Config)
        assertEquals(1f, deltasAt(state1.rig.puppet, ParameterId("ParamAngleX"), 0f)[0])

        // Stroke 2 (applied on top of state1)
        val state2 = pipeline.updateRigEdits(state1, edit2Config)
        assertEquals(5f, deltasAt(state2.rig.puppet, ParameterId("ParamAngleX"), 0f)[0])

        // Undo to Stroke 1
        val undone = pipeline.updateRigEdits(state2, edit1Config)
        assertEquals(1f, deltasAt(undone.rig.puppet, ParameterId("ParamAngleX"), 0f)[0])

        // Undo to Initial (empty edits)
        val undoneToInitial = pipeline.updateRigEdits(undone, initialPreview.config)
        assertNull(undoneToInitial.rig.puppet.drawables.first().geometryGrid)
        assertEquals(initialPreview.baseRig.puppet, undoneToInitial.rig.puppet)
    }
}
