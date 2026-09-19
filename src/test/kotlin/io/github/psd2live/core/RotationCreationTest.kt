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
    private fun command() = buildJsonObject {
        put("op", "canvas_create_rotation"); put("id", "new"); put("name", "New")
        put("preservePose", true); put("angle", 37f)
        put("origin", JsonArray(listOf(JsonPrimitive(120f), JsonPrimitive(-53f))))
        put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
    }
    private fun verify(parent: Deformer?) {
        val mesh = drawable(parent?.id, parent is Deformer.Warp)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), listOfNotNull(parent),
            listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val created = CanvasEdits.apply(source, command())
        assertContentEquals(mesh.mesh!!.uvs, created.drawables.single().mesh!!.uvs)
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .25f, .5f, 1f)) {
            val before = evaluator.evaluate(source, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            val after = evaluator.evaluate(created, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            before.indices.forEach { assertEquals(before[it], after[it], .002f, "pose=$value component=$it") }
        }
        // The source is immutable; replay produces the same result.
        val replay = CanvasEdits.apply(source, command())
        assertContentEquals(created.drawables.single().mesh!!.positions, replay.drawables.single().mesh!!.positions)
    }
    @Test fun unparentedMeshKeepsItsPoses() = verify(null)
    @Test fun warpedBranchKeepsItsPoses() = verify(Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
        grid(WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)),
            WarpLatticeForm(floatArrayOf(-10f, 10f, 120f, 15f, 5f, 80f, 90f, 110f)))))

    @Test fun rotatedBranchKeepsItsPoses() = verify(Deformer.Rotation(DeformerId("rotation"), "Rotation", null, null, 12f,
        grid(RotationPivotForm(13f, 9f, -20f, .7f), RotationPivotForm(30f, 6f, 65f, 1.3f))))
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

