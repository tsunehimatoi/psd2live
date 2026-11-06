package example

import io.github.psd2live.algorithms.binding.BindingProjection
import org.umamo.runtime.model.*
import kotlin.test.*

class BindingProjectionTest {
    @Test fun removedAxesRetainInterpolatedGeometryAndCapturedSourcePose() {
        val x = ParameterId("x"); val y = ParameterId("y")
        val grid = KeyformGrid(listOf(KeyformAxis(x, floatArrayOf(0f, 1f)), KeyformAxis(y, floatArrayOf(0f, 1f))),
            (0..1).flatMap { a -> (0..1).map { b ->
                KeyformCell(intArrayOf(a, b), MeshDeltaForm(floatArrayOf(2f + a * 4f + b * 10f, 0f)))
            } })
        val drawable = Drawable(DrawableId("mesh"), "mesh", null, BlendMode.Normal, emptyList(), null, grid)
        val warp = Deformer.Warp(DeformerId("warp"), "warp", null, null, 1, 1, true,
            KeyformGrid(listOf(KeyformAxis(x, floatArrayOf(0f, 1f))), (0..1).map { key ->
                KeyformCell(intArrayOf(key), WarpLatticeForm(FloatArray(8) { key * 8f }))
            }))
        val input = PuppetModel(listOf(Parameter(x, "x", 0f, 1f, .25f), Parameter(y, "y", 0f, 1f, 0f)),
            emptyList(), listOf(warp), listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null)
        val partial = BindingProjection.retain(input, setOf(y))
        assertEquals(listOf(y), partial.parameters.map { it.id })
        val retained = requireNotNull(partial.drawables.single().geometryGrid)
        assertEquals(listOf(y), retained.axes.map { it.parameterId })
        assertEquals(listOf(3f, 13f), retained.cells.sortedBy { it.coordinate[0] }.map { it.form.positionDeltas[0] })
        val lattice = requireNotNull((partial.deformers.single() as Deformer.Warp).geometryGrid)
        assertTrue(lattice.axes.isEmpty())
        assertContentEquals(FloatArray(8) { 2f }, lattice.cells.single().form.controlPoints)
        val source = BindingProjection.retain(input, emptySet(), mapOf(x to 1f))
        assertEquals(6f, source.drawables.single().geometryGrid!!.cells.single().form.positionDeltas[0])
        assertTrue(source.drawables.single().geometryGrid!!.axes.isEmpty())
        assertEquals(.25f, input.parameters.first().default)
        assertSame(grid, input.drawables.single().geometryGrid)
        assertFailsWith<InterruptedException> { BindingProjection.retain(input, emptySet(), checkpoint = { throw InterruptedException() }) }
        assertFailsWith<IllegalArgumentException> { BindingProjection.retain(input, emptySet(), mapOf(x to Float.NaN)) }
    }
}
