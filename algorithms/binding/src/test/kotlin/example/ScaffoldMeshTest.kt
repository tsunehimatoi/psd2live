package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.ScaffoldArms
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMesh.Geometry
import org.umamo.runtime.model.DeformerId
import kotlin.test.*

class ScaffoldMeshTest {
    private val parent = ScaffoldArms.Parent(DeformerId("parent"), Bounds(0f, 0f, 100f, 100f))
    private val raw = ScaffoldMesh.RawMesh(floatArrayOf(0.1f, 20f, 1f, 0f, 2f, 4f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))

    @Test fun normalizesSourcePointsAndPreservesTopologyWithoutAddingAxes() {
        val (mesh, grid) = ScaffoldMesh.build(raw, parent, Geometry.Zero)
        assertNotSame(raw.rigPositions, mesh.positions)
        assertSame(raw.atlasUvs, mesh.uvs)
        assertSame(raw.triangles, mesh.indices)
        assertContentEquals(raw.rigPositions.map { it / 100f }.toFloatArray(), mesh.positions)
        assertTrue(grid.axes.isEmpty())
        assertTrue(grid.cells.single().coordinate.isEmpty())
        assertContentEquals(FloatArray(raw.rigPositions.size), grid.cells.single().form.positionDeltas)
    }

    @Test fun openEyeAndRestBrowKeepSignedZeroDeltas() {
        val bounds = Bounds(0f, 0f, 10f, 10f)
        val eye = Geometry.Eye(true, bounds, listOf(bounds), 10f, 0, floatArrayOf(9f, 1f, 11f), 34f, 38f, 88f)
        val eyeDeltas = deltas(eye)
        assertEquals(0f.toRawBits(), eyeDeltas[0].toRawBits())
        assertEquals((-0f).toRawBits(), eyeDeltas[1].toRawBits())
        assertEquals(0f.toRawBits(), eyeDeltas[3].toRawBits())
        assertContentEquals(intArrayOf(0, Int.MIN_VALUE, 0, Int.MIN_VALUE, 0, Int.MIN_VALUE),
            deltas(Geometry.Brow(10f, 12f)).map { it.toRawBits() }.toIntArray())
    }

    @Test fun sourceIrisAndOpenMouthRetainCenteringRoundoff() {
        val x = raw.rigPositions[0]
        val centered = ((65f + (x - 65f)) - x) / parent.frame.width
        assertNotEquals(0f, centered)
        assertEquals(centered.toRawBits(), deltas(Geometry.Iris(65f, 80f, 4.5f, 11f))[0].toRawBits())
        val curve = (0..6).map { i -> ScaffoldMesh.CurvePoint(i / 6f, if (i in 1..5) 0.14f else 0f) }
        val mouth = Geometry.Mouth(Bounds(50f, 45f, 80f, 95f), curve, 8f, 48f, 1.8f, 10.5f, 7f)
        assertEquals(centered.toRawBits(), deltas(mouth)[0].toRawBits())
    }

    @Test fun meshValidationAndCancellationRejectPartialResults() {
        assertFailsWith<IllegalArgumentException> {
            ScaffoldMesh.build(raw.copy(triangles = intArrayOf(0, 1, 3)), parent, Geometry.Zero)
        }
        var checks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldMesh.build(raw, parent, Geometry.Zero) { if (++checks == 3) throw InterruptedException() }
        }
    }

    private fun deltas(geometry: Geometry): FloatArray =
        ScaffoldMesh.build(raw, parent, geometry).second.cells.single().form.positionDeltas
}
