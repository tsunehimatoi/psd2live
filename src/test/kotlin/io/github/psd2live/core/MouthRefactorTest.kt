package io.github.psd2live.core

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import java.io.File
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouthRefactorTest {

    @Test
    fun testUniformColumnsArcLength() {
        // Create an ellipse-like boundary loop
        val ellipsePoints = (0 until 32).map { i ->
            val angle = i * 2 * PI / 32
            (100f + 50f * cos(angle).toFloat()) to (200f + 20f * sin(angle).toFloat())
        }
        val cols = MouthContour.splitAndResample(ellipsePoints, segments = 24)
        assertEquals(25, cols.size)
        // Check that points are ordered left to right
        for (i in 0 until cols.lastIndex) {
            assertTrue(cols[i].top.first <= cols[i + 1].top.first + 1e-3f, "Top X should be monotonic: ${cols[i].top.first} <= ${cols[i + 1].top.first}")
            assertTrue(cols[i].bottom.first <= cols[i + 1].bottom.first + 1e-3f, "Bottom X should be monotonic: ${cols[i].bottom.first} <= ${cols[i + 1].bottom.first}")
        }
        // Check that topY is above or equal to bottomY (in canvas coordinates Y points down, so topY <= bottomY)
        for (col in cols) {
            assertTrue(col.topY <= col.bottomY + 1e-3f, "topY (${col.topY}) should be <= bottomY (${col.bottomY})")
        }
    }

    @Test
    fun testMouthStrokeMeshReducedVertices() {
        val path = (0..28).map { i -> (100f + i * 2f) to (200f + sin(i * 0.2f) * 5f) }
        val joins = listOf(2, 26)
        val pos = MouthStrokeMesh.positions(path, radius = 2f, roundJoins = joins)
        val indices = MouthStrokeMesh.indices(path.size, joins)
        // Pos has x,y pairs. Number of vertices:
        val vertexCount = pos.size / 2
        // With 8 segments for caps and joins, vertex count should be under 120 (previously was 400+)
        assertTrue(vertexCount in 50..120, "Vertex count was $vertexCount")
        assertTrue(indices.isNotEmpty())
    }

    @Test
    fun testCmo3ExportAndImportMouthIntegrity() {
        val cmo3File = File("examples/ds/test-out/ds.cmo3")
        if (!cmo3File.exists()) return

        val source = Cmo3.read(cmo3File.readBytes()).root as CModelSource
        val puppet = Cmo3Import.fromModelSource(source)
        val mouthDrawable = puppet.drawables.firstOrNull { it.id.raw.contains("Mouth", ignoreCase = true) && !it.id.raw.contains("lip", ignoreCase = true) }
        if (mouthDrawable != null) {
            val mesh = mouthDrawable.mesh
            assertTrue(mesh != null, "Mouth mesh should not be null")
            val vCount = mesh.positions.size / 2
            println("Mouth mesh vertex count: $vCount")
            // 25 columns * 3 rows = 75 vertices
            assertTrue(vCount in 50..150, "Mouth vertex count should be compact (~75), actual: $vCount")

            // Check deform paths
            val mouthPaths = puppet.deformPaths.filter { it.drawableId == mouthDrawable.id }
            println("Mouth deform paths count: ${mouthPaths.size}")
            assertEquals(2, mouthPaths.size, "Mouth should have 2 deform paths (upper and lower)")
            for (path in mouthPaths) {
                assertEquals(9, path.points.size, "Mouth deform path should have 9 control points")
                assertTrue(path.points.first().corner, "First control point must be corner (left corner)")
                assertTrue(path.points.last().corner, "Last control point must be corner (right corner)")
                for (p in path.points.subList(1, 8)) {
                    assertTrue(!p.corner, "Intermediate control points must be smooth points")
                }
            }

            // Check that at mouthOpen = 0 (closed keyform), mouth height is 0 (exact line)
            val grid = mouthDrawable.geometryGrid
            if (grid != null) {
                val openAxisIndex = grid.axes.indexOfFirst { it.parameterId.raw == "ParamMouthOpenY" }
                if (openAxisIndex >= 0) {
                    val closedCell = grid.cells.firstOrNull { it.coordinate[openAxisIndex] == 0 }
                    if (closedCell != null) {
                        val base = mesh.positions
                        val delta = closedCell.form.positionDeltas
                        val colCount = vCount / 3
                        var maxHeight = 0f
                        for (c in 0 until colCount) {
                            val topY = base[(c * 3) * 2 + 1] + delta[(c * 3) * 2 + 1]
                            val botY = base[(c * 3 + 2) * 2 + 1] + delta[(c * 3 + 2) * 2 + 1]
                            val h = kotlin.math.abs(botY - topY)
                            if (h > maxHeight) maxHeight = h
                        }
                        println("Closed mouth maximum column height: $maxHeight")
                        assertTrue(maxHeight < 1e-3f, "Closed mouth must collapse to a line (max height < 1e-3, actual: $maxHeight)")
                    }
                }
            }
        }

        // Check lip drawables
        val lipDrawables = puppet.drawables.filter { it.id.raw.contains("lip", ignoreCase = true) }
        println("Lip drawables found: ${lipDrawables.size}")
        assertEquals(2, lipDrawables.size, "Should have 2 lip drawables (upper and lower)")
        for (lip in lipDrawables) {
            val lipMesh = lip.mesh
            assertTrue(lipMesh != null, "Lip mesh should not be null")
            val vCount = lipMesh.positions.size / 2
            println("Lip ${lip.id.raw} vertex count: $vCount")
            assertTrue(vCount in 40..150, "Lip vertex count should be compact, actual: $vCount")

            // Test UV bounds
            val uvs = lipMesh.uvs
            assertTrue(uvs.isNotEmpty())
            val minU = (0 until uvs.size step 2).minOf { uvs[it] }
            val maxU = (0 until uvs.size step 2).maxOf { uvs[it] }
            val minV = (1 until uvs.size step 2).minOf { uvs[it] }
            val maxV = (1 until uvs.size step 2).maxOf { uvs[it] }
            assertTrue(minU >= 0f && maxU <= 1f)
            assertTrue(minV >= 0f && maxV <= 1f)
            // UV patch width and height should be reasonable (e.g. less than 0.5 of atlas)
            assertTrue((maxU - minU) < 0.5f, "UV width should be localized, actual: ${maxU - minU}")
            assertTrue((maxV - minV) < 0.5f, "UV height should be localized, actual: ${maxV - minV}")

            // Test DeformPath
            val lipPaths = puppet.deformPaths.filter { it.drawableId == lip.id }
            assertEquals(1, lipPaths.size, "Each lip should have exactly 1 deform path")
            val path = lipPaths.first()
            assertEquals(9, path.points.size, "Deform path should have 9 control points")
            assertTrue(path.points.first().corner, "First control point must be corner (yellow diamond at left mouth corner)")
            assertTrue(path.points.last().corner, "Last control point must be corner (yellow diamond at right mouth corner)")
            for (p in path.points.subList(1, 8)) {
                assertTrue(!p.corner, "Intermediate control points should be smooth curve points")
            }

            // Check that control points progress smoothly from left to right
            val coords = path.points.map { it.position(lipMesh.positions) }
            println("Lip ${lip.id.raw} deform path control point coords: $coords")
            assertTrue(coords.first().first < coords[4].first && coords[4].first < coords.last().first,
                "Control points must span from left corner to right corner")
            for (i in 0 until coords.lastIndex) {
                assertTrue(coords[i + 1].first >= coords[i].first - 1.0f,
                    "Control points should not reverse direction unexpectedly: ${coords[i].first} vs ${coords[i + 1].first}")
            }
        }
    }
}
