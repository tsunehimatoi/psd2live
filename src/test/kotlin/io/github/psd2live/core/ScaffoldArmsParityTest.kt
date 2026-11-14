package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.ScaffoldArms
import io.github.psd2live.core.modifier.RigModifierPipeline
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.*

class ScaffoldArmsParityTest {
    @Test fun armHangLatticesMatchTheLegacyNeutralProjection() {
        val document = Characters.figure()
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(generateDeformers = true)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val context = RigBuilder.rigContext(analysis, config)
        val legacy = PuppetModel(StandardParameters.all, emptyList(), context.deformers, emptyList(), emptyList(), null)
        val arms = BindingProjection.retain(legacy, emptySet()).deformers.filter { it.id.raw.startsWith("DeformArmHang_") }
        assertEquals(listOf("DeformArmHang_L", "DeformArmHang_R"), arms.map { it.id.raw })
        val parents = listOf(Side.LEFT, Side.RIGHT).zip(arms.map { arm ->
            val id = assertNotNull(arm.parent)
            ScaffoldArms.Parent(id, assertNotNull(context.resolveFrame(id, config)))
        }).toMap()
        val source = context.analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0 }
            .map { layer ->
                ScaffoldArms.Layer(layer.source.id.raw, layer.source.name, layer.semantic.side, layer.bounds,
                    parents.getValue(layer.semantic.side))
            }
        val actual = ScaffoldArms.build(source, context.deformers.mapTo(HashSet()) { it.id }, PartId("PartBody"),
            { tr("model.deformer.armHang", it) })
        assertEquals(arms.map(PuppetIr::deformerToIr), actual.map { PuppetIr.deformerToIr(it.warp) })
        assertEquals(arms.map { context.resolveFrame(it.id, config) }, actual.map { it.bounds })
        assertEquals(listOf(Side.LEFT, Side.RIGHT).map { side -> source.filter { it.side == side }.map { it.id } },
            actual.map { it.layerIds })
    }

    @Test fun aSideRequiresOneAvailableParentAndKeepsSourceOrder() {
        val frame = Bounds(2f, 4f, 80f, 82f)
        val parent = ScaffoldArms.Parent(DeformerId("parent"), frame)
        val leftA = ScaffoldArms.Layer("a", "first", Side.LEFT, frame, parent)
        val leftB = leftA.copy(id = "b", name = "second", bounds = Bounds(3f, 5f, 90f, 98f))
        val right = leftA.copy(id = "r", side = Side.RIGHT, parent = parent.copy(id = DeformerId("missing")))
        val part = PartId("body")
        val result = ScaffoldArms.build(listOf(right, leftA, leftB), setOf(parent.id), part, { it }).single()
        assertEquals("DeformArmHang_L", result.warp.id.raw)
        assertEquals("first", result.warp.name)
        assertEquals(listOf("a", "b"), result.layerIds)
        assertEquals(leftA.bounds.union(leftB.bounds).expanded(0.06f), result.bounds)

        val incompatible = leftB.copy(parent = parent.copy(frame = leftB.bounds))
        assertTrue(ScaffoldArms.build(listOf(leftA, incompatible), setOf(parent.id), part, { it }).isEmpty())
    }
}
