package io.github.psd2live.core

import org.umamo.runtime.model.DrawableMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RasterMeshCoverageTest {
    private val pixel = byteArrayOf(0, 0, 0, 1)
    private val square = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    private fun mesh(vararg triangles: Int) = DrawableMesh(square, square, triangles)

    @Test
    fun coveringOnlyTheCenterDoesNotCoverThePixel() {
        assertEquals(1f, RasterMeshCoverage.uncovered(1, 1, pixel, listOf(mesh(0, 1, 2))))
    }

    @Test
    fun twoAdjacentTrianglesCoverTheWholePixel() {
        assertEquals(0f, RasterMeshCoverage.uncovered(1, 1, pixel, listOf(mesh(0, 1, 2, 0, 2, 3))))
    }

    @Test
    fun overlappingFragmentsCannotHideAnUncoveredCorner() {
        assertEquals(1f, RasterMeshCoverage.uncovered(1, 1, pixel, listOf(mesh(0, 1, 2), mesh(0, 1, 2))))
    }

    @Test
    fun aFaintPixelAtTheFarEdgeOfALargeRasterIsNotSkipped() {
        val rgba = ByteArray(1024 * 65 * 4).also { it[it.lastIndex] = 1 }
        assertEquals(1f, RasterMeshCoverage.uncovered(1024, 65, rgba, emptyList()))
    }

    @Test
    fun anEmptyRasterHasNoCoverageRatio() {
        assertNull(RasterMeshCoverage.uncovered(1, 1, ByteArray(4), emptyList()))
    }
}
