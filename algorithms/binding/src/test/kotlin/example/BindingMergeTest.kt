package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class BindingMergeTest {
    @Test fun mergesCapturedChannelsWithoutApplicationServicesAndHonorsCancellation() {
        val mesh = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)), null)
        val before = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val channels = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(emptyList(),
            listOf(KeyformCell(IntArray(0), ChannelValue.Scalar(.5f))))))
        val desired = before.copy(drawables = listOf(mesh.copy(channelGrids = channels)))
        val instance = RigModifierInstance("change", "example", 1, listOf(ModifierTarget("mesh", "mesh")))
        val inputs = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = instance.copy(type = "rig.scaffold")
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = before to desired
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter): io.github.psd2live.format.model.Parameter? = null
            override fun static(model: PuppetModel) = model
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                BindingComparison().onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = emptyMap<DeformerId, DeformerId>()
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = emptyMap<PartId, PartId>()
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean): String = error("No generated part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit): List<org.umamo.edit.VertexSource> =
                error("Native geometry needs no vertex projection")
        }
        val result = BindingMerge.apply(before, instance, inputs) {}
        assertEquals(PuppetIr.toIr(desired.withDerivedRenderRoot()), PuppetIr.toIr(result))
        assertSame(ChannelGrids.Empty, before.drawables.single().channelGrids)
        assertFailsWith<CancellationException> {
            BindingMerge.apply(before, instance, inputs) { throw CancellationException() }
        }
    }
}
