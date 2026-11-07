package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class NeckAlgorithmTest {
    private val points = floatArrayOf(12f, 20f, 22f, 40f, 12f, 60f)
    private val uvs = floatArrayOf(0f, 0f, 1f, .5f, 0f, 1f)
    private val triangles = intArrayOf(0, 1, 2)
    private val frame = Bounds(0f, 0f, 60f, 100f)
    private val neck = Bounds(10f, 20f, 24f, 60f)
    private val ids = listOf("neck", "shadow", "clipped", "inverted", "face")
    private val targets = ids.map { ModifierTarget("mesh", it) }
    private val instance = RigModifierInstance("neck-tilt", NeckAlgorithm.TYPE, NeckAlgorithm.VERSION, targets)

    private fun context(): ModifierAlgorithmContext {
        val raw = ScaffoldMesh.RawMesh(points, uvs, triangles)
        val meshes = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material("raw-neck", "source-neck",
            raw, ScaffoldMesh.Geometry.Zero)))
        val source = NeckInputs(18f, 15f, neck, listOf("neck"), ids.dropLast(1).associateWith {
            NeckInputs.Target("raw-neck", neck, frame, null, it != "neck")
        })
        return source.encode(meshes).withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "structure"),
            ModifierUpstreamCapability("face/legacy", "face/fitted", 1, targets, "head"),
        ))
    }

    private fun rig(): RigIR {
        val geometry = MeshGeometry(Floats.wrap(floatArrayOf(.2f, .2f, .36667f, .4f, .2f, .6f)),
            Floats.wrap(uvs), Ints.wrap(triangles))
        val meshes = ids.map { id -> Mesh(id, id, null, geometry = geometry, offsets = null,
            maskedBy = if (id == "clipped") listOf("face") else emptyList(),
            invertMask = id == "inverted", opacity = .45f, drawOrder = 520f) }
        return RigIR(Canvas(80f, 100f), listOf(Parameter(NeckAlgorithm.ANGLE_Z, "Head roll", -30f, 30f, 0f)), meshes = meshes)
    }

    @Test fun sourceMotionMatchesLegacyAndOnlyOwnsNeckMeshes() {
        val input = rig()
        val changes = NeckAlgorithm().changes(ModifierEvaluationInput(input, context()), instance) {}
        changes.requireWrites(setOf("mesh"))
        assertEquals(ids.dropLast(1), changes.meshes.updated.map { it.before.id })
        val output = changes.apply(input)
        assertSame(input.meshes.last(), output.meshes.last())
        assertEquals(input.parameters, output.parameters)
        assertEquals(listOf("neck"), output.meshes[1].maskedBy)
        assertEquals(listOf("face"), output.meshes[2].maskedBy)
        assertTrue(output.meshes[3].invertMask)
        assertTrue(output.meshes[3].maskedBy.isEmpty())
        for (mesh in output.meshes.dropLast(1)) {
            assertSame(input.meshes.first().geometry, mesh.geometry)
            val binding = mesh.shapes.single()
            assertEquals("ParamAngleZ", binding.parameter)
            assertEquals(Floats.values(-30f, 0f, 30f), binding.keys)
            assertNull(binding.shapes[1])
            for ((index, degrees) in listOf(0 to -30f, 2 to 30f)) {
                val shape = requireNotNull(binding.shapes[index])
                assertContentEquals(legacy(degrees, mesh.id != "neck"), shape.deltas.shared())
                assertEquals(.45f, shape.opacity)
                assertEquals(520f, shape.drawOrder)
            }
        }
    }

    @Test fun addsTiltToAnExistingShapeWithoutChangingItsChannels() {
        val input = rig()
        val neckMesh = input.meshes.first()
        val authored = MeshShape(Floats.wrap(FloatArray(points.size) { .125f }), 620f, .7f, Rgb.White, Rgb.Black)
        val binding = BlendBinding(NeckAlgorithm.ANGLE_Z, Floats.values(-30f, 0f, 30f), 1, listOf(authored, null, null))
        val edited = input.copy(meshes = listOf(neckMesh.copy(shapes = listOf(binding))) + input.meshes.drop(1))

        val output = NeckAlgorithm().changes(ModifierEvaluationInput(edited, context()), instance) {}.apply(edited)
        val shape = requireNotNull(output.meshes.first().shapes.single().shapes.first())
        val expected = legacy(-30f, shadow = false).map { it + .125f }.toFloatArray()
        assertContentEquals(expected, shape.deltas.shared())
        assertEquals(authored.copy(deltas = shape.deltas), shape)
    }

    @Test fun missingFaceCapabilityPreventsAnyChange() {
        val incomplete = context().let { it.withUpstream(it.upstream.take(1)) }
        assertFailsWith<IllegalArgumentException> {
            NeckAlgorithm().changes(ModifierEvaluationInput(rig(), incomplete), instance) {}
        }
    }

    private fun legacy(degrees: Float, shadow: Boolean): FloatArray {
        val angle = Math.toRadians(degrees.toDouble()).toFloat()
        val expected = FloatArray(points.size)
        for (i in 0 until points.size / 2) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            val weight = if (shadow) 1f else ((neck.bottom - y) / neck.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                .let { it * it * (3f - 2f * it) }
            val dx = x - 18f
            val dy = y - 15f
            val rx = dx * cos(angle) + dy * sin(angle)
            val ry = -dx * sin(angle) + dy * cos(angle)
            expected[i * 2] = (rx - dx) * weight / frame.width + 0f
            expected[i * 2 + 1] = (ry - dy) * weight / frame.height + 0f
        }
        return expected
    }
}
