package io.github.psd2live.core.sim

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectBlendLimitsTest {
    @Test fun checksTheAreaBetweenTwoKeysEvenWhenTheGridCornersStayPositive() {
        val points = floatArrayOf(0f, 0f, -30f, 40f, -20f, -30f)
        val mesh = Mesh("hem", "Hem", null,
            geometry = MeshGeometry(Floats.of(points), Floats.of(points), Ints.values(0, 1, 2)), offsets = null)
        val base = PuppetIr.toPuppet(RigIR(Canvas(200f, 200f), emptyList(), meshes = listOf(mesh)))
        val scene = SimScene.build(base, RigSimEdit("sim", "Hem", SimKind.CLOTH, listOf(mesh.id)))
        val keys = floatArrayOf(-1f, 0f, 1f)
        val axes = listOf(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f), floatArrayOf(0f, 0f, 0f, -100f, 100f, 0f))
        val forms = axes.map { values -> keys.map { key -> FloatArray(points.size) { values[it] * key } } }
        val result = DirectBlendGeometry(scene, points) {}.project(listOf(keys, keys), forms, floatArrayOf(1f, 1f))
        if (result.safe) {
            val (a, b) = result.offsets.map { it.last() }
            val p = FloatArray(points.size) { points[it] + 0.30f * (a[it] + b[it]) }
            val area = (p[2] - p[0]) * (p[5] - p[1]) - (p[3] - p[1]) * (p[4] - p[0])
            assertTrue(area >= 1700f * 0.24f)
        }
    }

    @Test fun limitsAConflictingTriangleWithoutDimmingTheRestOfTheMotion() {
        val points = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 150f, 0f, 250f, 0f, 150f, 100f)
        val mesh = Mesh("hem", "Hem", null,
            geometry = MeshGeometry(Floats.of(points), Floats.of(points), Ints.values(0, 1, 2, 3, 4, 5)), offsets = null)
        val base = PuppetIr.toPuppet(RigIR(Canvas(300f, 200f), emptyList(), meshes = listOf(mesh)))
        val edit = RigSimEdit("sim", "Hem", SimKind.CLOTH, listOf(mesh.id), exaggeration = 1f)
        val neutral = FloatArray(points.size)
        val squeezed = floatArrayOf(0f, 0f, -70f, 0f, 0f, 0f, 20f, 0f, 20f, 0f, 20f, 0f)
        val keys = floatArrayOf(-1f, 0f, 1f)
        val forms = listOf(neutral, neutral, squeezed)
        val scene = SimScene.build(base, edit).also { it.reset(base, emptyMap()) }
        val mixing = DirectBlendGeometry(scene, points) {}.project(
            listOf(keys, keys), listOf(forms, forms), floatArrayOf(0.86f, 0.86f))
        assertTrue(mixing.safe)
        val (a, b) = mixing.offsets.map { it.last() }
        val pos = FloatArray(points.size) { points[it] + 0.9632f * (a[it] + b[it]) }
        val area = (pos[2] - pos[0]) * (pos[5] - pos[1]) - (pos[3] - pos[1]) * (pos[4] - pos[0])
        assertTrue(area >= 2400f)
        assertTrue(a.copyOfRange(6, 12).contentEquals(squeezed.copyOfRange(6, 12)))
        assertTrue(b.copyOfRange(6, 12).contentEquals(squeezed.copyOfRange(6, 12)))
        assertTrue(mixing.offsets.all { it[1].all { delta -> delta == 0f } })
    }

    @Test fun keepsTheSoloShapeAndTapersOnlyTheSharedDeformation() {
        val points = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f)
        val mesh = Mesh("hem", "Hem", null,
            geometry = MeshGeometry(Floats.of(points), Floats.of(points), Ints.values(0, 1, 2)), offsets = null)
        val base = PuppetIr.toPuppet(RigIR(Canvas(200f, 200f), emptyList(), meshes = listOf(mesh)))
        val edit = RigSimEdit("sim", "Hem", SimKind.CLOTH, listOf(mesh.id), exaggeration = 1f)
        val a = SimGenerator.parameterId(edit, 1)
        val b = SimGenerator.parameterId(edit, 2)
        val rest = FloatArray(points.size)
        val forms = listOf(rest, rest, floatArrayOf(0f, 0f, -70f, 0f, 0f, 0f))
        val scene = SimScene.build(base, edit).also { it.reset(base, emptyMap()) }
        val limits = DirectGeometryLimit(scene, points) {}.blendLimits(listOf(ParameterId(a), ParameterId(b)), listOf(forms, forms))
        assertTrue(limits.normalWeight in 0.7f..0.99f)
        assertEquals(1f, limits.curves[0].single().points.single { it.value == 0f }.weight)

        val modes = listOf(a, b).mapIndexed { index, id ->
            SimBakedMode(SimBakedAxis(id, floatArrayOf(-30f, 0f, 30f), mapOf(mesh.id to forms),
                limits.curves[index], limits.normalWeight), 70f, 0.5f, "sway")
        }
        val bake = SimBakeResult.fromJson(SimBakeResult("", mapOf(mesh.id to 3), emptyList(), modes).toJson())
        assertEquals("sway", bake.modes.first().motion)
        val renamed = ParameterId("Other")
        val aId = ParameterId(a)
        val sim = edit.copy(bake = bake, outputs = mapOf(b to SimOutput(id = renamed.raw, range = 60f)))
        val (model, issues) = SimGenerator.applyOne(base, sim)
        assertTrue(issues.isEmpty())
        val binding = model.drawables.single().blendShapes.single { it.parameterId == aId }
        assertEquals(BlendWeightLimit(renamed, limits.curves[0].single().points.map { BlendWeightLimitPoint(it.value * 2f, it.weight) }),
            binding.limits.single())
        val evaluator = CpuDeformationEvaluator()
        fun x(first: Float, second: Float) = evaluator.evaluate(model, mapOf(aId to first, renamed to second))
            .worldPositions.getValue(DrawableId(mesh.id))[2]
        assertEquals(30f, x(30f, 0f), 0.01f)
        assertTrue(x(30f, 60f) > 10f)
        assertTrue(x(30f, 60f) < 30f)

        val legacy = SimGenerator.applyOne(base, sim.copy(blendShapes = false)).first
        assertTrue(legacy.drawables.single().blendShapes.isEmpty())
        val limited = evaluator.evaluate(legacy, mapOf(aId to 30f)).worldPositions.getValue(DrawableId(mesh.id))[2]
        assertEquals(100f - 70f * limits.normalWeight, limited, 0.01f)
    }
}
