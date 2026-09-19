package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshRefinementOps
import org.umamo.runtime.model.*
import kotlin.test.*

class CanvasTopologyKnifeTest {
    private fun square() = DrawableMesh(
        floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f),
        floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )

    private fun assertUvFollowsPosition(mesh: DrawableMesh) {
        for (vertex in 0 until mesh.vertexCount) {
            assertEquals(mesh.positions[vertex * 2] / 100f, mesh.uvs[vertex * 2], .0002f)
            assertEquals(mesh.positions[vertex * 2 + 1] / 100f, mesh.uvs[vertex * 2 + 1], .0002f)
        }
    }

    @Test fun threeInteriorPointsInSameOriginalFace() {
        val cut = listOf(
            MeshRefinementOps.KnifeAnchor.AtPoint(20f, 10f),
            MeshRefinementOps.KnifeAnchor.AtPoint(60f, 20f),
            MeshRefinementOps.KnifeAnchor.AtPoint(65f, 55f),
        )
        val result = assertNotNull(CanvasTopology.build(square(), "knife", emptySet(), cut))
        assertEquals(7, result.edit.newMesh.vertexCount)
        assertUvFollowsPosition(result.edit.newMesh)
        assertTrue(result.edit.newMesh.indices.all { it in 0 until result.edit.newMesh.vertexCount })
    }

    @Test fun multipleSegmentsAcrossFacesAndExterior() {
        val strokes = listOf(
            listOf(10f to 20f, 55f to 30f, 80f to 60f, 35f to 85f),
            listOf(120f to 20f, 80f to 30f, 30f to 40f),
            listOf(15f to 110f, 45f to 120f, 75f to 115f),
        )
        for (stroke in strokes) {
            val anchors = stroke.map { MeshRefinementOps.KnifeAnchor.AtPoint(it.first, it.second) }
            val result = assertNotNull(CanvasTopology.build(square(), "knife", emptySet(), anchors), "stroke=$stroke")
            assertTrue(result.edit.newMesh.vertexCount >= square().vertexCount + stroke.size)
            assertUvFollowsPosition(result.edit.newMesh)
        }
    }

    @Test fun exteriorFreePointsExpandTheOutline() {
        val cut = listOf(
            MeshRefinementOps.KnifeAnchor.AtVertex(1),
            MeshRefinementOps.KnifeAnchor.AtPoint(130f, 10f),
            MeshRefinementOps.KnifeAnchor.AtPoint(150f, 45f),
        )
        val result = assertNotNull(CanvasTopology.build(square(), "knife", emptySet(), cut))
        val mesh = result.edit.newMesh
        assertEquals(6, mesh.vertexCount)
        assertEquals(130f, mesh.positions[8])
        assertEquals(150f, mesh.positions[10])
        assertUvFollowsPosition(mesh)
        assertTrue(mesh.indices.contains(4) && mesh.indices.contains(5))
    }

    @Test fun freePointNearAnEdgeIsNotMovedOntoIt() {
        val result = assertNotNull(CanvasTopology.build(square(), "knife", emptySet(), listOf(
            MeshRefinementOps.KnifeAnchor.AtVertex(0),
            MeshRefinementOps.KnifeAnchor.AtPoint(50f, -1f),
        )))
        assertEquals(-1f, result.edit.newMesh.positions[9], .0001f)
    }

    @Test fun selectedRegionAndSingleVertexRefineInOneBatch() {
        val mesh = square()
        assertEquals(3, CanvasTopology.subdivisionEdges(mesh, setOf(0)).size)
        val region = CanvasTopology.subdivisionEdges(mesh, setOf(0, 1, 2, 3))
        assertEquals(5, region.size)
        val result = assertNotNull(CanvasTopology.build(mesh, "subdivide", emptySet(), edges = region))
        assertEquals(9, result.edit.newMesh.vertexCount)
        assertUvFollowsPosition(result.edit.newMesh)
        val batch = setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(2, 3))
        assertEquals(6, assertNotNull(CanvasTopology.build(mesh, "split", emptySet(), edges = batch)).edit.newMesh.vertexCount)
        val sharedTriangle = setOf(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(1, 2))
        val twoEdgeResult = assertNotNull(CanvasTopology.build(mesh, "subdivide", emptySet(), edges = sharedTriangle))
        assertEquals(6, twoEdgeResult.edit.newMesh.vertexCount)
        assertUvFollowsPosition(twoEdgeResult.edit.newMesh)
    }

    @Test fun exteriorCutReplaysWithKeyformDeltas() {
        val drawable = Drawable(DrawableId("m"), "Mesh", null, BlendMode.Normal, emptyList(), square(),
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(8) { if (it % 2 == 0) 10f else -5f })))))
        val source = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null)
        val anchors = listOf(MeshRefinementOps.KnifeAnchor.AtVertex(1), MeshRefinementOps.KnifeAnchor.AtPoint(140f, 20f),
            MeshRefinementOps.KnifeAnchor.AtPoint(155f, 60f))
        val edit = buildJsonObject {
            put("op", "canvas_topology"); put("id", "m"); put("action", "knife")
            put("vertices", JsonArray(emptyList())); put("anchors", CanvasTopology.encodeAnchors(anchors))
        }
        val result = CanvasEdits.apply(source, edit).drawables.single()
        val resultMesh = requireNotNull(result.mesh)
        assertEquals(6, resultMesh.vertexCount)
        assertUvFollowsPosition(resultMesh)
        val deltas = result.geometryGrid!!.cells.single().form.positionDeltas
        assertEquals(resultMesh.vertexCount * 2, deltas.size)
        for (index in deltas.indices step 2) {
            assertEquals(10f, deltas[index], .0001f)
            assertEquals(-5f, deltas[index + 1], .0001f)
        }
    }
}


