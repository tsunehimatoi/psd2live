package example

import io.github.psd2live.algorithms.binding.BindingCoordinates
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class BindingCoordinatesTest {
    private val parameter = Parameter("turn", "Turn", 0f, 1f, 0f)
    private val warp = Deformer.Warp("warp", "Warp", null, null, 1, 1, true,
        KeyGrid.single(LatticePoints(Floats.values(0f, 0f, 100f, 0f, 0f, 100f, 110f, 120f))))
    private val rotation = Deformer.Rotation("rotation", "Rotation", "warp", null, 0f,
        KeyGrid(listOf(KeyAxis("turn", Floats.values(0f, 1f))), listOf(
            KeyCell(Ints.values(0), Pivot(.5f, .5f, 0f, 1f)), KeyCell(Ints.values(1), Pivot(.5f, .5f, 20f, 1f)))))
    private val destination = Deformer.Rotation("destination", "Destination", null, null, 0f,
        KeyGrid.single(Pivot(20f, 30f, 15f, 1f)))
    private val mesh = Mesh("mesh", "Mesh", "rotation", geometry = MeshGeometry(Floats.values(1f, 2f, 3f, 4f),
        Floats.values(0f, 0f, 1f, 1f), Ints.Empty), offsets = KeyGrid.single(MeshOffsets(Floats.values(.5f, 0f, 0f, .5f))),
        opacity = .8f, userData = "authored")
    private val model = RigIR(Canvas(128f, 128f), listOf(parameter), deformers = listOf(warp, rotation, destination), meshes = listOf(mesh))

    @Test fun nestedFramesMatchRuntimeAndObserveOnlyTheirAncestorInputs() {
        val input = ModifierEvaluationInput(model)
        val coordinates = BindingCoordinates.forMesh(input, "mesh", mapOf("turn" to .5f))
        val local = mesh.geometry!!.positions.toArray()
        val expected = requireNotNull(drawableSpaceMapping(PuppetIr.toPuppet(model), mapOf(ParameterId("turn") to .5f), DrawableId("mesh")))
            .localToWorld(local).also { for (i in 1 until it.size step 2) it[i] = -it[i] }
        assertContentEquals(expected, coordinates.toCanvas(local))
        val restored = coordinates.fromCanvas(expected, local)
        local.indices.forEach { assertEquals(local[it], restored[it], .001f) }
        assertTrue(input.reads().matches(model.copy(deformers = listOf(warp, rotation, destination.copy(baseAngle = 20f)))))
        assertFalse(input.reads().matches(model.copy(parameters = listOf(parameter.copy(default = .2f)))))
        assertFalse(ModifierReadKey("deformer", "destination") in input.reads().keys)
    }

    @Test fun directCanvasFrameKeepsYDownAndRejectsMalformedPoints() {
        val input = ModifierEvaluationInput(model.copy(meshes = listOf(mesh.copy(parent = null))))
        val coordinates = BindingCoordinates.forMesh(input, "mesh")
        val points = floatArrayOf(10f, 25f, -2f, -5f)
        assertContentEquals(points, coordinates.toCanvas(points))
        assertContentEquals(points, coordinates.fromCanvas(points))
        assertFailsWith<IllegalArgumentException> { coordinates.toCanvas(floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { coordinates.fromCanvas(floatArrayOf(Float.NaN, 1f)) }
    }

    @Test fun reparentProducesAnOrdinaryGeometryChangePreservingCanvasPoseAndChannels() {
        val original = BindingCoordinates.forMesh(ModifierEvaluationInput(model), "mesh")
        val changes = BindingCoordinates.reparent(ModifierEvaluationInput(model), "mesh", "destination")
        val output = changes.apply(model)
        val moved = output.meshes.single()
        val target = BindingCoordinates.forMesh(ModifierEvaluationInput(output), "mesh")
        val before = original.toCanvas(mesh.geometry!!.positions.toArray())
        val after = target.toCanvas(moved.geometry!!.positions.toArray())
        before.indices.forEach { assertEquals(before[it], after[it], .05f) }
        assertEquals(mesh.opacity, moved.opacity); assertEquals(mesh.userData, moved.userData)
        assertSame(model.deformers, output.deformers)
        assertEquals(setOf("mesh"), changes.writeKinds())
        val oldDelta = mesh.offsets!!.cells.single().form.deltas.toArray()
        val newDelta = moved.offsets!!.cells.single().form.deltas.toArray()
        val oldPose = original.toCanvas(FloatArray(oldDelta.size) { mesh.geometry!!.positions[it] + oldDelta[it] })
        val newPose = target.toCanvas(FloatArray(newDelta.size) { moved.geometry!!.positions[it] + newDelta[it] })
        oldPose.indices.forEach { assertEquals(oldPose[it], newPose[it], .05f) }
    }
}
