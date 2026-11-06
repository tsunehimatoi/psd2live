package example

import io.github.psd2live.core.*
import org.umamo.runtime.model.*
import kotlin.test.*

class SwingEngineTest {
    @Test fun standaloneGenerationKeepsRestAndPinnedEdge() {
        val points = floatArrayOf(0f, 0f, 10f, 0f, 0f, 30f, 10f, 30f)
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points)))))
        val input = PuppetModel(emptyList(), emptyList(), listOf(warp), emptyList(), emptyList(), null)
        val edit = RigSwingEdit.single("swing", "Swing", SwingKind.LATERAL, listOf("warp"), listOf("angle"),
            fulcrum = SwingFulcrum.TOP)
        assertEquals(edit, RigSwingEdit.fromJson(edit.toJson()))
        val (result, issues) = SwingGenerator.applyOne(input, edit)
        assertTrue(issues.isEmpty())
        val grid = (result.deformers.single() as Deformer.Warp).geometryGrid!!
        assertEquals(3, grid.cells.size)
        assertContentEquals(points, grid.cells.single { it.coordinate.contentEquals(intArrayOf(1)) }.form.controlPoints)
        grid.cells.forEach { cell ->
            assertContentEquals(points.copyOfRange(0, 4), cell.form.controlPoints.copyOfRange(0, 4))
        }
        assertTrue(grid.cells.any { !it.form.controlPoints.contentEquals(points) })
        assertTrue(input.parameters.isEmpty())
        assertEquals(1, warp.geometryGrid!!.cells.size)
    }
}
