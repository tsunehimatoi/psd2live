package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class BrowSpringCompositionTest {
    @Test fun springSurvivesAChangedParentFrameAndPreservesAuthoredMotionAndChannels() {
        for (capturedParent in listOf(true, false)) {
            fun warp(id: String, parent: String?, points: Floats) = Deformer.Warp(id, id, parent, null,
                1, 1, true, KeyGrid(emptyList(), listOf(KeyCell(Ints.values(), LatticePoints(points)))))
            val base = warp("head", null, Floats.values(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))
            val carrier = warp("blink-carrier", "head", Floats.values(.1f, 0f, .5f, 0f, .1f, .2f, .5f, .2f))
            val raw = ScaffoldMesh.RawMesh(floatArrayOf(20f, 10f, 35f, 11f, 24f, 14f),
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
            val local = FloatArray(raw.rigPositions.size) { i ->
                if (i % 2 == 0) (raw.rigPositions[i] - 10f) / 40f else raw.rigPositions[i] / 20f
            }
            val grid = KeyGrid(listOf(KeyAxis("ParamBrowLY", Floats.values(-1f, 1f))),
                listOf(-1f, 1f).mapIndexed { index, height -> KeyCell(Ints.values(index),
                    MeshOffsets(Floats.wrap(FloatArray(6) { i -> if (i % 2 == 0) .03f else .02f + height * .04f }))) })
            val neutral = FloatArray(6) { i -> if (i % 2 == 0) .03f else .02f }
            val custom = BlendBinding("Expression", Floats.values(0f, 1f), 0,
                listOf(null, MeshShape(Floats.wrap(neutral.map { it + .02f }.toFloatArray()), 12f, .4f,
                    Rgb(.3f, .4f, .5f), Rgb(.1f, .2f, .3f))))
            val mesh = Mesh("brow", "Brow", carrier.id, geometry = MeshGeometry(Floats.wrap(local),
                Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)), offsets = grid, shapes = listOf(custom),
                opacity = .8f, drawOrder = 10f, multiply = Rgb(.7f, .6f, .5f), screen = Rgb(.01f, .02f, .03f))
            val source = RigIR(Canvas(100f, 100f), listOf(
                Parameter("ParamBrowLY", "Height", -1f, 1f, 0f),
                Parameter("Expression", "Expression", 0f, 1f, 0f, blend = true, keys = Floats.values(0f, 1f))),
                meshes = listOf(mesh), deformers = listOf(base, carrier))
            val fit = BrowFit(Side.LEFT, 20f, 10f, 1f, 0f, 15f, List(BrowFit.SAMPLES) { 0f })
            val measurement = BrowSpringInputs(mapOf(Side.LEFT to "Spring L", Side.RIGHT to "Spring R"),
                mapOf(Side.LEFT to "Physics L", Side.RIGHT to "Physics R"), "Physics", mapOf("brow" to
                    BrowSpringInputs.Target(fit, null, Bounds(0f, 0f, 100f, 100f), raw,
                        listOf(if (capturedParent) BrowSpringInputs.fingerprint(base) else "edited-source-parent"), 10f, 1f)), null, null)
            val targets = listOf(ModifierTarget("mesh", "brow"))
            val config = buildJsonObject { put("scaffold", "structure"); put("base", "fitted"); put("physicsEnabled", false) }
            val context = measurement.encode().withUpstream(listOf(
                ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
                ModifierUpstreamCapability("brow/fitted", "brow/fitted", 2, targets, "fitted", config)))
            val instance = RigModifierInstance("spring", "brow.spring", 2, targets, config = config)
            val result = BrowSpringAlgorithm().changes(ModifierEvaluationInput(source, context), instance) {}.apply(source)
            assertEquals(source.deformers, result.deformers)
            assertEquals(mesh.offsets, result.meshes.single().offsets)
            assertEquals(mesh.geometry, result.meshes.single().geometry)
            val evaluator = CpuDeformationEvaluator()
            val id = DrawableId("brow")
            for (height in listOf(-1f, 0f, .4f, 1f)) for (expression in listOf(0f, .5f, 1f)) {
                val pose = mapOf(ParameterId("ParamBrowLY") to height, ParameterId("Expression") to expression)
                val before = evaluator.evaluate(PuppetIr.toPuppet(source), pose)
                val rest = evaluator.evaluate(PuppetIr.toPuppet(result), pose)
                assertContentEquals(before.worldPositions.getValue(id), rest.worldPositions.getValue(id))
                for (amount in listOf(-1f, 1f)) {
                    val moved = evaluator.evaluate(PuppetIr.toPuppet(result), pose + (ParameterId("ParamBrowLSpring") to amount))
                    val a = rest.worldPositions.getValue(id); val b = moved.worldPositions.getValue(id)
                    for (i in a.indices step 2) {
                        assertEquals(a[i], b[i], .001f)
                        assertEquals(.675f * amount, b[i + 1] - a[i + 1], .002f)
                    }
                    assertEquals(rest.opacity.getValue(id), moved.opacity.getValue(id), 1e-5f)
                    assertEquals(rest.multiplyColor.getValue(id), moved.multiplyColor.getValue(id))
                    assertEquals(rest.screenColor.getValue(id), moved.screenColor.getValue(id))
                    assertEquals(rest.drawOrder.getValue(id), moved.drawOrder.getValue(id))
                }
            }
        }
    }
}
