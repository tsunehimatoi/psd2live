package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingFrames
import io.github.psd2live.algorithms.binding.GeometryResidual
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DrawableId
import kotlin.test.*

class BindingGeometryPrecisionTest {
    private fun source(): RigIR {
        val geometry = MeshGeometry(Floats.wrap(floatArrayOf(.33333334f, .7f, .9f, .1f, .2f, .3f)),
            Floats.wrap(FloatArray(6)), Ints.wrap(intArrayOf(0, 1, 2)))
        val offsets = KeyGrid(listOf(KeyAxis("p", Floats.wrap(floatArrayOf(0f, 1f)))), listOf(
            KeyCell(Ints.wrap(intArrayOf(0)), MeshOffsets(Floats.wrap(FloatArray(6)))),
            KeyCell(Ints.wrap(intArrayOf(1)), MeshOffsets(Floats.wrap(floatArrayOf(.000001f, -.0036676973f, 0f, 0f, 0f, 0f)))),
        ))
        return RigIR(Canvas(100f, 100f), listOf(Parameter("p", "p", 0f, 1f, 0f)),
            meshes = listOf(Mesh("mesh", "mesh", null, geometry = geometry, offsets = offsets)))
    }

    @Test fun identicalMappingsAndZeroCorrectionsPreserveAuthoredFloatBits() {
        val input = source()
        val puppet = PuppetIr.toPuppet(input)
        val id = DrawableId("mesh")
        assertEquals(input.meshes, PuppetIr.toIr(BindingFrames.carriedToParent(puppet, puppet, id)).meshes)
        val sources = (0 until 3).map(VertexSource::FromOld)
        val neutral = puppet.copy(drawables = puppet.drawables.map { it.copy(geometryGrid = null) })
        assertSame(puppet.drawables.single().geometryGrid,
            GeometryResidual.geometry(puppet, neutral, neutral, id, sources, sources) {})
    }

    @Test fun nonzeroGeneratedCorrectionStillChangesTheAuthoredGrid() {
        val puppet = PuppetIr.toPuppet(source())
        val neutral = puppet.copy(drawables = puppet.drawables.map { it.copy(geometryGrid = null) })
        val shifted = neutral.copy(drawables = neutral.drawables.map { drawable ->
            val mesh = requireNotNull(drawable.mesh)
            drawable.copy(mesh = org.umamo.runtime.model.DrawableMesh(
                mesh.positions.copyOf().apply { this[0] += .25f }, mesh.uvs, mesh.indices))
        })
        val sources = (0 until 3).map(VertexSource::FromOld)
        val result = assertNotNull(GeometryResidual.geometry(puppet, neutral, shifted, DrawableId("mesh"), sources, sources) {})
        assertEquals(.25f, result.cells.first().form.positionDeltas[0], .000001f)
        assertEquals(.250001f, result.cells.last().form.positionDeltas[0], .000001f)
    }
}
