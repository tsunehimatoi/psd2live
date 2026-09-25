package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.MeshRefinementOps
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.test.*

class GlueEditingTest {
    private fun glue(a: String, b: String, vararg pairs: Pair<Int, Int>) =
        Glue(DrawableId(a), DrawableId(b), pairs.map { GluePair(it.first, it.second, 0.5f, 0.5f) })

    @Test fun weldGroupsChainAcrossGlues() {
        val welds = WeldGroups.of(listOf(glue("a", "b", 0 to 3), glue("b", "c", 3 to 7)))
        assertEquals(1, welds.groups.size)
        assertEquals(
            setOf(MeshVertex("a", 0), MeshVertex("b", 3), MeshVertex("c", 7)),
            welds.members(MeshVertex("c", 7)).toSet(),
        )
        assertEquals(listOf(MeshVertex("a", 1)), welds.members(MeshVertex("a", 1)))
    }

    @Test fun weldGroupsNeverHalfSelectAGluedPoint() {
        val welds = WeldGroups.of(listOf(glue("a", "b", 0 to 3, 1 to 4)))
        val grown = welds.expand(mapOf("a" to setOf(0, 2)))
        assertEquals(setOf(0, 2), grown["a"])
        assertEquals(setOf(3), grown["b"])
    }

    @Test fun weldGroupsIgnoreStalePairs() {
        val welds = WeldGroups.of(listOf(glue("a", "b", 0 to 3, 1 to 99))) { it.index < 10 }
        assertEquals(1, welds.groups.size)
        assertFalse(welds.isWelded(MeshVertex("a", 1)))
    }

    /** A thin quad split along its long diagonal: the short one is the Delaunay choice. */
    private fun quad(uvAffine: Boolean): DrawableMesh {
        val positions = floatArrayOf(0f, 0f, 10f, 0f, 20f, 1f, 10f, 2f)
        val uvs = FloatArray(8) { positions[it] / 20f }
        if (!uvAffine) uvs[4] += 0.1f
        return DrawableMesh(positions, uvs, intArrayOf(0, 1, 2, 0, 2, 3))
    }

    @Test fun edgeFlipsTakeTheShortDiagonalWithoutTouchingTheArt() {
        val mesh = quad(uvAffine = true)
        val flipped = MeshRefinementOps.flipTowardDelaunay(mesh, mesh.positions, setOf(0, 1, 2, 3))
        val edges = MeshTopologyEdges.of(flipped)
        assertTrue(org.umamo.edit.MeshElement.Edge.of(1, 3) in edges)
        assertFalse(org.umamo.edit.MeshElement.Edge.of(0, 2) in edges)
        assertSameWinding(mesh.positions, mesh.indices, flipped)
    }

    @Test fun edgeFlipsRefuseAQuadWhoseTextureIsNotOnePlane() {
        val mesh = quad(uvAffine = false)
        assertContentEquals(mesh.indices, MeshRefinementOps.flipTowardDelaunay(mesh, mesh.positions, setOf(0, 1, 2, 3)))
    }

