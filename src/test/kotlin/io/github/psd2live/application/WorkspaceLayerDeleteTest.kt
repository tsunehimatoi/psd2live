package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** layer_delete removes a layer and its meshes for good - one history node, nothing kept aside - and undo brings it back. */
class WorkspaceLayerDeleteTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    private fun ellipse(id: String, name: String, order: Int, cx: Int, cy: Int, rx: Int, ry: Int): WorkspaceSourceLayer {
        val left = cx - rx; val top = cy - ry; val width = rx * 2; val height = ry * 2
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + 0.5f - rx) / rx; val dy = (y + 0.5f - ry) / ry
            if (dx * dx + dy * dy > 1f) continue
            val i = (y * width + x) * 4
            rgba[i] = (40 + order * 20).toByte(); rgba[i + 1] = 90; rgba[i + 2] = 120; rgba[i + 3] = -1
        }
        return WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order, LayerBounds(left, top, width, height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
    }

    private suspend fun runtime(): WorkspaceRuntime<RigPreviewModel> {
        val layers = listOf(
            ellipse("body", "body", 0, 100, 230, 50, 60),
            ellipse("face", "face", 1, 100, 110, 50, 60),
            ellipse("eye_l", "eye white L", 2, 120, 100, 12, 7),
            ellipse("eye_r", "eye white R", 3, 80, 100, 12, 7),
        )
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "delete", document, builder.build(document))
        return runtime
    }

    private suspend fun delete(runtime: WorkspaceRuntime<RigPreviewModel>, id: String): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        return WorkspaceLayerCommands(runtime).execute(before.projectId, before.state, WorkspaceDocumentOperation(WorkspaceLayerEdits.DELETE,
            buildJsonObject { put("layer_id", id) }), "Delete", MutationAuthor.USER).commit.capture
    }

    private fun meshesOf(capture: WorkspaceCapture<RigPreviewModel>, id: String) =
        capture.model.rig.puppet.drawables.filter { capture.model.rig.layerIdByDrawableId[it.id.raw] == id }

    @Test fun aDeletedImageLeavesNothingBehindAndUndoBringsItBack() = runBlocking<Unit> {
        val runtime = runtime()
        val file = temporary.resolve("bow.png").also { Files.write(it, PngCodec.write(RasterImage(40, 20, ByteArray(40 * 20 * 4) { -1 }))) }
        val start = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(start.projectId, start.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        val moved = runtime.capture().let {
            WorkspaceDocumentCommands(runtime).execute(it.projectId, it.state, "Move", listOf(WorkspaceDocumentOperation(WorkspaceLayerTransform.OP,
                buildJsonObject { put("layer_id", id); putJsonArray("translate") { add(5); add(5) } })), MutationAuthor.USER).capture
        }
        val mesh = meshesOf(moved, id).single().id
        val nodes = runtime.history().selections.size
        val deleted = delete(runtime, id)
        assertEquals(nodes + 1, runtime.history().selections.size)
        assertTrue(deleted.document.source.layers.none { it.id.raw == id }, "the layer is gone")
        assertTrue(deleted.model.rig.puppet.drawables.none { it.id == mesh }, "its mesh is gone")
        assertTrue(deleted.document.deletedLayerIds.isEmpty(), "nothing is soft-deleted")
        assertTrue(id !in deleted.document.layerVisibility && id !in deleted.document.layerOverrides)
        val journal = deleted.document.rigEdits.authoringJournal
        assertEquals(LayerDeletionJournal.OP, journal.last()["op"]?.jsonPrimitive?.content)
        assertTrue(RigCheckpoint.isRecord(journal[journal.size - 2]), "checkpointed right before the deletion")
        // A cold build never needs the deleted artwork.
        assertTrue(builder.build(deleted.document).rig.puppet.drawables.none { it.id == mesh })
        runtime.checkout(deleted.projectId, deleted.state, moved.historyHead)
        assertEquals(moved.document, runtime.capture().document)
        assertEquals(1, meshesOf(runtime.capture(), id).size)
    }

    @Test fun aDeletedPsdLayerLeavesTheGenerationInputAndItsMeshes() = runBlocking<Unit> {
        val runtime = runtime()
        val before = runtime.capture()
        val eye = meshesOf(before, "eye_l").map { it.id }
        assertTrue(eye.isNotEmpty())
        val deleted = delete(runtime, "eye_l")
        assertTrue(deleted.document.source.layers.none { it.id.raw == "eye_l" })
        assertTrue(deleted.model.rig.puppet.drawables.none { it.id in eye }, "its meshes are gone")
        assertTrue(deleted.document.generationSource?.layers?.none { it.id.raw == "eye_l" } ?: true, "and it is no generation input")
        assertEquals(meshesOf(before, "eye_r").map { it.id }, meshesOf(deleted, "eye_r").map { it.id }, "the other eye stays")
        assertTrue(builder.build(deleted.document).rig.puppet.drawables.none { it.id in eye })
        // A later regeneration does not bring it back.
        val regenerated = WorkspaceDocumentCommands(runtime).execute(deleted.projectId, deleted.state, "Head", listOf(WorkspaceDocumentOperation(
            "settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })), MutationAuthor.USER).capture
        assertTrue(regenerated.model.rig.puppet.drawables.none { it.id in eye })
    }

    @Test fun theLastLayerCannotBeDeleted() = runBlocking<Unit> {
        val runtime = runtime()
        for (id in listOf("body", "face", "eye_l")) delete(runtime, id)
        val before = runtime.capture()
        assertFailsWith<Exception> { delete(runtime, "eye_r") }
        assertEquals(before.state, runtime.capture().state)
    }
}
