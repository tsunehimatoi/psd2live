package io.github.psd2live.core.sim

import io.github.psd2live.agent.*
import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ModelPresetsTest {
    private fun silhouette(pants: Boolean, padded: Boolean = false): LayerRaster {
        val width = 80; val height = 120
        val rgba = ByteArray(width * height * 4)
        for (y in (if (padded) 10 else 0) until 110) for (x in 0 until width) {
            val half = if (pants) 25 else 12 + y / 5
            if (x in 40 - half..40 + half && !(pants && y > 45 && x in 35..45)) rgba[(y * width + x) * 4 + 3] = -1
        }
        return LayerRaster(width, height, rgba)
    }

    @Test fun detectsLegGapWithoutDependingOnLayerNames() {
        val pants = ModelPresets.silhouette(silhouette(true), "bottomwear")
        val skirt = ModelPresets.silhouette(silhouette(false), "bottomwear")
        assertEquals(ModelPresets.Garment.TROUSERS, pants.garment)
        assertNotNull(pants.crotch)
        assertEquals(ModelPresets.Garment.SKIRT, skirt.garment)
        assertNull(skirt.crotch)
        assertTrue(skirt.waist < 0.1f)
    }

    @Test fun namesDisambiguateSplitSkirtsAndSingleTrouserLegs() {
        assertEquals(ModelPresets.Garment.SKIRT, ModelPresets.silhouette(silhouette(true), "裙子").garment)
        assertEquals(ModelPresets.Garment.TROUSERS, ModelPresets.silhouette(silhouette(false), "pants_left").garment)
        assertEquals(ModelPresets.Garment.SKIRT, ModelPresets.silhouette(silhouette(false, true), "服装").garment)
        assertFailsWith<IllegalArgumentException> { ModelPresets.silhouette(LayerRaster(4, 4, ByteArray(64)), "empty") }
    }

    @Test fun rootsEveryDisconnectedLegAndLeavesBothCuffsFree() {
        val positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, -50f, 10f, -50f, 30f, -10f, 40f, -10f, 30f, -60f, 40f, -60f)
        val mesh = DrawableMesh(positions, FloatArray(16), intArrayOf(0, 1, 2, 1, 3, 2, 4, 5, 6, 5, 7, 6))
        val pin = ModelPresets.pinWeights(mesh, positions, false, ModelPresets.Silhouette(ModelPresets.Garment.TROUSERS, 0f, 1f, 0.4f))
        assertContentEquals(floatArrayOf(1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f), pin)
    }

    @Test fun clothingPresetPersistsAndReappliesWithoutDuplicatingSimulationsOrWeights() {
        val raster = silhouette(true)
        val layer = WorkspaceSourceLayer(LayerId("bottom"), "bottomwear", "", SourceLayerKind.Raster, true, 0,
            LayerBounds(0, 0, raster.width, raster.height), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
        val source = WorkspaceSourceArt(80, 120, listOf(layer), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshInteriorDensity = 15f)
        val analysis = CharacterAnalyzer.analyze(source, config)
        val preview = PSD2LivePipeline().buildPreview(analysis, config)
        val applied = ModelPresets.apply(RigEditOverlay.Empty, preview.rig.puppet, analysis,
            preview.rig.layerIdByDrawableId, ModelPresets.Preset.CLOTHING)
        assertEquals(listOf(ModelPresets.Garment.TROUSERS), applied.garments.values.toList())
        val edit = applied.overlay.simEdits.single()
        assertEquals(SimKind.CLOTH, edit.kind)
        val replayed = applied.overlay.applyTo(preview.baseRig.puppet)
        val scene = SimScene.build(replayed, edit)
        assertTrue(scene.solver.pinWeight.any { it == 1f })
        assertTrue(scene.solver.pinWeight.any { it == 0f })
        assertTrue(scene.notes.none { "Nothing is pinned" in it })
        val again = ModelPresets.apply(applied.overlay, replayed, analysis, preview.rig.layerIdByDrawableId, ModelPresets.Preset.CLOTHING)
        assertEquals(applied.overlay, again.overlay)
        val temp = createTempDirectory("preset-store")
        val document = AgentWorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), applied.overlay)
        val store = AgentWorkspaceStore(temp)
        val history = WorkspaceHistoryTree(document, "preset", "preset")
        store.persistHistory("preset", history.state())
        val restored = assertNotNull(store.loadHistory("preset")).head().snapshot.rigEdits
        assertEquals(applied.overlay, restored)
        assertEquals(replayed.vertexGroups, restored.applyTo(preview.baseRig.puppet).vertexGroups)
        // Selection containing only a nonmatching layer never silently applies to the entire model.
        assertFailsWith<IllegalArgumentException> {
            ModelPresets.apply(restored, replayed, analysis, preview.rig.layerIdByDrawableId, ModelPresets.Preset.CLOTHING, setOf("missing"))
        }
    }
}
