package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class MouthJawCompositionTest {
    private fun fixture(keys: Int): Pair<RigIR, ModifierAlgorithmContext> {
        val geometry = MeshGeometry(Floats.values(.1f, .5f, .9f, .5f, .5f, .9f),
            Floats.values(0f, 0f, 1f, 0f, .5f, 1f), Ints.values(0, 1, 2))
        val axis = Floats.wrap(FloatArray(keys) { it.toFloat() / (keys - 1) })
        val offsets = KeyGrid(listOf(KeyAxis("Turn", axis), KeyAxis("Expression", axis)),
            (0 until keys).flatMap { x -> (0 until keys).map { y ->
                KeyCell(Ints.values(x, y), MeshOffsets(Floats.wrap(FloatArray(6) { i ->
                    .02f + axis[x] * axis[y] * .04f + (if (i % 2 == 0) axis[x] else axis[y]) * .01f
                })))
            } })
        val mesh = Mesh("face", "Face", null, geometry = geometry, offsets = offsets)
        val source = RigIR(Canvas(1f, 1f), listOf(
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f),
            Parameter("Turn", "Turn", 0f, 1f, .37f),
            Parameter("Expression", "Expression", 0f, 1f, .61f)), meshes = listOf(mesh))
        val resource = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("face", "source",
            ScaffoldMesh.RawMesh(geometry.positions.shared(), geometry.uvs.shared(), geometry.indices.shared()),
            ScaffoldMesh.Geometry.Zero))).resources.getValue("source_meshes")
        val context = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "mouth-jaw/1")
            put("providers", buildJsonObject {})
            put("fit", JsonArray(listOf(0f, 0f, 1f, 0f, 1f, .4f).map(::JsonPrimitive)))
            put("chin", 1f); put("reach", 1f)
            put("targets", buildJsonObject { put("face", buildJsonObject {
                put("frame", JsonArray(listOf(0f, 0f, 1f, 1f).map(::JsonPrimitive)))
                put("nose", false); put("parent", JsonNull)
            }) })
        }, mapOf("source_meshes" to resource)).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
            ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 2, targets, "mouth")))
        return source to context
    }

    private val targets = listOf(ModifierTarget("mesh", "face"))
    private val instance = RigModifierInstance("jaw", "mouth.jaw", 2, targets, config = buildJsonObject {
        put("scaffold", "structure"); put("base", "mouth")
    })

    @Test fun coupledGridAndInterpolatedNeutralSurviveWithActualJawMotion() {
        val (source, context) = fixture(3)
        val result = MouthJawAlgorithm().changes(ModifierEvaluationInput(source, context), instance) {}.apply(source)
        assertTrue(result.deformers.isEmpty())
        assertEquals(source.parameters, result.parameters)
        val before = PuppetIr.toPuppet(source).drawables.single()
        val after = PuppetIr.toPuppet(result).drawables.single()
        for (x in listOf(0f, .23f, .5f, 1f)) for (y in listOf(0f, .61f, 1f)) {
            fun sample(open: Float) = requireNotNull(meshGridDefaultDeltas(after) {
                when (it.raw) { "Turn" -> x; "Expression" -> y; else -> open }
            })
            val original = requireNotNull(meshGridDefaultDeltas(before) { if (it.raw == "Turn") x else y })
            val closed = sample(0f)
            val opened = sample(1f)
            original.indices.forEach { assertEquals(original[it], closed[it], 1e-6f) }
            // Opening must lower the chin at every authored and interpolated expression pose.
            assertTrue(opened[5] - closed[5] > .04f)
            val half = sample(.5f)
            original.indices.forEach { assertEquals((opened[it] + closed[it]) * .5f, half[it], 1e-6f) }
        }
    }

    @Test fun captureSharePreservesEditedIntermediateFormsAndChangesVisibleJawMotion() {
        val (base, captured) = fixture(3)
        val keys = Floats.values(0f, .4f, 1f)
        val authored = BlendBinding("ParamJawOpen", keys, 0, listOf(null,
            MeshShape(Floats.values(.01f, .02f, .03f, .04f, .05f, .06f), 500f, .7f,
                Rgb(.9f, .8f, .7f), Rgb(.1f, .2f, .3f)),
            MeshShape(Floats.values(.03f, .08f, .04f, .09f, .06f, .1f), 500f, .6f,
                Rgb(.8f, .7f, .6f), Rgb(.2f, .3f, .4f))))
        val source = base.copy(parameters = base.parameters + Parameter("ParamJawOpen", "Capture", 0f, 1f, 0f, blend = true),
            meshes = listOf(base.meshes.single().copy(shapes = listOf(authored))))
        val context = ModifierAlgorithmContext(JsonObject(captured.values + ("providers" to
            buildJsonObject { put("mouth.capture", "capture") })), captured.resources)
            .withUpstream(captured.upstream + ModifierUpstreamCapability("mouth.capture", "mouth.capture", 2, targets, "capture"))
        fun build(share: Int): RigIR {
            val configured = instance.copy(config = JsonObject(instance.config + ("values" to buildJsonObject { put("share", share) })))
            return MouthJawAlgorithm().changes(ModifierEvaluationInput(source, context), configured) {}.apply(source)
        }
        val baseline = build(15)
        val stronger = build(25)
        assertEquals(authored, baseline.meshes.single().shapes.single())
        val edited = stronger.meshes.single().shapes.single()
        assertEquals(keys, edited.keys)
        val baselineDrawable = PuppetIr.toPuppet(baseline).drawables.single()
        val strongerDrawable = PuppetIr.toPuppet(stronger).drawables.single()
        fun openDeltas(drawable: org.umamo.runtime.model.Drawable) = requireNotNull(meshGridDefaultDeltas(drawable) {
            when (it.raw) { "ParamMouthOpenY" -> 1f; "Turn" -> .37f; "Expression" -> .61f; else -> 0f }
        })
        val before = openDeltas(baselineDrawable)
        val after = openDeltas(strongerDrawable)
        for (index in 1..2) {
            val original = authored.shapes[index]!!
            val changed = edited.shapes[index]!!
            assertEquals(original.copy(deltas = changed.deltas), changed)
            for (i in before.indices)
                assertEquals(original.deltas[i] + (after[i] - before[i]) * 1.2f * keys[index], changed.deltas[i], 1e-6f)
        }
        val evaluator = CpuDeformationEvaluator()
        val pose = mapOf(ParameterId("ParamJawOpen") to 1f)
        val oldWorld = evaluator.evaluate(PuppetIr.toPuppet(baseline), pose).worldPositions.getValue(DrawableId("face"))
        val newWorld = evaluator.evaluate(PuppetIr.toPuppet(stronger), pose).worldPositions.getValue(DrawableId("face"))
        assertTrue(kotlin.math.abs(oldWorld[5] - newWorld[5]) > .03f)
        assertEquals(stronger, build(25), "Replaying from the same source must not accumulate the share adjustment")
    }

    @Test fun denseCoupledGridGetsStableCarrierWithoutDiscardingAuthoredCells() {
        val (source, context) = fixture(17)
        fun apply() = MouthJawAlgorithm().changes(ModifierEvaluationInput(source, context), instance) {}.apply(source)
        val result = apply()
        assertEquals(result, apply())
        assertTrue(result.deformers.isNotEmpty())
        assertEquals(source.parameters, result.parameters)
        val grid = assertNotNull(result.meshes.single().offsets)
        assertEquals(source.meshes.single().offsets!!.axes, grid.axes)
        assertEquals(289, grid.cells.size)
        assertTrue(grid.cells.flatMap { it.form.deltas.shared().toList() }.all(Float::isFinite))
        assertTrue(result.deformers.any { it.id == result.meshes.single().parent })
        val evaluator = CpuDeformationEvaluator()
        val before = PuppetIr.toPuppet(source)
        val after = PuppetIr.toPuppet(result)
        for (x in listOf(0f, .37f, 1f)) for (y in listOf(0f, .61f, 1f)) {
            val pose = mapOf(ParameterId("Turn") to x, ParameterId("Expression") to y)
            val original = evaluator.evaluate(before, pose).worldPositions.getValue(DrawableId("face"))
            val closed = evaluator.evaluate(after, pose).worldPositions.getValue(DrawableId("face"))
            original.indices.forEach { assertEquals(original[it], closed[it], 1e-5f) }
            val opened = evaluator.evaluate(after, pose + (ParameterId("ParamMouthOpenY") to 1f))
                .worldPositions.getValue(DrawableId("face"))
            assertTrue(kotlin.math.abs(opened[5] - closed[5]) > .025f)
        }
    }
}
