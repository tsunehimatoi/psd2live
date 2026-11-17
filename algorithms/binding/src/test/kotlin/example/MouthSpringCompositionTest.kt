package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.test.*

class MouthSpringCompositionTest {
    @Test fun springPreservesCapturePressAndAuthoredTracksAcrossCarrierAndTopologyChanges() {
        for (lipped in listOf(false, true)) {
            val targets = listOf(ModifierTarget("mesh", "face"))
            val structure = RigModifierInstance("structure", "rig.scaffold", 2, targets)
            val parentId = ScaffoldBinding.deformerId(structure, "face", "head")
            fun warp(id: String, parent: String?, points: Floats) = Deformer.Warp(id, id, parent, null, 1, 1, true,
                KeyGrid(emptyList(), listOf(KeyCell(Ints.values(), LatticePoints(points)))))
            val parent = warp(parentId, null, Floats.values(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))
            val carrier = warp("jaw-carrier", parentId, Floats.values(.1f, .2f, .9f, .2f, .1f, 1f, .9f, 1f))
            val raw = ScaffoldMesh.RawMesh(floatArrayOf(10f, 50f, 90f, 50f, 50f, 90f),
                floatArrayOf(0f, 0f, 1f, 0f, .5f, 1f), intArrayOf(0, 1, 2))
            val points = raw.rigPositions + floatArrayOf(50f, 66f)
            val geometry = MeshGeometry(Floats.wrap(FloatArray(points.size) { i ->
                (points[i] - if (i % 2 == 0) 10f else 20f) / 80f
            }), Floats.wrap(raw.atlasUvs + floatArrayOf(.5f, .4f)), Ints.values(0, 1, 3, 1, 2, 3, 2, 0, 3))
            val grid = KeyGrid(listOf(KeyAxis("ParamMouthOpenY", Floats.values(0f, 1f)),
                KeyAxis("ParamMouthPressLipOpen", Floats.values(-1f, 1f))), (0..1).flatMap { open -> (0..1).map { press ->
                KeyCell(Ints.values(open, press), MeshOffsets(Floats.wrap(FloatArray(points.size) { i ->
                    .02f + open * .03f + (press * 2 - 1) * .01f + if (i % 2 == 0) open * press * .01f else 0f
                })))
            } })
            val custom = BlendBinding("Expression", Floats.values(0f, 1f), 0,
                listOf(null, MeshShape(Floats.wrap(FloatArray(points.size) { .04f }), 12f, .4f,
                    Rgb(.3f, .4f, .5f), Rgb(.1f, .2f, .3f))))
            val mesh = Mesh("face", "Face", carrier.id, geometry = geometry, offsets = grid, shapes = listOf(custom),
                opacity = .8f, drawOrder = 10f, multiply = Rgb(.7f, .6f, .5f), screen = Rgb(.01f, .02f, .03f))
            val input = RigIR(Canvas(100f, 100f), listOf(
                Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f), Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
                Parameter("ParamMouthPressLipOpen", "Press", -1f, 1f, 0f),
                Parameter("Expression", "Expression", 0f, 1f, 0f, blend = true, keys = Floats.values(0f, 1f))),
                meshes = listOf(mesh), deformers = listOf(parent, carrier))
            val resource = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("source", "source", raw,
                ScaffoldMesh.Geometry.Zero))).resources.getValue("source_meshes")
            val upper = List(MouthFit.SAMPLES) { i -> -.8f * MouthFit.u(i) * (1f - MouthFit.u(i)) }
            val source = MouthSourceContext(MouthFit(20f, 30f, 1f, 0f, 60f, upper, upper.map { -it }),
                MouthOptions(jaw = true, spring = true, capture = true), null)
            val frame = Bounds(0f, 0f, 100f, 100f)
            val captured = MouthFittedInputs(source, .9f, null, false, false, 20f,
                mapOf("face" to MouthFittedInputs.Layer("FACE", null, "NONE", null, frame, frame,
                    50f, 70f, true, false, true)), emptyList(), sourceVertices = MouthFittedInputs.SourceVertices(resource,
                    mapOf("face" to MouthFittedInputs.SourceVertexTarget("source", "head"))))
            val config = buildJsonObject {
                put("scaffold", "structure"); put("base", "mouth"); put("physicsEnabled", false)
                put("dependencies", buildJsonObject { put("mouth.jaw", "jaw") })
            }
            val context = MouthSpringAlgorithm.context(captured.encode(), if (lipped) setOf("face") else emptySet(),
                "Spring", "Physics", "Mouth physics").withUpstream(listOf(
                    ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
                    ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 2, targets, "mouth", config),
                    ModifierUpstreamCapability("mouth.jaw", "mouth.jaw", 2, targets, "jaw", config)))
            val instance = RigModifierInstance("spring", "mouth.spring", 2, targets, config = config)
            val result = MouthSpringAlgorithm().changes(ModifierEvaluationInput(input, context), instance) {}.apply(input)
            assertEquals(input.deformers, result.deformers)
            assertEquals(mesh.geometry, result.meshes.single().geometry)
            assertEquals(grid, result.meshes.single().offsets)
            assertEquals(custom, result.meshes.single().shapes.single { it.parameter == "Expression" })
            val rig = source.rig()
            val expected = rig.faceShapes(points, frame, .9f, false, ParameterId("ParamMouthOpenY"), MouthRig.SPRING, lipped)
                .single { it.parameterId == MouthRig.SPRING }.forms.last()!!.positionDeltas
            val neutral = if (!lipped) FloatArray(points.size) else rig.faceGrid(points, frame,
                ParameterId("ParamMouthForm"), ParameterId("ParamMouthOpenY"), .9f).let { fitted ->
                fitted.cells.single { cell -> fitted.axes.indices.all { i -> fitted.axes[i].keys[cell.coordinate[i]] == 0f } }.form.positionDeltas
            }
            val evaluator = CpuDeformationEvaluator()
            val id = DrawableId("face")
            var maximum = 0f
            for (open in listOf(0f, .5f, 1f)) for (press in listOf(-1f, 0f, 1f)) for (expression in listOf(0f, 1f)) {
                val pose = mapOf(ParameterId("ParamMouthOpenY") to open, ParameterId("ParamMouthPressLipOpen") to press,
                    ParameterId("Expression") to expression)
                val before = evaluator.evaluate(PuppetIr.toPuppet(input), pose)
                val rest = evaluator.evaluate(PuppetIr.toPuppet(result), pose)
                assertContentEquals(before.worldPositions.getValue(id), rest.worldPositions.getValue(id))
                val moved = evaluator.evaluate(PuppetIr.toPuppet(result), pose + (MouthRig.SPRING to 1f))
                val a = rest.worldPositions.getValue(id); val b = moved.worldPositions.getValue(id)
                for (i in a.indices) {
                    val delta = b[i] - a[i]
                    maximum = maxOf(maximum, abs(delta))
                    assertEquals((expected[i] - neutral[i]) * if (i % 2 == 0) 100f else -100f, delta, .015f)
                }
                assertEquals(rest.opacity.getValue(id), moved.opacity.getValue(id), 1e-5f)
                assertEquals(rest.multiplyColor.getValue(id), moved.multiplyColor.getValue(id))
                assertEquals(rest.screenColor.getValue(id), moved.screenColor.getValue(id))
                assertEquals(rest.drawOrder.getValue(id), moved.drawOrder.getValue(id))
            }
            assertTrue(maximum > .1f, "Spring must move actual face vertices")
        }
    }
}
