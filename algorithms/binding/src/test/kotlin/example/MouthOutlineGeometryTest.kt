package example

import io.github.psd2live.algorithms.binding.MouthContour
import io.github.psd2live.algorithms.binding.MouthStrokeMesh
import kotlin.test.*

class MouthOutlineGeometryTest {
    @Test fun contourAndRibbonOperateOnCapturedArraysWithoutHostTypes() {
        val points = floatArrayOf(0f, 0f, 10f, 0f, 10f, 4f, 0f, 4f)
        val triangles = intArrayOf(0, 1, 2, 0, 2, 3)
        val originalPoints = points.copyOf()
        val originalTriangles = triangles.copyOf()
        val boundary = MouthContour.extractBoundaryLoop(points, triangles)
        assertEquals(setOf(0f to 0f, 10f to 0f, 10f to 4f, 0f to 4f), boundary.toSet())
        val columns = MouthContour.uniformColumns(points, triangles)
        assertEquals(25, columns.size)
        assertEquals(columns.first().top, columns.first().bottom)
        assertEquals(columns.last().top, columns.last().bottom)
        for (side in 0..1) {
            val path = MouthContour.crossedPath(columns, side)
            val overlap = MouthContour.overlapCount(columns.size)
            val joins = listOf(overlap, overlap + columns.lastIndex)
            val ribbon = MouthStrokeMesh.positions(path, .75f, joins)
            val indices = MouthStrokeMesh.indices(path.size, joins)
            assertTrue(ribbon.all(Float::isFinite))
            assertTrue(indices.isNotEmpty() && indices.size % 3 == 0)
            assertTrue(indices.all { it in 0 until ribbon.size / 2 })
            assertContentEquals(indices, MouthStrokeMesh.indices(path.size, joins))
        }
        assertContentEquals(originalPoints, points)
        assertContentEquals(originalTriangles, triangles)
    }

    @Test fun straightRibbonKeepsExactWidthAndRoundedEndCaps() {
        val positions = MouthStrokeMesh.positions(listOf(0f to 0f, 10f to 0f), 2f)
        assertContentEquals(floatArrayOf(0f, -2f, 0f, 2f, 10f, -2f, 10f, 2f), positions.copyOfRange(0, 8))
        val xs = positions.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(-2f, xs.min(), 1e-5f)
        assertEquals(12f, xs.max(), 1e-5f)
    }
}
