package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlin.test.*

class WorkspaceMouthArtworkTest {
    @org.junit.jupiter.api.io.TempDir lateinit var temp: java.nio.file.Path
    private fun layer(id: String, name: String, group: String, bounds: LayerBounds, visible: Boolean = true,
                      clipped: Boolean = false): WorkspaceSourceLayer {
        val rgba = ByteArray(bounds.width * bounds.height * 4) { 127 }
        for (i in 3 until rgba.size step 4) rgba[i] = -1
        return WorkspaceSourceLayer(LayerId(id), name, group, SourceLayerKind.Raster, visible, 0, bounds,
            1f, clipped, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(bounds.width, bounds.height, rgba), null, null, false)
    }
    private fun document(duplicate: Boolean = false): WorkspaceDocument {
        val hidden = listOf(
            layer("base", "Layer 1", "mouth/interior", LayerBounds(20, 20, 60, 60)),
            layer("upper", "Layer 2", "mouth/interior", LayerBounds(20, 20, 60, 2)),
            layer("lower", "Layer 3", "mouth/interior", LayerBounds(20, 78, 60, 2)),
            layer("tooth", "upper teeth", "mouth/interior", LayerBounds(30, 20, 40, 10), clipped = true),
            layer("tongue", "tongue", "mouth/interior", LayerBounds(35, 50, 30, 30), clipped = true),
            layer("alternate", "Layer 4", "mouth/interior", LayerBounds(30, 30, 20, 20), visible = false))
        val extra = if (!duplicate) emptyList() else hidden.map { it.copy(id = LayerId(it.id.raw + "-copy"), groupPath = "mouth/alternate") }
        val source = WorkspaceSourceArt(100, 100, hidden + extra +
            layer("seam", "upper lip line", "mouth/closed", LayerBounds(20, 49, 60, 2)),
            listOf("mouth/interior", "mouth/alternate").map { WorkspaceSourceGroup(it, it.substringAfterLast('/'), false, 1f,
                false, LayerBlend.Normal, true) })
        return WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay())
    }
    @Test fun selectsOnlyTheCompleteConstructionAndPreservesTheSource() {
        val original = document()
        val result = WorkspaceMouthArtwork.prepare(original)
        assertSame(original.source, result.source)
        assertEquals(setOf("base", "upper", "lower", "tooth", "tongue"), result.layerVisibility.filterValues { it }.keys)
        assertEquals(PartRole.UPPER_LINE, result.layerOverrides["upper"]?.role)
        assertEquals(PartRole.LOWER_LINE, result.layerOverrides["lower"]?.role)
        assertEquals(PartRole.CLOSED_LINE, result.layerOverrides["seam"]?.role)
        assertFalse(result.source.isEffectivelyVisible(result.source.layers.first()))
    }
    @Test fun ambiguousCompleteSetsAreNotEnabledTogether() {
        val original = document(duplicate = true)
        assertEquals(original, WorkspaceMouthArtwork.prepare(original))
    }
    @Test fun separateTeethAndTongueAreOptionalForAnOpening() {
        val original = document().let { document -> document.copy(source = WorkspaceSourceArt(
            document.source.widthPx, document.source.heightPx,
            document.source.layers.filterNot { it.id.raw in setOf("tooth", "tongue") }, document.source.groups)) }
        val result = WorkspaceMouthArtwork.prepare(original)
        assertEquals(setOf("base", "upper", "lower"), result.layerVisibility.filterValues { it }.keys)
        assertEquals(false, result.layerVisibility["seam"])
    }
    @Test fun anExplicitlyHiddenSeamDoesNotTriggerArtworkSelection() {
        val original = document().copy(layerVisibility = mapOf("seam" to false))
        assertEquals(original, WorkspaceMouthArtwork.prepare(original))
    }
    @Test fun explicitLayerChoicesWin() {
        val original = document().copy(layerVisibility = mapOf("tongue" to false),
            layerOverrides = mapOf("upper" to LayerClassificationOverride(tag = SemanticTag.MOUTH, role = PartRole.PLAIN)))
        val result = WorkspaceMouthArtwork.prepare(original)
        assertEquals(false, result.layerVisibility["tongue"])
        assertEquals(PartRole.PLAIN, result.layerOverrides["upper"]?.role)
    }
    @Test fun zeroOpacityLayersAreNotTreatedAsVisibleOrSelected() {
        val zeroSeam = layer("seam", "upper lip line", "mouth/closed", LayerBounds(20, 49, 60, 2)).copy(opacity = 0f)
        val doc = document().let { d ->
            d.copy(source = WorkspaceSourceArt(d.source.widthPx, d.source.heightPx,
                d.source.layers.filterNot { it.id.raw == "seam" } + zeroSeam, d.source.groups))
        }
        assertEquals(doc, WorkspaceMouthArtwork.prepare(doc))
    }
    @Test fun multipleVisibleOverlappingMouthBasesKeepOnlyThePrimaryOne() {
        val primary = layer("m1", "mouth", "", LayerBounds(20, 20, 60, 40))
        val secondary = layer("m2", "mouth 2", "", LayerBounds(25, 25, 50, 20))
        val doc = WorkspaceDocument(WorkspaceSourceArt(100, 100, listOf(primary, secondary), emptyList()),
            emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay())
        val prepared = WorkspaceMouthArtwork.prepare(doc)
        assertEquals(false, prepared.layerVisibility["m2"])
        assertEquals(null, prepared.layerVisibility["m1"])
    }
    @Test fun selectionAndClippingSurviveReopeningAndExport() {
        val original = document().copy(rigEdits = RigEditOverlay(modifierStack = io.github.psd2live.core.modifier.RigModifierStack().toJson()))
        val (candidate, choices) = WorkspacePresetInitialize.prepare(original, WorkspaceInitialModifiers.RECOMMENDED)
        val (initialized, preview) = WorkspacePresetInitialization.prepare(candidate, WorkspaceRasterWork.Direct, choices!!)
        val baseMouth = preview.modifierEvaluation!!.entries.filter { it.instance.type in setOf("mouth", "mouth/fitted") }
        assertTrue(baseMouth.isNotEmpty())
        assertTrue(baseMouth.all { it.status == io.github.psd2live.core.modifier.ModifierStatus.ACTIVE }, baseMouth.toString())
        fun masks(model: RigPreviewModel): Map<String, List<String>> = model.rig.puppet.drawables
            .filter { model.rig.layerIdByDrawableId[it.id.raw] in setOf("tooth", "tongue") && it.textureSourceId == null }
            .associate { it.id.raw to it.maskedBy.map { mask -> mask.raw } }
        fun visibleAncestors(model: RigPreviewModel, id: String) {
            var child: org.umamo.runtime.model.OrgChild = org.umamo.runtime.model.OrgChild.Drawable(org.umamo.runtime.model.DrawableId(id))
            val visited = hashSetOf<org.umamo.runtime.model.PartId>()
            while (true) {
                val parent = model.rig.puppet.parts.singleOrNull { child in it.children } ?: break
                assertTrue(parent.isVisible, "Selected mouth material has a hidden parent")
                assertTrue(visited.add(parent.id))
                child = org.umamo.runtime.model.OrgChild.Part(parent.id)
            }
        }
        val expected = masks(preview)
        assertTrue(expected.size == 2 && expected.values.all { it.size == 1 })
        expected.keys.forEach { visibleAncestors(preview, it) }
        fun paintedPixels(open: Float): Int {
            val visible = initialized.renderVisibleLayers(preview).intersect(preview.rig.layerIdByDrawableId.values.toSet())
            val image = javax.imageio.ImageIO.read(WorkspaceViewRenderer.modelComposite(preview, "mouth",
                mapOf("ParamMouthOpenY" to open), visible, emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 100f, 100f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(200)).png.inputStream())
            return (0 until image.width).sumOf { x -> (0 until image.height).count { y -> image.getRGB(x, y) ushr 24 > 127 } }
        }
        assertTrue(paintedPixels(1f) > paintedPixels(0f) * 2, "Selecting the construction must visibly open its hidden cavity")
        io.github.psd2live.scenario.Studio.run(temp, checks = emptySet()) {
            open(initialized)
            reopen()
            assertEquals(initialized.layerVisibility, now.document.layerVisibility)
            assertEquals(initialized.layerOverrides, now.document.layerOverrides)
            assertEquals(expected, masks(now.model))
        }
        val files = linkedMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
            RigIrCompiler.compile(preview, tileArt = true), io.github.psd2live.format.compile.ExportOptions("mouth")) { path, bytes -> files[path] = bytes }
        val (art, config) = Cmo3ModelImport.prepare(files.getValue("mouth.cmo3"), Cmo3ImportMode.NEW, null, preview.config)
        val imported = PSD2LivePipeline().buildPreview(art, config)
        for ((id, clips) in expected) {
            assertEquals(clips, imported.rig.puppet.drawables.single { it.id.raw == id }.maskedBy.map { it.raw })
            visibleAncestors(imported, id)
        }
    }

}
