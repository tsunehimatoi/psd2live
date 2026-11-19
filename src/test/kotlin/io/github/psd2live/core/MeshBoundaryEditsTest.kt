package io.github.psd2live.core

import org.umamo.edit.MeshElement
import org.umamo.edit.MeshManualOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.*

class MeshBoundaryEditsTest {
    private fun mesh(positions: FloatArray, vararg indices: Int) =
        DrawableMesh(positions, positions.map { it / 10f }.toFloatArray(), indices)

    @Test fun connectStitchesBothIslandEdgesAndPreservesEveryExistingVertex() {
        val original = mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 2f, 0f, 3f, 0f, 3f, 1f, 2f, 1f),
            0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7)
        val result = assertNotNull(CanvasTopology.build(original, "connect", setOf(1, 4))).edit
        val joined = result.newMesh
        assertContentEquals(original.positions, joined.positions)
        assertContentEquals(original.uvs, joined.uvs)
        assertContentEquals(original.indices, joined.indices.copyOfRange(0, original.indices.size))
        assertEquals(original.triangleCount + 2, joined.triangleCount)
        assertEquals(List(8) { VertexSource.FromOld(it) }, result.vertexSources)
        assertTrue(MeshManualOps.isLegal(joined))
        assertEquals(8, MeshTopology.connectedVertices(MeshTopology.buildVertexAdjacency(8, joined.indices), 1).size)
        assertTrue(MeshElement.Edge.of(1, 4) in MeshTopology.uniqueEdges(joined.indices))
        assertNull(CanvasTopology.build(joined, "connect", setOf(1, 4)))
        assertEquals("editor.topologyRefused.existingEdge", CanvasTopology.refusalKey(joined, "connect", setOf(1, 4)))

        // A third island occupies the only route between the picked endpoints.
        val blocked = mesh(original.positions + floatArrayOf(1.4f,-1f, 1.6f,-1f, 1.5f,2f),
            *original.indices, 8, 9, 10)
        assertNull(CanvasTopology.build(blocked, "connect", setOf(1, 4)))
        assertEquals("editor.topologyRefused.connect", CanvasTopology.refusalKey(blocked, "connect", setOf(1, 4)))
    }

    @Test fun deletingAnEarExposesOneEdgeWithoutTryingToTriangulateIt() {
        val sheet = mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0, 1, 2, 0, 2, 3)
        val result = assertNotNull(CanvasTopology.build(sheet, "delete", setOf(1))).edit
        assertEquals(listOf(0, 2, 3).map { VertexSource.FromOld(it) }, result.vertexSources)
        assertContentEquals(intArrayOf(0, 1, 2), result.newMesh.indices)
        assertTrue(MeshManualOps.isLegal(result.newMesh))
        assertNull(CanvasTopology.build(result.newMesh, "delete", setOf(1)))
        assertEquals("editor.topologyRefused.minimumVertices", CanvasTopology.refusalKey(result.newMesh, "delete", setOf(1)))
    }

    @Test fun deletingTheCentreOfAFanCanLeaveAFilledPolygonWithNoOldFaces() {
        val fan = mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, .5f, .5f),
            0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4)
        val filled = assertNotNull(CanvasTopology.build(fan, "delete", setOf(4))).edit.newMesh
        assertEquals(4, filled.vertexCount)
        assertEquals(2, filled.triangleCount)
        assertTrue(MeshManualOps.isLegal(filled))
    }
}
