package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

class RotationCreationTest {
    private val parameter = ParameterId("pose")
    private fun <T> grid(a: T, b: T) = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
        listOf(KeyformCell(intArrayOf(0), a), KeyformCell(intArrayOf(1), b)))
    private fun drawable(parent: DeformerId?, normalized: Boolean = false): Drawable {
        val points = if (normalized) floatArrayOf(.1f, .2f, .8f, .1f, .7f, .9f) else floatArrayOf(20f, 30f, 80f, 20f, 70f, 90f)
        return Drawable(DrawableId("mesh"), "Mesh", parent, BlendMode.Normal, emptyList(),
            DrawableMesh(points, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), intArrayOf(0, 1, 2)),
            grid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(FloatArray(6) { if (normalized) .03f else 3f })))
    }
    private fun command(originX: Float = 0.5f, originY: Float = 0.5f) = buildJsonObject {
        put("op", "canvas_create_rotation"); put("id", "new"); put("name", "New")
        put("preservePose", true); put("angle", 37f)
        put("add_to", "parent_of_selected")
        put("origin", JsonArray(listOf(JsonPrimitive(originX), JsonPrimitive(originY))))
        put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
    }
    private fun verify(parent: Deformer?, originX: Float, originY: Float) {
        val mesh = drawable(parent?.id, parent is Deformer.Warp)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), listOfNotNull(parent),
            listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val created = CanvasEdits.apply(source, command(originX, originY))
        assertContentEquals(mesh.mesh!!.uvs, created.drawables.single().mesh!!.uvs)
        // New rotation sits between the mesh and its former parent.
        assertEquals(parent?.id, created.deformers.single { it.id.raw == "new" }.parent)
        assertEquals(DeformerId("new"), created.drawables.single().parentDeformerId)
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .25f, .5f, 1f)) {
            val before = evaluator.evaluate(source, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            val after = evaluator.evaluate(created, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            before.indices.forEach { assertEquals(before[it], after[it], .002f, "pose=$value component=$it") }
        }
        // The source is immutable; replay produces the same result.
        val replay = CanvasEdits.apply(source, command(originX, originY))
        assertContentEquals(created.drawables.single().mesh!!.positions, replay.drawables.single().mesh!!.positions)
    }
    @Test fun unparentedMeshKeepsItsPoses() = verify(null, 50f, 50f)
    /** Warp parent: mesh lands in pixel-scale rotation-local. Static lattice so mid-poses stay exact. */
    @Test fun warpedBranchKeepsItsPoses() = verify(Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
        KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
            WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)))))), 0.5f, 0.5f)

    @Test fun rotatedBranchKeepsItsPoses() = verify(Deformer.Rotation(DeformerId("rotation"), "Rotation", null, null, 12f,
        grid(RotationPivotForm(13f, 9f, -20f, .7f), RotationPivotForm(30f, 6f, 65f, 1.3f))), 50f, 50f)

    @Test fun parentOfDeformerInsertsAboveAnchor() {
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))))))
        val mesh = drawable(warp.id, normalized = true)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), listOf(warp),
            listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val cmd = buildJsonObject {
            put("op", "canvas_create_rotation"); put("id", "rot"); put("name", "Rot")
            put("preservePose", true); put("angle", 0f)
            put("add_to", "parent_of_deformer"); put("deformer_id", "warp")
            put("origin", JsonArray(listOf(JsonPrimitive(50f), JsonPrimitive(50f))))
        }
        val created = CanvasEdits.apply(source, cmd)
        val rot = created.deformers.single { it.id.raw == "rot" }
        assertNull(rot.parent)
        assertEquals(DeformerId("rot"), created.deformers.single { it.id.raw == "warp" }.parent)
        assertEquals(warp.id, created.drawables.single().parentDeformerId)
    }
    @Test fun glueCreationCompilesAndReplaysWithStableIdentity() {
        val a = drawable(null)
        val b = a.copy(id = DrawableId("other"), name = "Other")
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val command = buildJsonObject {
            put("op", "canvas_create_glue"); put("id", "seam"); put("name", "Seam")
            put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 1f)
        }
        val (created, journal) = RigAuthoringJournal.compile(source, JsonArray(listOf(command)))
        assertEquals(1, journal.size)
        assertEquals("seam", created.glues.single().id)
        assertEquals(3, created.glues.single().pairs.size)
        assertEquals("seam", RigAuthoringJournal.apply(source, journal.single()).glues.single().id)
    }

    @Test fun glueRequiresTwoDifferentMeshes() {
        val mesh = drawable(null)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val command = buildJsonObject {
            put("op", "canvas_create_glue"); put("id", "seam"); put("mesh_a", mesh.id.raw); put("mesh_b", mesh.id.raw)
        }
        val failure = assertFailsWith<IllegalArgumentException> { CanvasEdits.apply(source, command) }
        assertEquals("Glue requires two different meshes", failure.message)
    }

    @Test fun glueRejectsASecondBindingUnlessReplacing() {
        val a = drawable(null)
        val b = a.copy(id = DrawableId("other"), name = "Other")
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val command = buildJsonObject {
            put("op", "canvas_create_glue"); put("id", "seam"); put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 1f)
        }
        val created = CanvasEdits.apply(source, command)
        val duplicate = buildJsonObject {
            put("op", "canvas_create_glue"); put("id", "other-seam"); put("mesh_a", b.id.raw); put("mesh_b", a.id.raw); put("distance", 1f)
        }
        assertFailsWith<IllegalArgumentException> { CanvasEdits.apply(created, duplicate) }
        val replaced = CanvasEdits.apply(created, JsonObject(duplicate + ("replace" to JsonPrimitive(true))))
        assertEquals(1, replaced.glues.size)
        assertEquals("seam", replaced.glues.single().id)
        assertEquals(b.id, replaced.glues.single().meshA)
        assertEquals(a.id, replaced.glues.single().meshB)
    }

    @Test fun glueBrushPairsOnlyTheStrokedVertex() {
        val a = drawable(null)
        val b = a.copy(id = DrawableId("other"), name = "Other")
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val command = buildJsonObject {
            put("op", "canvas_glue_edit"); put("id", "seam"); put("action", "brush")
            put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 1f)
            put("hits_a", JsonArray(listOf(JsonPrimitive(0))))
            put("hits_b", JsonArray(emptyList()))
        }
        val glued = CanvasEdits.apply(source, command)
        assertEquals(1, glued.glues.single().pairs.size)
        assertEquals(0, glued.glues.single().pairs.single().indexA)
        assertEquals(0, glued.glues.single().pairs.single().indexB)
    }

    /** A big triangle whose UV is the affine x/200, y/200, so any vertex's UV can be checked against its position. */
    private fun cover(vararg points: Float) = Drawable(DrawableId("cover"), "Cover", null, BlendMode.Normal, emptyList(),
        DrawableMesh(points, FloatArray(points.size) { points[it] / 200f }, intArrayOf(0, 1, 2)),
        grid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(FloatArray(6) { 3f })))

    private fun glueStroke(a: Drawable, b: Drawable, action: String, hitsA: List<Int>, distance: Float = 6f) = buildJsonObject {
        put("op", "canvas_glue_edit"); put("id", "seam"); put("action", action)
        put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", distance)
        put("hits_a", JsonArray(hitsA.map(::JsonPrimitive)))
        put("hits_b", JsonArray(emptyList()))
    }

    private fun assertUvFollowsPosition(mesh: DrawableMesh) {
        mesh.positions.indices.forEach { assertEquals(mesh.positions[it] / 200f, mesh.uvs[it], 1e-4f, "component=$it") }
    }

    @Test fun glueBrushInsertsACoincidentVertexWithoutMovingThePicture() {
        val a = drawable(null)
        val b = cover(0f, 0f, 200f, 0f, 0f, 200f)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val glued = CanvasEdits.apply(source, glueStroke(a, b, "brush", listOf(0)))
        val pair = glued.glues.single().pairs.single()
        val coverMesh = glued.drawables.single { it.id == b.id }.mesh!!
        assertEquals(4, coverMesh.vertexCount)
        assertEquals(0, pair.indexA)
        assertEquals(3, pair.indexB)
        // The old vertices and the texture mapping are untouched; the new vertex samples its own texel.
        assertContentEquals(b.mesh!!.positions, coverMesh.positions.copyOf(6))
        assertUvFollowsPosition(coverMesh)
        // The pair starts on one point, so the weld does nothing at rest.
        val world = CpuDeformationEvaluator().evaluate(glued, emptyMap()).worldPositions
        val before = CpuDeformationEvaluator().evaluate(source, emptyMap()).worldPositions
        assertEquals(world.getValue(a.id)[0], world.getValue(b.id)[6], 1e-3f)
        assertEquals(world.getValue(a.id)[1], world.getValue(b.id)[7], 1e-3f)
        before.getValue(a.id).indices.forEach { assertEquals(before.getValue(a.id)[it], world.getValue(a.id)[it], 1e-3f) }
        // The inserted vertex keeps following the pose, interpolated from its triangle.
        assertEquals(8, glued.drawables.single { it.id == b.id }.geometryGrid!!.cells.last().form.positionDeltas.size)
    }

    @Test fun glueBrushSlidesANearbyVertexCarryingItsUv() {
        val a = drawable(null)
        val b = cover(22f, 31f, 200f, 0f, 0f, 200f)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val glued = CanvasEdits.apply(source, glueStroke(a, b, "brush", listOf(0)))
        val coverMesh = glued.drawables.single { it.id == b.id }.mesh!!
        assertEquals(3, coverMesh.vertexCount)
        assertEquals(0, glued.glues.single().pairs.single().indexB)
        assertEquals(20f, coverMesh.positions[0], 1e-3f)
        assertEquals(30f, coverMesh.positions[1], 1e-3f)
        assertUvFollowsPosition(coverMesh)
    }

    @Test fun glueBrushIgnoresVerticesOutOfReach() {
        val a = drawable(null)
        val b = cover(300f, 300f, 400f, 300f, 300f, 400f)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        assertFailsWith<IllegalArgumentException> { CanvasEdits.apply(source, glueStroke(a, b, "brush", listOf(0, 1, 2), distance = 10f)) }
    }

    @Test fun ungluingTheLastPairRemovesTheGlue() {
        val a = drawable(null)
        val b = cover(0f, 0f, 200f, 0f, 0f, 200f)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val glued = CanvasEdits.apply(source, glueStroke(a, b, "brush", listOf(0, 1)))
        assertEquals(2, glued.glues.single().pairs.size)
        val partial = CanvasEdits.apply(glued, glueStroke(a, b, "unglue", listOf(0)))
        assertEquals(listOf(1), partial.glues.single().pairs.map { it.indexA })
        assertTrue(CanvasEdits.apply(partial, glueStroke(a, b, "unglue", listOf(1))).glues.isEmpty())
    }

    @Test fun layerSelectionRangeFollowsTheAnchor() {
        assertEquals(listOf("b", "c"), layerSelectionRange(listOf("a", "b", "c", "d"), "c", "b"))
        assertEquals(listOf("z"), layerSelectionRange(listOf("a", "b"), null, "z"))
    }
    @Test fun rotationCrossing180KeepsContinuousKeyAngles() {
        val rotation = Deformer.Rotation(DeformerId("r"), "Rotation", null, null, 0f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 179f, 1f)))))
        val source = PuppetModel(emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null)
        val angle = 181f * kotlin.math.PI.toFloat() / 180f
        val edit = buildJsonObject {
            put("op", "canvas_geometry"); put("id", "r"); put("kind", "rotation"); put("key", JsonObject(emptyMap()))
            put("points", JsonArray(listOf(0f, 0f, 100f * kotlin.math.cos(angle), 100f * kotlin.math.sin(angle)).map(::JsonPrimitive)))
        }
        val result = CanvasEdits.apply(source, edit).deformers.single() as Deformer.Rotation
        assertEquals(181f, result.geometryGrid!!.cells.single().form.angle, .001f)
    }
}

