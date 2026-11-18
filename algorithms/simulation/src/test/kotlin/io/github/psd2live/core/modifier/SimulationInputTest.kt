package io.github.psd2live.core.modifier

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimCollider
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.format.model.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SimulationInputTest {
    @Test fun bakeSamplesTheSamePosedVerticesAcrossGlueChainsAndColliderParents() {
        fun mesh(id: String, parent: String? = null) = Mesh(id, id, parent, geometry = MeshGeometry(
            Floats.values(0f, 0f, 10f, 0f, 0f, 10f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)), offsets = null)
        fun glue(a: String, b: String) = Glue(null, a, b, listOf(GluePair(0, 0, .5f, .5f)))
        val root = Deformer.Rotation("root", "Root", null, null, 0f, KeyGrid(
            listOf(KeyAxis("turn", Floats.values(0f, 1f))),
            listOf(KeyCell(Ints.values(0), Pivot(0f, 0f, 0f, 1f)), KeyCell(Ints.values(1), Pivot(8f, 5f, 25f, 1f)))))
        val child = root.copy(id = "child", parent = root.id)
        val rig = RigIR(Canvas(100f, 100f), listOf(Parameter("turn", "Turn", 0f, 1f, 0f)),
            meshes = listOf(mesh("target"), mesh("bridge"), mesh("driver", child.id), mesh("collider", root.id), mesh("unused")),
            deformers = listOf(child, root, root.copy(id = "unused")),
            glues = listOf(glue("bridge", "driver"), glue("target", "bridge")))
        val model = PuppetIr.toPuppet(rig)
        val edit = RigSimEdit("sim", "Sim", SimKind.CLOTH, listOf("target"), colliders = listOf(SimCollider("collider", 0, radius = 1f)))
        val scoped = SimulationInput.bakeModel(model, edit)
        assertEquals(listOf("target", "bridge", "driver", "collider"), scoped.drawables.map { it.id.raw })
        assertEquals(listOf("child", "root"), scoped.deformers.map { it.id.raw })
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .3f, 1f)) {
            val pose = mapOf(ParameterId("turn") to value)
            val expected = evaluator.evaluate(model, pose).worldPositions
            val actual = evaluator.evaluate(scoped, pose).worldPositions
            for (mesh in scoped.drawables) assertContentEquals(expected.getValue(mesh.id), actual.getValue(mesh.id))
        }
    }
}
