package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.GloomBindingAlgorithm
import io.github.psd2live.application.WorkspacePresetBaseline
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.algorithms.binding.GloomInputs
import io.github.psd2live.algorithms.binding.MoodBinding
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.*

class GloomPluginParityTest {
    @Test fun capturesSkinFormsBeforeModernKeyformAllocation() {
        val document = Characters.mouthed(withNose = true)
        val config = WorkspacePresetBaseline.configure(document.rawConfig(), "modern")
            .copy(mouthOutlineEnabled = false, generateDeformers = true)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        var captured: (() -> PuppetModel)? = null
        val built = RigBuilder.build(analysis, atlas, config, cache) { captured = it }
        val source = assertNotNull(captured).invoke()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val lowered = built.puppet.drawables.associateBy { it.id }
        val withOrdinaryForms = mutableSetOf<SemanticTag>()
        for (expected in source.drawables) {
            val layer = layers.getValue(built.layerIdByDrawableId.getValue(expected.id.raw))
            if (layer.semantic.tag !in setOf(SemanticTag.FACE, SemanticTag.NOSE)) continue
            val live = lowered.getValue(expected.id)
            val actual = RigBuilder.skinLoweringSource(layer, context, config, atlas, cache, live)
            assertEquals(PuppetIr.meshToIr(expected), PuppetIr.meshToIr(actual))
            if (expected.blendShapes.size > live.blendShapes.size) withOrdinaryForms += layer.semantic.tag
        }
        assertEquals(setOf(SemanticTag.FACE, SemanticTag.NOSE), withOrdinaryForms)
    }

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
        // A genuinely pre-keyed mesh and the folded shape expose the same live IR. Only
        // the latter is eligible for a carrier when adding Gloom crosses the budget.
        val preKeyed = source.copy(drawables = PuppetIr.toPuppet(input).drawables)
        val baked = kernel.evaluate(preKeyed, emptyList(), emptyMap(), emptySet(), influence, cheek = false, gloom = false)
        assertEquals(input.meshes, PuppetIr.toIr(baked.model).meshes)
        assertTrue(kernel.evaluate(preKeyed, listOf(gloom), emptyMap(), emptySet(), influence,
            cheek = false, gloom = true).model.deformers.isEmpty())
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
