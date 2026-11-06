package example

import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierReadKey
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Floats
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.*

class ScaffoldReadInputsTest {
    private val target = drawable("target")
    private val others = listOf(drawable("other-a"), drawable("other-b"))
    private val authored = DeformerId("authored")
    private val frame = DeformerId("source-frame")
    private val part = PartId("source-part")
    private val material = PuppetModel(emptyList(), emptyList(), listOf(warp(authored, null)), listOf(target) + others,
        listOf(OrgChild.Drawable(target.id)), null).withDerivedRenderRoot()
    private val template = PuppetModel(emptyList(), listOf(Part(part, "Source", listOf(OrgChild.Drawable(target.id)))),
        listOf(warp(frame, part)), listOf(target.copy(parentDeformerId = frame)), listOf(OrgChild.Part(part)), null)
    private val context = ScaffoldAlgorithm.capture(template, material, mapOf(target.id.raw to target.id.raw))
    private val instance = RigModifierInstance("structure", "rig.scaffold", 2, listOf(ModifierTarget("mesh", target.id.raw)))

    @Test fun unrelatedMeshGeometryAndAppearanceCanReuseTheObservedChanges() {
        val input = PuppetIr.toIr(material)
        val view = ModifierEvaluationInput(input, context)
        val algorithm = ScaffoldAlgorithm()
        val changes = algorithm.changes(view, instance) {}
        val reads = view.reads()
        assertTrue(ModifierReadKey("meshIds") in reads.keys)
        assertEquals(setOf(ModifierReadKey("mesh", target.id.raw)), reads.keys.filter { it.section == "mesh" }.toSet())
        assertTrue(ModifierReadKey("context") in reads.keys)

        val unrelated = input.meshes[1]
        val changedGeometry = requireNotNull(unrelated.geometry).copy(
            positions = Floats.values(0.1f, 0.1f, 0.8f, 0.2f, 0.3f, 0.9f),
            uvs = Floats.values(0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f),
        )
        val replacement = unrelated.copy(geometry = changedGeometry, opacity = 0.42f, drawOrder = 37f)
        val updated = input.copy(meshes = listOf(input.meshes[0], replacement, input.meshes[2]))
        assertTrue(reads.matches(updated, context))
        val reused = changes.apply(updated)
        assertSame(replacement, reused.meshes[1])
        assertEquals(algorithm.evaluate(updated, instance, context) {}, reused)
    }

    @Test fun meshMembershipOrderTargetAndAuthoredFrameInvalidate() {
        val reparented = material.copy(drawables = material.drawables.map {
            if (it.id == target.id) it.copy(parentDeformerId = authored) else it
        })
        val input = PuppetIr.toIr(reparented)
        val view = ModifierEvaluationInput(input, context)
        ScaffoldAlgorithm().changes(view, instance) {}
        val reads = view.reads()
        assertTrue(reads.matches(input, context))
        assertFalse(reads.matches(input.copy(meshes = input.meshes.dropLast(1)), context))
        val added = input.meshes.last().copy(id = "added")
        assertFalse(reads.matches(input.copy(meshes = input.meshes + added), context))
        assertFalse(reads.matches(input.copy(meshes = listOf(input.meshes[0], input.meshes[2], input.meshes[1])), context))
        val changedTarget = input.meshes.first().copy(parent = null)
        assertFalse(reads.matches(input.copy(meshes = listOf(changedTarget) + input.meshes.drop(1)), context))
        val authoredFrameChanged = reparented.copy(deformers = listOf(warp(authored, null, 2f)))
        assertFalse(reads.matches(PuppetIr.toIr(authoredFrameChanged), context))
    }

    private fun drawable(id: String) = Drawable(
        DrawableId(id), id, null, BlendMode.Normal, emptyList(),
        DrawableMesh(floatArrayOf(0.2f, 0.2f, 0.7f, 0.2f, 0.2f, 0.7f), FloatArray(6), intArrayOf(0, 1, 2)), null,
    )

    private fun warp(id: DeformerId, part: PartId?, scale: Float = 1f) = Deformer.Warp(
        id, id.raw, null, part, 1, 1, true,
        KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0),
            WarpLatticeForm(floatArrayOf(0f, 0f, scale, 0f, 0f, scale, scale, scale))))),
    )
}
