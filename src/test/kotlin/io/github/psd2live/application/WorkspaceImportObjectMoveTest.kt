package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.render.eval.CpuDeformationEvaluator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** An imported image moved and scaled in Select mode stays where it is when the hierarchy drags it under a deformer. */
class WorkspaceImportObjectMoveTest {
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

    private fun world(capture: WorkspaceCapture<RigPreviewModel>, id: String): FloatArray {
        val model = capture.model
        val drawable = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id }
        return assertNotNull(evaluator.evaluate(model.rig.puppet, emptyMap()).worldPositions[drawable.id])
    }

    @Test fun aMovedImportKeepsItsPlaceWhenDraggedUnderADeformer() = runBlocking<Unit> {
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
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "move-project", document, builder.build(document))
        val commands = WorkspaceDocumentCommands(runtime)

        val file = temporary.resolve("patch.png").also { Files.write(it, PngCodec.write(RasterImage(400, 400, ByteArray(400 * 400 * 4) { -1 }))) }
        val imported = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(imported.projectId, imported.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        // Select mode moves and widens the whole mesh at the pose it shows: one base geometry edit, as the canvas commits it.
        val placed = runtime.capture()
        val drawable = placed.model.rig.puppet.drawables.single { placed.model.rig.layerIdByDrawableId[it.id.raw] == id }.id.raw
        val geometry = RigGeometryTools.geometry(placed.model.rig.puppet, "mesh", drawable, emptyMap())
        val points = FloatArray(geometry.points.size) { if (it % 2 == 0) 80f + (geometry.points[it] - 80f) * 1.5f + 10f else geometry.points[it] }
        val command = canvasGeometryCommand(EditHierarchyMode.SELECT, "mesh", drawable,
            canvasDeformationCoordinate(placed.model.rig.puppet, geometry.axes, emptyMap(), emptyList()), points)
        val moved = commands.executeJournal(placed.projectId, placed.state, "Move",
            JsonArray(RigAuthoringJournal.compile(placed.model.rig.puppet, JsonArray(listOf(command))).second), MutationAuthor.USER).capture
        val shown = world(moved, id)
        assertEquals(points.filterIndexed { i, _ -> i % 2 == 0 }.min(), shown.filterIndexed { i, _ -> i % 2 == 0 }.min(), 1e-3f)

        for (parent in listOf("DeformHeadContainer", "DeformHeadRotation", "DeformBodyXY", null)) {
            val before = runtime.capture()
            val edit = assertNotNull(WorkspaceHierarchyEdits.reparent(before.model.rig.puppet, drawable, parent))
            val bound = commands.executeJournal(before.projectId, before.state, "Reparent", WorkspaceHierarchyEdits.journal(edit), MutationAuthor.USER).capture
            assertEquals(parent, bound.model.rig.puppet.drawables.single { it.id.raw == drawable }.parentDeformerId?.raw)
            val after = world(bound, id)
            assertTrue(after.indices.all { abs(after[it] - shown[it]) < 0.1f },
                "under $parent the mesh moved: ${after.take(4)} against ${shown.take(4)}")
            // A rebuild replays the bind against the same parents and lands on the same spot.
            val rebuilt = builder.build(bound.document)
            val replayed = evaluator.evaluate(rebuilt.rig.puppet, emptyMap()).worldPositions.getValue(org.umamo.runtime.model.DrawableId(drawable))
            assertTrue(replayed.indices.all { abs(replayed[it] - shown[it]) < 0.1f }, "replayed under $parent: ${replayed.take(4)}")
        }
    }

    /** Moves [id]'s one mesh by [dx] canvas units in Select mode, as the canvas commits it. */
    private suspend fun move(runtime: WorkspaceRuntime<RigPreviewModel>, id: String, dx: Float): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        val drawable = before.model.rig.puppet.drawables.single { before.model.rig.layerIdByDrawableId[it.id.raw] == id }.id.raw
        val geometry = RigGeometryTools.geometry(before.model.rig.puppet, "mesh", drawable, emptyMap())
        val world = before.model.rig.puppet.let { evaluator.evaluate(it, emptyMap()).worldPositions.getValue(org.umamo.runtime.model.DrawableId(drawable)) }
        val local = geometry.points
        // Root meshes: local positions are canvas positions; translate in that space.
        require(world.indices.all { i -> abs(if (i % 2 == 0) world[i] - local[i] else -world[i] - local[i]) < 1e-2f }) { "fixture mesh must sit at the root" }
        val points = FloatArray(local.size) { local[it] + if (it % 2 == 0) dx else 0f }
        val command = canvasGeometryCommand(EditHierarchyMode.SELECT, "mesh", drawable,
            canvasDeformationCoordinate(before.model.rig.puppet, geometry.axes, emptyMap(), emptyList()), points)
        return WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Move",
            JsonArray(RigAuthoringJournal.compile(before.model.rig.puppet, JsonArray(listOf(command))).second), MutationAuthor.USER).capture
    }

    /**
     * An imported image is the user's mesh: moving it is a mesh edit, before or after a regeneration writes a checkpoint
     * that holds it, and it never gains a second, generated mesh.
     */
    @Test fun anImportMovesLikeAnyMeshAcrossRegenerations() = runBlocking<Unit> {
        val layers = listOf(ellipse("body", "body", 0, 100, 230, 50, 60), ellipse("face", "face", 1, 100, 110, 50, 60))
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { d, previous -> builder.build(d, previous) })
        runtime.install(runtime.state.value.state, "move-project", document, builder.build(document))
        val file = temporary.resolve("patch.png").also { Files.write(it, PngCodec.write(RasterImage(40, 40, ByteArray(40 * 40 * 4) { -1 }))) }
        val imported = runtime.capture()
        val id = WorkspaceImageLayerCommands(runtime).importImages(imported.projectId, imported.state, listOf(file), null, "Import", MutationAuthor.USER)
            .mutation.affectedLayerIds.single()
        suspend fun regenerate(strength: Int) = runtime.capture().let {
            WorkspaceDocumentCommands(runtime).execute(it.projectId, it.state, "Head", listOf(WorkspaceDocumentOperation("settings_update",
                buildJsonObject { putJsonObject("changes") { put("headStrength", strength) } })), MutationAuthor.USER)
        }
        fun left(capture: WorkspaceCapture<RigPreviewModel>) = world(capture, id).filterIndexed { i, _ -> i % 2 == 0 }.min()
        suspend fun checkMove(dx: Float) {
            val before = left(runtime.capture())
            val moved = move(runtime, id, dx)
            assertEquals(before + dx, left(moved), 0.05f, "the mesh moved")
            assertEquals(1, moved.model.rig.puppet.drawables.count { moved.model.rig.layerIdByDrawableId[it.id.raw] == id }, "one mesh")
            val mesh = moved.model.rig.puppet.drawables.single { moved.model.rig.layerIdByDrawableId[it.id.raw] == id }.id
            val replayed = evaluator.evaluate(builder.build(moved.document).rig.puppet, emptyMap()).worldPositions.getValue(mesh)
            assertEquals(left(moved), replayed.filterIndexed { i, _ -> i % 2 == 0 }.min(), 1e-3f, "a cold build agrees")
        }
        checkMove(10f)
        regenerate(2)
        checkMove(15f)
        regenerate(3)
        checkMove(-5f)
        assertEquals(imported.document.generationSource, runtime.capture().document.generationSource, "no generation input is frozen for an image")
    }

    @Test fun aReplacingCreationKeepsTheMeshInItsPlaceOrCreatesIt() = runBlocking<Unit> {
        val layers = listOf(ellipse("body", "body", 0, 100, 230, 50, 60), ellipse("face", "face", 1, 100, 110, 50, 60))
        val config = PipelineConfig(atlasSize = 512, meshSpacing = 16, generatePhysics = false, exportMoc3 = false, meshOnly = true)
        val model = builder.build(WorkspaceDocument(WorkspaceSourceArt(200, 300, layers, emptyList()), emptyMap(), emptySet(), emptyMap(),
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config)))
        val puppet = model.rig.puppet
        val target = puppet.drawables.first().id
        val record = RasterMeshCreation.replacing(RasterMeshCreation.encode(model.rig, target))
        val moved = JsonObject(record + ("positions" to JsonArray(record.getValue("positions").jsonArray.mapIndexed { i, v ->
            JsonPrimitive(v.jsonPrimitive.float + if (i % 2 == 0) 5f else 0f) })))
        val replaced = RasterMeshCreation.replay(puppet, moved)
        assertEquals(puppet.drawables.map { it.id }, replaced.drawables.map { it.id }, "the mesh keeps its place in the draw list")
        assertEquals(puppet.parts.map { it.children }, replaced.parts.map { it.children }, "and in its part")
        assertEquals(puppet.drawables.first().mesh!!.positions[0] + 5f, replaced.drawables.first().mesh!!.positions[0], 1e-4f)
        val removed = puppet.copy(drawables = puppet.drawables.drop(1),
            parts = puppet.parts.map { it.copy(children = it.children - org.umamo.runtime.model.OrgChild.Drawable(target)) },
            rootChildren = puppet.rootChildren - org.umamo.runtime.model.OrgChild.Drawable(target))
        assertTrue(RasterMeshCreation.replay(removed, moved).drawables.any { it.id == target }, "without the mesh it is created")
        assertFailsWith<IllegalArgumentException> { RasterMeshCreation.replay(puppet, JsonObject(moved - "replace")) }
    }
}
