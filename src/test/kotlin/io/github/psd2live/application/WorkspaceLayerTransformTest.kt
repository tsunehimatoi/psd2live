package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/**
 * The bow tie: an image is imported, moved and scaled, put under the head's rotation, turned, carried through a
 * regeneration, saved and reopened - one history node per step, its pixels never resampled, its mesh where the user
 * put it.
 */
class WorkspaceLayerTransformTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val evaluator = CpuDeformationEvaluator()

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

    /** Canvas positions (y down) of [id]'s one mesh at rest. */
    private fun canvas(model: RigPreviewModel, id: String): FloatArray {
        val drawable = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }
        val world = evaluator.evaluate(model.rig.puppet, emptyMap()).worldPositions.getValue(drawable.id)
        return FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
    }

    private fun assertMapped(before: FloatArray, after: FloatArray, transform: LayerTransform, message: String) {
        assertEquals(before.size, after.size)
        for (i in 0 until before.size / 2) {
            val x = before[i * 2]; val y = before[i * 2 + 1]
            assertTrue(abs(transform.x(x, y) - after[i * 2]) < 0.1f && abs(transform.y(x, y) - after[i * 2 + 1]) < 0.1f,
                "$message: vertex $i at ${after[i * 2]}, ${after[i * 2 + 1]}, expected ${transform.x(x, y)}, ${transform.y(x, y)}")
        }
    }

    @Test fun anImportedImageMovesScalesTurnsAndChangesParentWithoutLosingItsPlaceOrPixels() = runBlocking<Unit> {
        val layers = listOf(
            ellipse("body", "body", 0, 100, 230, 50, 60),
            ellipse("face", "face", 1, 100, 110, 50, 60),
            ellipse("eye_l", "eye white L", 2, 120, 100, 12, 7),
            ellipse("eye_r", "eye white R", 3, 80, 100, 12, 7),
            ellipse("mouth", "mouth", 4, 100, 140, 10, 4),
        )
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "bow-tie", document, builder.build(document))
        val commands = WorkspaceDocumentCommands(runtime)

        // A 400 x 200 bow tie, much denser than the canvas: fitted in, every pixel kept.
        val pixels = ByteArray(400 * 200 * 4) { if (it % 4 == 3) -1 else 77 }
        val file = temporary.resolve("bow.png").also { Files.write(it, PngCodec.write(RasterImage(400, 200, pixels))) }
        val nodes = runtime.history().selections.size
        val start = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(start.projectId, start.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        assertEquals(nodes + 1, runtime.history().selections.size, "the import is one history node")
        val imported = runtime.capture()
        val tile = imported.model.atlas.placementByLayerId.getValue(id)

        suspend fun transform(request: JsonObject.() -> JsonObject = { this }, fields: JsonObjectBuilder.() -> Unit): WorkspaceCapture<RigPreviewModel> {
            val before = runtime.capture()
            return commands.execute(before.projectId, before.state, "Transform", listOf(WorkspaceDocumentOperation(WorkspaceLayerTransform.OP,
                buildJsonObject { put("layer_id", id); fields() }.request())), MutationAuthor.USER).capture
        }

        // Move onto the head and halve it.
        val beforeMove = canvas(imported.model, id)
        val moveScale = WorkspaceLayerTransform.transform(buildJsonObject { putJsonArray("translate") { add(20); add(-30) }; put("scale", 0.5) })
        val moved = transform { putJsonArray("translate") { add(20); add(-30) }; put("scale", 0.5) }
        assertMapped(beforeMove, canvas(moved.model, id), moveScale, "moved and scaled")
        val layer = moved.document.source.layers.single { it.id.raw == id }
        assertEquals(moveScale, layer.transform, "the layer remembers where its pixels show")
        assertContentEquals(pixels, layer.raster.rgba, "pixels are never resampled")
        assertEquals(tile, moved.model.atlas.placementByLayerId.getValue(id), "the texture tile stays")
        assertEquals(imported.document.generationSource, moved.document.generationSource, "nothing is regenerated")

        // Under the head's rotation, the image stays where it is.
        val mesh = moved.model.rig.puppet.drawables.single { moved.model.rig.layerIdByDrawableId[it.id.raw] == id }.id.raw
        val head = "DeformHeadRotation"
        assertTrue(moved.model.rig.puppet.deformers.any { it.id.raw == head }, "fixture has a head rotation")
        val edit = assertNotNull(WorkspaceHierarchyEdits.reparent(moved.model.rig.puppet, mesh, head))
        val bound = commands.executeJournal(moved.projectId, moved.state, "Reparent", WorkspaceHierarchyEdits.journal(edit), MutationAuthor.USER).capture
        assertEquals(head, bound.model.rig.puppet.drawables.single { it.id.raw == mesh }.parentDeformerId?.raw)
        assertMapped(canvas(moved.model, id), canvas(bound.model, id), LayerTransform.IDENTITY, "reparented")

        // Turned 30 degrees about its centre, under the rotation.
        val points = canvas(bound.model, id)
        val cx = points.filterIndexed { i, _ -> i % 2 == 0 }.average().toFloat(); val cy = points.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat()
        val turned = transform { put("rotate", 30); putJsonArray("pivot") { add(cx); add(cy) } }
        val angle = Math.toRadians(30.0)
        val turn = LayerTransform(cos(angle).toFloat(), sin(angle).toFloat(), -sin(angle).toFloat(), cos(angle).toFloat(), 0f, 0f).let { r ->
            LayerTransform(r.a, r.b, r.c, r.d, cx - (r.a * cx + r.c * cy), cy - (r.b * cx + r.d * cy))
        }
        assertMapped(points, canvas(turned.model, id), turn, "turned")
        assertFalse(turned.document.source.layers.single { it.id.raw == id }.transform.isAxisAligned)

        // A regeneration keeps it: the generators never read the image, and its mesh is the user's.
        val regenerated = commands.execute(turned.projectId, turned.state, "Head", listOf(WorkspaceDocumentOperation("settings_update",
            buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })), MutationAuthor.USER).capture
        assertMapped(canvas(turned.model, id), canvas(regenerated.model, id), LayerTransform.IDENTITY, "regenerated")
        assertEquals(1, regenerated.model.rig.puppet.drawables.count { regenerated.model.rig.layerIdByDrawableId[it.id.raw] == id })

        // A cold build, and the project saved and opened again, agree.
        assertMapped(canvas(regenerated.model, id), canvas(builder.build(regenerated.document), id), LayerTransform.IDENTITY, "cold build")
        val store = WorkspaceStore(temporary.resolve("store"))
        store.persistHistory("bow-tie", runtime.history())
        val reopened = assertNotNull(WorkspaceStore(temporary.resolve("store")).loadHistory("bow-tie")).head().snapshot
        assertEquals(regenerated.document.source.layers.single { it.id.raw == id }.transform, reopened.source.layers.single { it.id.raw == id }.transform)
        assertEquals(WorkspaceRevisions.of(regenerated.document), WorkspaceRevisions.of(reopened))

        // Every step is one node, and undo walks back through them to before the import.
        assertEquals(nodes + 5, runtime.history().selections.size)
        runtime.checkout(regenerated.projectId, regenerated.state, start.historyHead)
        assertTrue(runtime.capture().document.source.layers.none { it.id.raw == id })
    }

    @Test fun aPsdLayerMovesAsTheUsersEditAndKeepsItsPlaceThroughARegeneration() = runBlocking<Unit> {
        val layers = listOf(ellipse("body", "body", 0, 100, 230, 50, 60), ellipse("face", "face", 1, 100, 110, 50, 60))
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "psd-layer", document, builder.build(document))
        val commands = WorkspaceDocumentCommands(runtime)
        val before = runtime.capture()
        val shift = LayerTransform(1f, 0f, 0f, 1f, 7f, 3f)
        val moved = commands.execute(before.projectId, before.state, "Move", listOf(WorkspaceDocumentOperation(WorkspaceLayerTransform.OP,
            buildJsonObject { put("layer_id", "body"); putJsonArray("matrix") { shift.toList().forEach { add(it) } } })), MutationAuthor.USER).capture
        assertMapped(canvas(before.model, "body"), canvas(moved.model, "body"), shift, "moved")
        assertEquals(before.document.source.layers.single { it.id.raw == "body" }.bounds, moved.document.source.layers.single { it.id.raw == "body" }.bounds)
        val regenerated = commands.execute(moved.projectId, moved.state, "Head", listOf(WorkspaceDocumentOperation("settings_update",
            buildJsonObject { putJsonObject("changes") { put("bodyStrength", 2) } })), MutationAuthor.USER).capture
        assertMapped(canvas(moved.model, "body"), canvas(regenerated.model, "body"), LayerTransform.IDENTITY, "kept through the regeneration")
    }

    /** Rebuilding a moved image's mesh makes it again where the image shows, not where it was imported. */
    @Test fun aRebuiltMeshStaysWhereTheLayerWasMoved() = runBlocking<Unit> {
        val layers = listOf(ellipse("body", "body", 0, 100, 230, 50, 60), ellipse("face", "face", 1, 100, 110, 50, 60))
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "rebuild", document, builder.build(document))
        val commands = WorkspaceDocumentCommands(runtime)
        val disc = ByteArray(64 * 64 * 4).also { rgba ->
            for (y in 0 until 64) for (x in 0 until 64) if ((x - 31.5) * (x - 31.5) + (y - 31.5) * (y - 31.5) < 30 * 30) {
                val i = (y * 64 + x) * 4; rgba[i] = 9; rgba[i + 3] = -1
            }
        }
        val file = temporary.resolve("disc.png").also { Files.write(it, PngCodec.write(RasterImage(64, 64, disc))) }
        val start = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(start.projectId, start.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        val before = runtime.capture()
        val moved = commands.execute(before.projectId, before.state, "Move", listOf(WorkspaceDocumentOperation(WorkspaceLayerTransform.OP,
            buildJsonObject { put("layer_id", id); putJsonArray("translate") { add(30); add(40) }; put("scale", 1.5) })), MutationAuthor.USER).capture
        fun box(points: FloatArray) = listOf(points.filterIndexed { i, _ -> i % 2 == 0 }.min(), points.filterIndexed { i, _ -> i % 2 == 1 }.min(),
            points.filterIndexed { i, _ -> i % 2 == 0 }.max(), points.filterIndexed { i, _ -> i % 2 == 1 }.max())
        val shown = box(canvas(moved.model, id))
        val rebuilt = commands.execute(moved.projectId, moved.state, "Rebuild", listOf(WorkspaceDocumentOperation(WorkspaceRasterEdits.REBUILD_MESH,
            buildJsonObject { put("layer_id", id) })), MutationAuthor.USER).capture
        assertNotEquals(moved.document.rigEdits.authoringJournal.size, rebuilt.document.rigEdits.authoringJournal.size, "the rebuild is recorded")
        val after = box(canvas(rebuilt.model, id))
        assertTrue(shown.indices.all { abs(shown[it] - after[it]) < 3f }, "rebuilt at $after, shown at $shown")
        assertEquals(moved.document.source.layers.single { it.id.raw == id }.transform, rebuilt.document.source.layers.single { it.id.raw == id }.transform)
        assertMapped(canvas(rebuilt.model, id), canvas(builder.build(rebuilt.document), id), LayerTransform.IDENTITY, "cold build")
    }

    /** Pixels beyond the mesh are reported as not showing, and a rebuild from the pixels brings them in. */
    @Test fun pixelsBeyondTheMeshAreReportedUntilARebuild() = runBlocking<Unit> {
        val layers = listOf(ellipse("body", "body", 0, 100, 230, 50, 60), ellipse("face", "face", 1, 100, 110, 50, 60))
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "coverage", document, builder.build(document))
        val commands = WorkspaceDocumentCommands(runtime)
        val disc = ByteArray(64 * 64 * 4).also { rgba ->
            for (y in 0 until 64) for (x in 0 until 64) if ((x - 31.5) * (x - 31.5) + (y - 31.5) * (y - 31.5) < 31 * 31) {
                val i = (y * 64 + x) * 4; rgba[i] = 9; rgba[i + 3] = -1
            }
        }
        val file = temporary.resolve("disc.png").also { Files.write(it, PngCodec.write(RasterImage(64, 64, disc))) }
        val start = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(start.projectId, start.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        val imported = runtime.capture()
        assertTrue(assertNotNull(MeshCoverage.uncovered(imported.model, id)) < 0.03f, "an import is covered")
        // Its corners filled in, as a paint that keeps the mesh would leave them: about a fifth of the pixels show nowhere.
        val painted = commands.executeCandidate(imported.projectId, imported.state, "Paint", MutationAuthor.USER, mutation = { doc, _ ->
            doc.copy(source = WorkspaceSourceArt(doc.source.widthPx, doc.source.heightPx, doc.source.layers.map { layer ->
                if (layer.id.raw != id) layer else (layer as WorkspaceSourceLayer).copy(raster = org.umamo.format.art.LayerRaster(
                    layer.raster.width, layer.raster.height, ByteArray(layer.raster.rgba.size) { if (it % 4 == 3) -1 else 9 }))
            }, doc.source.groups))
        }).capture
        val missing = assertNotNull(MeshCoverage.uncovered(painted.model, id))
        assertTrue(missing in 0.12f..0.3f, "uncovered $missing")
        val rebuilt = commands.execute(painted.projectId, painted.state, "Rebuild", listOf(WorkspaceDocumentOperation(WorkspaceRasterEdits.REBUILD_MESH,
            buildJsonObject { put("layer_id", id) })), MutationAuthor.USER).capture
        assertTrue(assertNotNull(MeshCoverage.uncovered(rebuilt.model, id)) < 0.03f, "the rebuild covers the pixels")
    }
}
