package io.github.psd2live.application

import io.github.psd2live.core.IrFrameRenderer
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceGroup
import io.github.psd2live.scenario.Characters
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.OrgChild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceThroughHairGroupTest {
    private val head = Characters.head(physics = false)
    private val paint = head.source.layers.single { it.id.raw == "face" } as io.github.psd2live.document.WorkspaceSourceLayer

    private fun box(id: String, name: String, order: Int, bounds: LayerBounds, rgb: IntArray,
                    group: String = "", visible: Boolean = true) = paint.copy(
        id = LayerId(id), name = name, order = order, groupPath = group, bounds = bounds, visible = visible,
        raster = LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { i ->
            if (i % 4 == 3) 255.toByte() else rgb[i % 4].toByte()
        }),
    )

    private fun preview(layers: List<io.github.psd2live.document.WorkspaceSourceLayer>, groups: List<WorkspaceSourceGroup>): RigPreviewModel {
        val document = head.copy(source = WorkspaceSourceArt(48, 48, layers, groups),
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val choices = WorkspaceInitialPreset(clothing = false, motionBasic = false,
            frontHair = WorkspaceInitialHair.OFF, backHair = WorkspaceInitialHair.OFF)
        return WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct, choices, richness = "base").second
    }

    private fun RigPreviewModel.drawable(layer: String): Drawable =
        rig.puppet.drawables.single { rig.ownLayerIdByDrawableId[it.id.raw] == layer }

    private fun RigPreviewModel.frame() = IrFrameRenderer.open(RigIrCompiler.compile(this), false).use {
        it.render(emptyMap(), 0f, FrameSpec(0f, 0f, 48f, 48f, 48, 48))
    }

    @Test fun sourceEyeOverlayClipsOnceToVisibleFrontHairAndKeepsTheAuthoredOpacity() {
        val neutral = intArrayOf(100, 100, 100)
        val ink = intArrayOf(240, 0, 0)
        val lashes = LayerBounds(8, 11, 32, 16)
        val preview = preview(listOf(
            box("outer-hair", "front hair", 0, LayerBounds(2, 2, 4, 5), neutral),
            box("eye-copy", "eyelash upper-r", 1, lashes, ink, "eyepaint"),
            box("brow-copy", "eyebrow-r", 2, lashes, ink, "eyepaint"),
            box("extra", "ornament", 3, LayerBounds(2, 39, 8, 6), neutral, "ordinary"),
            box("bang", "front hair", 4, LayerBounds(24, 4, 20, 30), intArrayOf(0, 0, 0)),
            box("hidden-hair", "front hair", 5, lashes, neutral, visible = false),
            box("face", "face", 6, LayerBounds(1, 1, 45, 45), neutral),
        ), listOf(
            WorkspaceSourceGroup("eyepaint", "眼眉透发", true, .25f, false, LayerBlend.Normal, true),
            WorkspaceSourceGroup("ordinary", "Ordinary paint", true, .25f, false, LayerBlend.Normal, true),
        ))
        val group = preview.rig.puppet.parts.single { it.name == "眼眉透发" }
        assertTrue(group.isIsolated)
        assertEquals(.25f, group.composite.opacity)
        assertEquals(listOf(preview.drawable("bang").id), group.composite.maskedBy)
        assertTrue(preview.rig.puppet.parts.single { it.name == "Ordinary paint" }.composite.maskedBy.isEmpty())
        assertTrue(OrgChild.Drawable(preview.drawable("eye-copy").id) in group.children)
        assertNotNull(preview.drawable("eye-copy").parentDeformerId)
        val pixels = preview.frame().argb
        assertEquals(0x646464, pixels[17 * 48 + 14] and 0xffffff)
        assertTrue((pixels[17 * 48 + 29] ushr 16 and 0xff) in 58..62)
    }

    @Test fun sourceFringeShadowClipsMultiplyToTheMainFace() {
        val shading = intArrayOf(128, 192, 128)
        val skin = intArrayOf(192, 128, 96)
        val ear = intArrayOf(100, 80, 60)
        val preview = preview(listOf(
            box("shadow", "painted shadow", 1, LayerBounds(6, 6, 36, 34), shading, "shadows"),
            box("face-small", "face", 2, LayerBounds(18, 18, 4, 4), skin),
            box("face", "face", 3, LayerBounds(16, 16, 24, 24), skin),
            box("ear", "ear-r", 4, LayerBounds(1, 12, 18, 24), ear),
        ), listOf(WorkspaceSourceGroup("shadows", "Fringe Cast Shadow", true, 1f, false, LayerBlend.Multiply, false)))
        val group = preview.rig.puppet.parts.single { it.name == "Fringe Cast Shadow" }
        assertEquals(BlendMode.MultiplyPremultiplied, group.composite.blendMode)
        assertEquals(listOf(preview.drawable("face").id), group.composite.maskedBy)
        val pixels = preview.frame().argb
        assertEquals(0x64503c, pixels[23 * 48 + 10] and 0xffffff)
        val onFace = pixels[25 * 48 + 25]
        assertTrue((onFace ushr 16 and 0xff) in 95..98 && (onFace ushr 8 and 0xff) in 95..98 && (onFace and 0xff) in 47..49)
    }
}
