package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.GloomBindingAlgorithm
import io.github.psd2live.algorithms.binding.GloomInputs
import io.github.psd2live.algorithms.binding.MoodBinding
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.*

class GloomPluginParityTest {
    @Test fun v2RejectsAddingAnAxisWhenLegacyGloomWouldAllocateACarrier() {
        val angle = ParameterId("ParamAngleX")
        val axes = listOf(
            KeyformAxis(angle, FloatArray(9) { it.toFloat() }),
            KeyformAxis(ParameterId("ParamAxisY"), FloatArray(8) { it.toFloat() }),
            KeyformAxis(ParameterId("ParamAxisZ"), floatArrayOf(0f, 1f)),
        )
        val vertices = floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f)
        val cells = axes[0].keys.indices.flatMap { x ->
            axes[1].keys.indices.flatMap { y ->
                axes[2].keys.indices.map { z ->
                    KeyformCell(intArrayOf(x, y, z), MeshDeltaForm(FloatArray(vertices.size)))
                }
            }
        }
        val id = DrawableId("face")
        val face = Drawable(id, "Face", null, BlendMode.Normal, emptyList(),
            DrawableMesh(vertices, floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f), intArrayOf(0, 1, 2)),
            KeyformGrid(axes, cells), blendShapes = listOf(BlendShapeBinding(angle, floatArrayOf(0f, 8f), 0,
                listOf(null, MeshForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f))))))
        val source = PuppetModel(
            axes.map { Parameter(it.parameterId, it.parameterId.raw, it.keys.first(), it.keys.last(), 0f) },
            emptyList(), emptyList(), listOf(face), listOf(OrgChild.Drawable(id)), null,
            canvasWidth = 100f, canvasHeight = 100f,
        )
        val kernel = MoodBinding(carrierName = { _, _ -> "Face turn" })
        val influence = mapOf(id to MoodBinding.Influence(0f, 1f, false))
        val input = PuppetIr.toIr(kernel.evaluate(source, emptyList(), emptyMap(), emptySet(), influence, cheek = false, gloom = false).model)
        val gloom = Parameter(ParameterId("ParamGloom"), "Gloom", 0f, 1f, 0f)
        val legacy = PuppetIr.toIr(kernel.evaluate(source, listOf(gloom), emptyMap(), emptySet(), influence, cheek = false, gloom = true).model)
        val neutral = input.meshes.single().copy(parent = null, geometry = null, offsets = null, shapes = emptyList())
        val context = GloomInputs(true, "Gloom", "Face",
            mapOf(id.raw to GloomInputs.Layer(true, false, .06f, null)), mapOf(id.raw to neutral))
        val instance = RigModifierInstance("gloom-test", "expression.gloom", 2, listOf(ModifierTarget("mesh", id.raw)))
        assertEquals(1, legacy.deformers.size)
        assertTrue(input.deformers.isEmpty())
        val failure = assertFailsWith<IllegalArgumentException> {
            GloomBindingAlgorithm().evaluate(input, instance, context.context()) {}
        }
        assertContains(failure.message.orEmpty(), "use Gloom v1")
    }
}
