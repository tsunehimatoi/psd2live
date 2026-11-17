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

class MouthCheekLidArtworkTest {
    @Test fun whiteOnlyArtworkMovesWithCheekAndPreservesBlink() {
        val width = 80; val height = 60
        val pixels = ByteArray(width * height * 4)
        var opaque = 0
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x + .5f - 40f) / 30f; val dy = (y + .5f - 30f) / 14f
            if (dx * dx + dy * dy <= 1f) { pixels[(y * width + x) * 4 + 3] = -1; opaque++ }
        }
        val layer = ModifierSourceLayer("white", "EYEWHITE", "LEFT", left = 100f, top = 50f,
            width = width, height = height, rgba = "pixels", bounds = ModifierSourceBounds(110f, 66f, 170f, 94f),
            centroidX = 140f, centroidY = 80f, opaquePixels = opaque)
        val art = ModifierSourceInputs(listOf(layer), mapOf("pixels" to ModifierAlgorithmResource(pixels)), mapOf("eye" to "white"))
        val raw = ScaffoldMesh.RawMesh(floatArrayOf(140f, 67f, 140f, 93f, 120f, 80f),
            floatArrayOf(.5f, 0f, .5f, 1f, 0f, .5f), intArrayOf(0, 1, 2))
        val topology = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("white", "white", raw,
            ScaffoldMesh.Geometry.Zero))).resources.getValue("source_meshes")
        val measurements = EyeSmileInputs(60f, null, emptyMap(), emptyMap(), emptyMap(),
            mapOf("eye" to EyeSmileInputs.Target("white", null, ModifierSourceBounds(0f, 0f, 1f, 1f))), emptyMap(), "Eyes")
        val geometry = MeshGeometry(Floats.wrap(raw.rigPositions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))
        val blink = KeyGrid(listOf(KeyAxis("ParamEyeLOpen", Floats.values(0f, 1f))), listOf(
            KeyCell(Ints.values(0), MeshOffsets(Floats.values(0f, 12f, 0f, -12f, 0f, 0f))),
            KeyCell(Ints.values(1), MeshOffsets(Floats.wrap(FloatArray(6))))))
        val eye = Mesh("eye", "Eye", null, geometry = geometry, offsets = blink,
            shapes = listOf(BlendBinding("ParamEyeBallX", Floats.values(0f, 1f), 0,
                listOf(null, MeshShape(Floats.values(1f, 0f, 1f, 0f, 1f, 0f), 0f, 1f, Rgb.White, Rgb.Black)))))
        val mouth = eye.copy(id = "mouth", offsets = KeyGrid(listOf(KeyAxis("ParamMouthForm", Floats.values(-1f, 0f, 1f))),
            (0..2).map { KeyCell(Ints.values(it), MeshOffsets(Floats.wrap(FloatArray(6)))) }))
        val iris = eye.copy(id = "iris", shapes = emptyList(), offsets = KeyGrid(
            listOf(KeyAxis("ParamEyeBallX", Floats.values(0f, 1f))),
            (0..1).map { KeyCell(Ints.values(it), MeshOffsets(Floats.wrap(FloatArray(6)))) }))
        val source = RigIR(Canvas(200f, 120f), listOf(
            Parameter("ParamEyeLOpen", "Blink", 0f, 1f, 1f), Parameter("ParamMouthForm", "Smile", -1f, 1f, 0f),
            Parameter("ParamEyeBallX", "Gaze", 0f, 1f, 0f)), meshes = listOf(eye, mouth, iris))
        val targets = listOf(ModifierTarget("mesh", "eye"))
        val config = buildJsonObject {
            put("scaffold", "structure"); put("base", "mouth")
            put("dependencies", buildJsonObject { put("eye/fitted", "eyes") })
        }
        val context = MouthCheekLidAlgorithm.context(measurements.encode(art, topology), mapOf("eye" to ("eye-carrier" to "Cheek")))
            .withUpstream(listOf(
                ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
                ModifierUpstreamCapability("eye/fitted", "eye/fitted", 2, targets, "eyes", config),
                ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 2, targets + ModifierTarget("mesh", "mouth"), "mouth", config)))
        val instance = RigModifierInstance("cheek", "mouth.cheek-lid", 2, targets, config = config)
        val result = MouthCheekLidAlgorithm().changes(ModifierEvaluationInput(source, context), instance) {}.apply(source)
        assertEquals(source.parameters, result.parameters)
        assertEquals(mouth, result.meshes.single { it.id == "mouth" })
        assertEquals(iris, result.meshes.single { it.id == "iris" })
        val evaluator = CpuDeformationEvaluator()
        fun points(model: RigIR, open: Float, smile: Float): FloatArray = evaluator.evaluate(PuppetIr.toPuppet(model),
            mapOf(ParameterId("ParamEyeLOpen") to open, ParameterId("ParamMouthForm") to smile))
            .worldPositions.getValue(DrawableId("eye"))
        for (open in listOf(0f, .4f, 1f)) {
            val before = points(source, open, 0f)
            val after = points(result, open, 0f)
            before.indices.forEach { assertEquals(before[it], after[it], 1e-4f) }
        }
        val rest = points(result, 1f, 0f)
        val gaze = evaluator.evaluate(PuppetIr.toPuppet(result), mapOf(ParameterId("ParamEyeBallX") to 1f))
            .worldPositions.getValue(DrawableId("eye"))
        for (i in rest.indices) assertEquals(rest[i] + if (i % 2 == 0) 1f else 0f, gaze[i], 1e-4f)
        val smiling = points(result, 1f, 1f)
        assertTrue(rest.indices.any { abs(smiling[it] - rest[it]) > .1f }, "Cheeks must visibly move the white-only eye boundary")
        val shut = points(result, 0f, 0f)
        val shutSmile = points(result, 0f, 1f)
        shut.indices.forEach { assertEquals(shut[it], shutSmile[it], 1e-4f, "Cheek motion must fade at closed eyes") }
    }
}
