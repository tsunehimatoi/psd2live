package io.github.psd2live.core

import io.github.psd2live.project.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.test.*

class MouthOpenFadeTest {
    private fun preview(closedMouth: Boolean): RigPreviewModel {
        fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(
            LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false,
            LayerBlend.Normal, ChannelMask.ALL, LayerRaster(bounds.width, bounds.height,
                ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }), null, null, false)
        val layers = listOfNotNull(
            layer("face", "Face", 0, LayerBounds(8, 8, 72, 60)),
            layer("open", "Mouth open", 1, LayerBounds(32, 46, 24, 12)),
            if (closedMouth) layer("mouth", "Mouth", 2, LayerBounds(32, 50, 24, 4)) else null,
        )
        val overrides = mapOf("face" to SemanticTag.FACE, "open" to SemanticTag.MOUTH_OPEN, "mouth" to SemanticTag.MOUTH)
            .mapValues { LayerClassificationOverride(tag = it.value) }
        return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(96, 80, layers, emptyList()),
            PipelineConfig(atlasSize = 256, meshSpacing = 8, exportMoc3 = false, generatePhysics = false,
                rigEdits = RigEditOverlay(skeleton = SkeletonSpec.Disabled), layerOverrides = overrides))
    }

    /** The opacity of every drawable [layerId] owns, its lip ribbons included, at [open]. */
    private fun opacities(preview: RigPreviewModel, layerId: String, open: Float): List<Float> {
        val owned = preview.rig.puppet.drawables.filter {
            val layer = preview.rig.layerIdByDrawableId[it.id.raw]
            layer == layerId || layer == MouthLipLayer.idFor(layerId, 0) || layer == MouthLipLayer.idFor(layerId, 1)
        }
        assertTrue(owned.isNotEmpty())
        val frame = CpuDeformationEvaluator().evaluate(preview.rig.puppet, mapOf(StandardParameters.MOUTH_OPEN to open))
        return owned.map { frame.opacity.getValue(it.id) }
    }

    @Test fun anOpenMouthBesideAClosedOneIsHiddenAtRest() {
        val preview = preview(closedMouth = true)
        assertTrue(opacities(preview, "open", 0f).all { it == 0f }, "the open mouth and its lips are hidden when closed")
        assertTrue(opacities(preview, "open", 1f).all { it == 1f }, "and fully shown when open")
        assertTrue(opacities(preview, "mouth", 0f).all { it == 1f }, "the closed mouth keeps drawing the mouth at rest")
    }

    @Test fun anOpenMouthWithoutAClosedOneStaysVisible() {
        val preview = preview(closedMouth = false)
        assertTrue(opacities(preview, "open", 0f).all { it == 1f })
    }
}
