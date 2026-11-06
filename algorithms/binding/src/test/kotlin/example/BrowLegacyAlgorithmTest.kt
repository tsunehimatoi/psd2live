package example

import io.github.psd2live.algorithms.binding.BrowLegacyAlgorithm
import io.github.psd2live.algorithms.binding.BrowLegacyInputs
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.*

class BrowLegacyAlgorithmTest {
    private val left = mesh("left")
    private val unsided = mesh("unsided")
    private val unrelated = mesh("unrelated")
    private val input = RigIR(Canvas(100f, 100f), emptyList(), meshes = listOf(left, unsided, unrelated))
    private val source = BrowLegacyInputs(mapOf(
        left.id to BrowLegacyInputs.Brow(Side.LEFT, 10f, 20f),
        unsided.id to BrowLegacyInputs.Brow(Side.NONE, 5f, 20f),
    ), 12f, "Left Brow", "Right Brow", "Brows")
    private val instance = RigModifierInstance("brows", "brow/legacy", 1, listOf(left, unsided).map { ModifierTarget("mesh", it.id) },
        config = buildJsonObject { putJsonObject("tuning") { put("browLift", 20f) } },
        outputAliases = mapOf("parameter-folder:ParamGroupBrows" to "legacy-brows"))

    @Test fun generatesSignedLegacyFormsAndPreservesUnselectedObjects() {
        val context = source.encode()
        assertEquals(source, BrowLegacyInputs.decode(context))
        val view = ModifierEvaluationInput(input, context)
        val changes = BrowLegacyAlgorithm().changes(view, instance) {}
        val output = changes.apply(input)
        assertEquals(listOf("ParamBrowLY", "ParamBrowRY"), output.parameters.map { it.id })
        assertEquals(listOf(ParameterNode.Group("legacy-brows", "Brows", true,
            output.parameters.map { ParameterNode.Param(it.id) })), output.parameterTree)
        val first = requireNotNull(output.meshes[0].offsets)
        val second = requireNotNull(output.meshes[1].offsets)
        assertEquals("ParamBrowLY", first.axes.single().parameter)
        assertEquals("ParamBrowRY", second.axes.single().parameter)
        assertEquals(Floats.values(-1f, 0f, 1f), first.axes.single().keys)
        assertEquals(Floats.values(0f, 0.1f, 0f, 0.1f, 0f, 0.1f), first.cells.first().form.deltas)
        assertEquals(Floats.values(0f, -0f, 0f, -0f, 0f, -0f), first.cells[1].form.deltas)
        assertEquals(Floats.values(0f, -0.05f, 0f, -0.05f, 0f, -0.05f), second.cells.last().form.deltas)
        assertSame(left.geometry, output.meshes.first().geometry)
        assertSame(unrelated, output.meshes.last())
        val changed = input.copy(meshes = listOf(left, unsided, unrelated.copy(opacity = 0.2f)))
        assertTrue(view.reads().matches(changed, context))
        assertSame(changed.meshes.last(), changes.apply(changed).meshes.last())
    }

    @Test fun addsMotionToAuthoredOffsets() {
        val authored = left.copy(offsets = KeyGrid.single(MeshOffsets(Floats.values(1f, 2f, 1f, 2f, 1f, 2f))))
        val rig = input.copy(meshes = listOf(authored, unsided, unrelated))
        val output = BrowLegacyAlgorithm().evaluate(rig, instance, source.encode()) {}
        val cells = requireNotNull(output.meshes.first().offsets).cells
        assertEquals(1f, cells.first().form.deltas[0], absoluteTolerance = 0.00001f)
        assertEquals(2.1f, cells.first().form.deltas[1], absoluteTolerance = 0.00001f)
        assertEquals(2f, cells[1].form.deltas[1], absoluteTolerance = 0.00001f)
        assertEquals(1.9f, cells.last().form.deltas[1], absoluteTolerance = 0.00001f)
    }

    private fun mesh(id: String) = Mesh(id, id, null, geometry = MeshGeometry(
        Floats.values(10f, 10f, 20f, 10f, 10f, 20f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)),
        offsets = null)
}
