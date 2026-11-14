package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.*
import kotlin.test.*

class BrowFittedAlgorithmTest {
    @Test fun typedChangesMatchLegacyHeightOnEachSideAndLeaveOtherAxesIndependent() {
        val frame = Bounds(0f, 0f, 100f, 100f)
        val raw = ScaffoldMesh.RawMesh(floatArrayOf(20f, 10f, 35f, 11f, 24f, 14f),
            floatArrayOf(.2f, .1f, .35f, .11f, .24f, .14f), intArrayOf(0, 1, 2))
        val (mesh, neutral) = ScaffoldMesh.build(raw, ScaffoldArms.Parent(DeformerId("brows"), frame),
            ScaffoldMesh.Geometry.Brow(4f, 50f))
        val targets = listOf("left", "right", "shadow", "other").map { ModifierTarget("mesh", it) }
        val source = PuppetIr.toIr(PuppetModel(emptyList(), emptyList(), emptyList(), targets.map {
            Drawable(DrawableId(it.id), it.id, null, BlendMode.Normal, emptyList(), mesh, neutral, opacity = .7f)
        }, emptyList(), null))
        val vertices = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("source", "source", raw, ScaffoldMesh.Geometry.Zero)))
            .resources.getValue(BrowFittedInputs.VERTICES)
        val left = BrowFit(Side.LEFT, 20f, 10f, 1f, 0f, 15f, List(BrowFit.SAMPLES) { 0f })
        val right = left.copy(side = Side.RIGHT, originX = 35f, axisX = -1f)
        val inputs = BrowFittedInputs("structure", mapOf(Side.LEFT to left, Side.RIGHT to right),
            mapOf(Side.LEFT to BrowFittedInputs.Eye("eyes", .5f, 12f)),
            mapOf("left" to BrowFittedInputs.Layer("source", Side.LEFT, true, frame, 4f),
                "right" to BrowFittedInputs.Layer("source", Side.RIGHT, true, frame, 4f),
                "shadow" to BrowFittedInputs.Layer("source", Side.RIGHT, false, frame, 4f)),
            "Left", "Right", "Brows", 50f)
        val context = inputs.encode(vertices, listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
            ModifierUpstreamCapability("eye/fitted", "eye/fitted", 2, targets, "eyes"),
        ))
        val instance = RigModifierInstance("fitted-brow", "brow/fitted", 1, targets,
            config = buildJsonObject { put("scaffold", "structure") })
        val changes = BrowFittedAlgorithm().changes(source, instance, context) {}
        assertEquals(listOf("ParamBrowLY", "ParamBrowRY"), changes.parameters.added.map { it.id })
        assertEquals(listOf("left", "right", "shadow"), changes.meshes.updated.map { it.before.id })
        val output = changes.apply(source)
        assertSame(source.meshes.last(), output.meshes.last())
        val group = output.parameterTree.single() as io.github.psd2live.format.model.ParameterNode.Group
        assertEquals(instance.outputId("parameter-folder:ParamGroupBrows"), group.id)
        val drawables = PuppetIr.toPuppet(output).drawables
        for ((index, fit, travel) in listOf(Triple(0, left, .5f * 12f * .45f), Triple(1, right, 15f * .25f * .45f / .45f))) {
            val actual = drawables[index]
            val expected = BrowRig(fit, travel, BrowOptions()).grid(raw.rigPositions, frame,
                ParameterId(if (index == 0) "ParamBrowLY" else "ParamBrowRY"), ParameterId("angle"))
            val grid = assertNotNull(actual.geometryGrid)
            assertEquals(expected.axes.single().parameterId, grid.axes.single().parameterId)
            expected.cells.zip(grid.cells).forEach { (old, new) ->
                assertContentEquals(old.form.positionDeltas, new.form.positionDeltas)
            }
            assertTrue(actual.blendShapes.isEmpty())
        }
        val fallback = assertNotNull(drawables[2].geometryGrid)
        assertEquals(ParameterId("ParamBrowRY"), fallback.axes.single().parameterId)
        assertEquals(-.02f, fallback.cells.last().form.positionDeltas[1], 1e-6f)
        assertFailsWith<IllegalArgumentException> {
            BrowFittedAlgorithm().changes(source, instance, context.withUpstream(context.upstream.take(1))) {}
        }
    }
}