    @Test fun glueBrushLeavesACleanTriangulation() {
        // A strip of A laid across a big triangle B: every stroked vertex of A lands inside B.
        val a = Drawable(DrawableId("strip"), "Strip", null, BlendMode.Normal, emptyList(),
            DrawableMesh(
                floatArrayOf(30f, 40f, 60f, 40f, 90f, 40f, 30f, 50f, 60f, 50f, 90f, 50f),
                FloatArray(12) { 0.1f + it * 0.01f },
                intArrayOf(0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4),
            ), null)
        val coverPoints = floatArrayOf(0f, 0f, 200f, 0f, 0f, 200f)
        val b = Drawable(DrawableId("cover"), "Cover", null, BlendMode.Normal, emptyList(),
            DrawableMesh(coverPoints, FloatArray(6) { coverPoints[it] / 200f }, intArrayOf(0, 1, 2)), null)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val stroke = buildJsonObject {
            put("op", "canvas_glue_edit"); put("id", "seam"); put("action", "brush")
            put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 2f)
            put("hits_a", JsonArray((0..5).map(::JsonPrimitive))); put("hits_b", JsonArray(emptyList()))
        }
        val glued = CanvasEdits.apply(source, stroke)
        val cover = glued.drawables.single { it.id == b.id }.mesh!!
        assertEquals(6, glued.glues.single().pairs.size)
        assertEquals(9, cover.vertexCount)
        // Every triangle keeps the original winding and a real area, and no triangle sticks out of B.
        assertSameWinding(b.mesh!!.positions, b.mesh.indices, cover.indices, cover.positions)
        for (i in 0 until cover.vertexCount) {
            val x = cover.positions[i * 2]
            val y = cover.positions[i * 2 + 1]
            assertTrue(x >= -1e-3f && y >= -1e-3f && x + y <= 200f + 1e-3f, "vertex $i left the cover")
            assertEquals(x / 200f, cover.uvs[i * 2], 1e-4f)
            assertEquals(y / 200f, cover.uvs[i * 2 + 1], 1e-4f)
        }
        // Each pair shares one point at rest, so the weld moves nothing.
        val world = CpuDeformationEvaluator().evaluate(glued, emptyMap()).worldPositions
        for (pair in glued.glues.single().pairs) {
            assertEquals(world.getValue(a.id)[pair.indexA * 2], world.getValue(b.id)[pair.indexB * 2], 1e-3f)
            assertEquals(world.getValue(a.id)[pair.indexA * 2 + 1], world.getValue(b.id)[pair.indexB * 2 + 1], 1e-3f)
        }
    }

    @Test fun glueBrushNeverGrowsTheSilhouette() {
        val a = Drawable(DrawableId("a"), "A", null, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(-3f, 50f, -40f, 40f, -40f, 60f), FloatArray(6), intArrayOf(0, 1, 2)), null)
        val coverPoints = floatArrayOf(0f, 0f, 200f, 0f, 0f, 200f)
        val b = Drawable(DrawableId("cover"), "Cover", null, BlendMode.Normal, emptyList(),
            DrawableMesh(coverPoints, FloatArray(6) { coverPoints[it] / 200f }, intArrayOf(0, 1, 2)), null)
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(a, b),
            listOf(OrgChild.Drawable(a.id), OrgChild.Drawable(b.id)), null)
        val stroke = buildJsonObject {
            put("op", "canvas_glue_edit"); put("id", "seam"); put("action", "brush")
            put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 6f)
            put("hits_a", JsonArray(listOf(JsonPrimitive(0)))); put("hits_b", JsonArray(emptyList()))
        }
        val glued = CanvasEdits.apply(source, stroke)
        val cover = glued.drawables.single { it.id == b.id }.mesh!!
        // A point 3 px outside B's edge splits that edge and slides the new vertex out to meet it; B gains
        // a vertex, not a triangle.
        assertEquals(4, cover.vertexCount)
        assertEquals(2, cover.indices.size / 3)
        assertEquals(-3f, cover.positions[6], 1e-3f)
        assertEquals(50f, cover.positions[7], 1e-3f)
    }

    private object MeshTopologyEdges {
        fun of(indices: IntArray) = org.umamo.edit.MeshTopology.uniqueEdges(indices).toSet()
    }

    private fun signedArea(p: FloatArray, a: Int, b: Int, c: Int) =
        (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])

    private fun assertSameWinding(before: FloatArray, beforeIndices: IntArray, after: IntArray, afterPositions: FloatArray = before) {
        val sign = signedArea(before, beforeIndices[0], beforeIndices[1], beforeIndices[2])
        for (t in 0 until after.size / 3) {
            val area = signedArea(afterPositions, after[t * 3], after[t * 3 + 1], after[t * 3 + 2])
            assertTrue(area * sign > 0f && abs(area) > 1e-3f, "triangle $t flipped or collapsed")
        }
    }
}
