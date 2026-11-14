package example

import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.ParameterNode as IrParameterNode
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.*
import kotlin.test.*

class ScaffoldChangesTest {
    private val owner = PartId("authored-part")
    private val sourcePart = PartId("source-part")
    private val frame = DeformerId("source-frame")
    private val upstream = DeformerId("authored-frame")
    private val target = drawable("target", 600f)
    private val other = drawable("other", 200f)
    private val untouched = drawable("untouched", 300f)
    private val ordinary = PuppetModel(
        parameters = emptyList(),
        parts = listOf(
            Part(owner, "Authored group", listOf(OrgChild.Drawable(target.id), OrgChild.Drawable(other.id)),
                groupMode = PartGroupMode.Grouped, drawOrder = 160),
        ),
        deformers = listOf(warp(upstream, owner)),
        drawables = listOf(other, target, untouched),
        rootChildren = listOf(OrgChild.Part(owner), OrgChild.Drawable(untouched.id)),
        rootPartId = null,
    ).withDerivedRenderRoot()
    private val template = PuppetModel(
        emptyList(), listOf(Part(sourcePart, "Source group", listOf(OrgChild.Drawable(target.id)))),
        listOf(warp(frame, sourcePart)), listOf(target.copy(parentDeformerId = frame)),
        listOf(OrgChild.Part(sourcePart)), null,
    )
    private val source = ScaffoldBinding.CapturedSource(mapOf(target.id.raw to template), listOf(target))

    @Test fun directChangesMatchFullBindingAndPreserveUntouchedObjects() {
        for (version in 1..2) for (authored in listOf(false, true)) {
            val instance = instance(version)
            val model = if (authored) ordinary.copy(drawables = ordinary.drawables.map {
                if (it.id == target.id) it.copy(parentDeformerId = upstream) else it
            }) else ordinary
            val input = PuppetIr.toIr(model)
            val expected = PuppetIr.toIr(ScaffoldBinding.apply(PuppetIr.toPuppet(input), instance, source) {})
            val changes = ScaffoldBinding.applyChanges(input, instance, source) {}
            val actual = changes.apply(input)
            assertEquals(expected, actual)
            assertEquals(setOf("part", "deformer", "mesh", "hierarchy"), changes.writeKinds())
            assertEquals(listOf(target.id.raw), changes.meshes.updated.map { it.before.id })
            assertEquals(listOf(owner.raw), changes.parts.updated.map { it.before.id })
            assertEquals(listOf("other"), changes.parts.updated.single().after.children.map {
                (it as io.github.psd2live.format.model.ChildRef.MeshRef).id
            })
            assertEquals(1, changes.deformers.added.size)
            assertEquals(1, changes.parts.added.size)
            assertSame(input.meshes[0], actual.meshes[0])
            assertSame(input.meshes[2], actual.meshes[2])
            assertSame(input.deformers.single(), actual.deformers.first())
            assertFailsWith<IllegalArgumentException> {
                changes.apply(input.copy(meshes = input.meshes.map { if (it.id == target.id.raw) it.copy(opacity = 0.5f) else it }))
            }
        }
    }

    @Test fun directChangesKeepTheInputParameterOrderAndCanBeCanceled() {
        val a = Parameter(ParameterId("a"), "A", 0f, 1f, 0f)
        val b = Parameter(ParameterId("b"), "B", 0f, 1f, 0f)
        val input = PuppetIr.toIr(ordinary.copy(parameters = listOf(a, b))).copy(
            parameterTree = listOf(IrParameterNode.Param(b.id.raw), IrParameterNode.Param(a.id.raw)),
        )
        val changes = ScaffoldBinding.applyChanges(input, instance(2), source) {}
        val result = changes.apply(input)
        assertTrue(changes.parameters.isEmpty)
        assertNull(changes.parameterTree)
        assertSame(input.parameters, result.parameters)
        assertSame(input.parameterTree, result.parameterTree)

        var checks = 0
        assertFailsWith<InterruptedException> {
            ScaffoldBinding.applyChanges(input, instance(2), source) { if (++checks == 6) throw InterruptedException() }
        }
        assertEquals(listOf(target.id.raw, other.id.raw), ordinary.parts.single().children.map { (it as OrgChild.Drawable).id.raw })
    }

    private fun instance(version: Int) = RigModifierInstance("scaffold", "rig.scaffold", version, listOf(ModifierTarget("mesh", target.id.raw)),
        outputAliases = if (version == 2) mapOf("shared/deformer:${frame.raw}" to "kept-warp", "shared/part:${sourcePart.raw}" to "kept-part")
        else emptyMap())

    private fun warp(id: DeformerId, part: PartId) = Deformer.Warp(
        id, id.raw, null, part, 1, 1, true,
        KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))))),
    )

    private fun drawable(id: String, drawOrder: Float) = Drawable(
        DrawableId(id), id, null, BlendMode.Normal, emptyList(),
        DrawableMesh(floatArrayOf(0.2f, 0.2f, 0.7f, 0.2f, 0.2f, 0.7f), FloatArray(6), intArrayOf(0, 1, 2)),
        null, drawOrder = drawOrder,
    )
}
