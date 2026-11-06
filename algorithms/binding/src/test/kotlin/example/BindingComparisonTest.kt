package example

import io.github.psd2live.algorithms.binding.BindingComparison
import org.umamo.runtime.model.*
import kotlin.test.*

class BindingComparisonTest {
    @Test fun acceptsOnlyClippingChangesAndComparesArrayContentsAndAncestorChannels() {
        val parent = Deformer.Warp(DeformerId("parent"), "Parent", null, null, 1, 1, true, null)
        val geometry = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2))
        val mesh = Drawable(DrawableId("mesh"), "Mesh", parent.id, BlendMode.Normal, emptyList(), geometry, null)
        val before = PuppetModel(emptyList(), emptyList(), listOf(parent), listOf(mesh), emptyList(), null)
        val clipped = mesh.copy(maskedBy = listOf(DrawableId("mask")), invertMask = true,
            mesh = DrawableMesh(geometry.positions.copyOf(), geometry.uvs.copyOf(), geometry.indices.copyOf()))
        val after = before.copy(drawables = listOf(clipped))
        val comparison = BindingComparison()
        fun check(model: PuppetModel) = comparison.onlyMasksChange(before, model, mesh.id, setOf(parent.id))
        assertTrue(check(after))
        assertFalse(check(after.copy(drawables = listOf(clipped.copy(opacity = .5f)))))
        assertFalse(check(after.copy(drawables = listOf(clipped.copy(mesh = DrawableMesh(
            geometry.positions.copyOf().also { it[0] = .1f }, geometry.uvs, geometry.indices))))))
        val channels = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(emptyList(),
            listOf(KeyformCell(IntArray(0), ChannelValue.Scalar(.7f))))))
        assertFalse(check(after.copy(drawables = listOf(clipped.copy(channelGrids = channels)))))
        assertFalse(check(after.copy(deformers = listOf(parent.copy(channelGrids = channels)))))
        assertFalse(check(after.copy(deformers = emptyList())))
        assertTrue(check(after))
    }
}
