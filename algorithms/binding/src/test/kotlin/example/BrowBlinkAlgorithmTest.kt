package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class BrowBlinkAlgorithmTest {
    @Test fun additionalAuthoredAxisSurvivesBlinkWithFoldedWideEyes() {
        val raw = ScaffoldMesh.RawMesh(floatArrayOf(20f, 10f, 35f, 11f, 24f, 14f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
        val frame = Bounds(0f, 0f, 100f, 100f)
        val fit = BrowFit(Side.LEFT, 20f, 10f, 1f, 0f, 15f, List(BrowFit.SAMPLES) { 0f })
        val eye = BrowShapeInputs.Eye(.5f, 12f)
        val grid = BrowRig(fit, .5f * 12f * .45f, BrowOptions()).grid(raw.rigPositions, frame,
            ParameterId("ParamBrowLY"), ParameterId("ParamBrowLAngle"))
        val offsets = KeyGrid(grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) } +
            KeyAxis("Expression", Floats.values(0f, 1f)), (0..1).flatMap { expression ->
                grid.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate + expression),
                    MeshOffsets(Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                        cell.form.positionDeltas[i] + expression * .2f
                    }))) }
            })
        val mesh = Mesh("brow", "Brow", null, geometry = MeshGeometry(Floats.wrap(raw.rigPositions),
            Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)), offsets = offsets)
        val targets = listOf(ModifierTarget("mesh", mesh.id))
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "fitted") }
        val encoded = BrowShapeInputs(List(6) { "Brow" }, "Brows", mapOf(mesh.id to
            BrowShapeInputs.Target(fit, eye, null, frame, raw)), "eyes").encode()
        val context = ModifierAlgorithmContext(JsonObject(encoded.values + ("carrier_names" to
            buildJsonObject { put(mesh.id, "Brow motion") })), encoded.resources).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
            ModifierUpstreamCapability("brow/fitted", "brow/fitted", 2, targets, "fitted", config),
            ModifierUpstreamCapability("eye/fitted", "eye", 1, targets, "eyes", config),
        ))
        val source = RigIR(Canvas(100f, 100f), listOf(
            Parameter("ParamBrowLY", "Height", -1f, 1f, 0f),
            Parameter("Expression", "Expression", 0f, 1f, 0f),
            Parameter("ParamEyeLOpen", "Open", 0f, 2f, 1f)), meshes = listOf(mesh))
        val instance = RigModifierInstance("blink", "brow.blink", 2, targets, config = config)
        val result = BrowBlinkAlgorithm().changes(ModifierEvaluationInput(source, context), instance) {}.apply(source)
        val updated = result.meshes.single()
        assertEquals(mesh.geometry, updated.geometry)
        // Both expression slices must survive, with the same authored separation.
        val actual = assertNotNull(updated.offsets)
        val expressionAxis = actual.axes.indexOfFirst { it.parameter == "Expression" }
        assertTrue(expressionAxis >= 0)
        for (cell in actual.cells.filter { it.coordinate[expressionAxis] == 0 }) {
            val partner = actual.cells.single { other -> actual.axes.indices.all { axis ->
                other.coordinate[axis] == if (axis == expressionAxis) 1 else cell.coordinate[axis]
            } }
            for (i in 0 until cell.form.deltas.size)
                assertEquals(.2f, partner.form.deltas[i] - cell.form.deltas[i], 1e-5f)
        }
        assertTrue(updated != mesh)
    }
}
