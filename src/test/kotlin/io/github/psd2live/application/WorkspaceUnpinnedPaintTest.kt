package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.*
import kotlin.test.*

/**
 * A layer added after the generation input was frozen generates from its current pixels. Changing them pins the
 * previous pixels first, so a repaint keeps its mesh and a rebuild migrates from the mesh it had.
 */
class WorkspaceUnpinnedPaintTest {
    private val builder = WorkspacePreviewBuilder()

    private fun opaque(width: Int, height: Int) = LayerRaster(width, height, ByteArray(width * height * 4) { if (it % 4 == 3) -1 else 110 })

    private fun layer(id: String, order: Int, bounds: LayerBounds, raster: LayerRaster, rect: LayerCanvasRect? = null) = WorkspaceSourceLayer(
        LayerId(id), id, "", SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        raster, null, null, false, rect)

    /**
     * A document whose generation input was frozen before the "added" layer existed; a [dense] one holds two
     * raster pixels per canvas unit on a fractional rectangle.
     */
    private fun document(dense: Boolean): WorkspaceDocument {
        val body = layer("body", 0, LayerBounds(10, 20, 40, 56), opaque(40, 56))
        val added = if (dense) layer("added", 1, LayerBounds(64, 16, 48, 48), opaque(94, 94), LayerCanvasRect(64.5f, 16.5f, 47f, 47f))
            else layer("added", 1, LayerBounds(64, 16, 48, 48), opaque(48, 48))
        val config = PipelineConfig(atlasSize = 2048, meshSpacing = 8, meshOnly = true, generatePhysics = false, exportMoc3 = false)
        return WorkspaceDocument(WorkspaceSourceArt(128, 96, listOf(body, added), emptyList()), emptyMap(), emptySet(),
            mapOf("added" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config), generationSource = WorkspaceSourceArt(128, 96, listOf(body), emptyList()))
    }

    /** The left half of the added layer: erasing the rest shrinks the shape a mesh would be generated from. */
    private fun erased(dense: Boolean) = if (dense) WorkspacePaintRaster("added", opaque(40, 94), rebuildMesh = false,
        rect = LayerCanvasRect(64.5f, 16.5f, 20f, 47f))
        else WorkspacePaintRaster("added", opaque(20, 48), rebuildMesh = false, rect = LayerCanvasRect(64f, 16f, 20f, 48f))

    private fun mesh(model: RigPreviewModel) =
        requireNotNull(model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "added" }.mesh)

    @Test fun aRepaintWithoutRebuildKeepsTheMeshOfALayerMissingFromTheGenerationInput() = runBlocking<Unit> {
        for (dense in listOf(false, true)) {
            val document = document(dense)
            val model = builder.build(document)
            val painted = WorkspaceRasterEdits.prepare(document, model, erased(dense))
            val pinned = assertNotNull(painted.generationSource).layers.single { it.id.raw == "added" }
            assertEquals(LayerBounds(64, 16, 48, 48), pinned.bounds)
            assertEquals(48, pinned.raster.width, "pinned at canvas resolution")
            if (!dense) assertContentEquals(document.source.layers.single { it.id.raw == "added" }.raster.rgba, pinned.raster.rgba)
            assertEquals(if (dense) 40 else 20, painted.source.layers.single { it.id.raw == "added" }.raster.width)
            val after = builder.build(painted)
            assertContentEquals(mesh(model).positions, mesh(after).positions, "dense=$dense")
            assertContentEquals(mesh(model).indices, mesh(after).indices, "dense=$dense")
        }
    }

    @Test fun aRebuildOfALayerMissingFromTheGenerationInputMigratesFromItsMesh() = runBlocking<Unit> {
        for (dense in listOf(false, true)) {
            val document = document(dense)
            val model = builder.build(document)
            val rebuilt = WorkspaceRasterEdits.prepare(document, model, erased(dense).copy(rebuildMesh = true))
            assertTrue(rebuilt.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
            // Replaying the migration needs the base mesh it was recorded against.
            val after = builder.build(rebuilt)
            assertTrue(mesh(after).vertexCount < mesh(model).vertexCount, "dense=$dense: ${mesh(after).vertexCount} < ${mesh(model).vertexCount}")
        }
    }

    @Test fun replacingTheImageOfALayerMissingFromTheGenerationInputKeepsItsMesh() = runBlocking<Unit> {
        val document = document(dense = false)
        val model = builder.build(document)
        val replaced = LayerImageReplace.replace(document, "added", LayerRaster(48, 48, ByteArray(48 * 48 * 4) {
            if (it % 4 == 3 && (it / 4) % 48 < 12) -1 else 0 }), LayerImageReplace.Fit.STRETCH)
        assertContentEquals(mesh(model).positions, mesh(builder.build(replaced)).positions)
    }
}
