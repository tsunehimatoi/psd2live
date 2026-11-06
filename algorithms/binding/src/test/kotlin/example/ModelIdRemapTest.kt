package example

import io.github.psd2live.algorithms.binding.ModelIdRemap
import org.umamo.runtime.model.*
import kotlin.test.*

class ModelIdRemapTest {
    @Test fun folderAndUnrelatedParameterRenamesKeepMeshInputsShared() {
        val parameter = ParameterId("p")
        val channels = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(
            listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
            listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(0f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(1f))))))
        val mesh = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), null, null, channels)
        val group = ParameterGroupId("group")
        val source = PuppetModel(listOf(Parameter(parameter, "p", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(mesh),
            listOf(OrgChild.Drawable(mesh.id)), null,
            parameterTree = listOf(ParameterNode.Group(group, "Group", true, listOf(ParameterNode.Param(parameter)))))
        val folder = ModelIdRemap(groups = mapOf(group to ParameterGroupId("renamed"))).model(source)
        assertEquals("renamed", (folder.parameterTree.single() as ParameterNode.Group).id.raw)
        assertSame(source.drawables, folder.drawables)
        assertSame(source.deformers, folder.deformers)
        assertSame(source.renderRoot, folder.renderRoot)
        val unrelated = ModelIdRemap(parameters = mapOf(ParameterId("unused") to ParameterId("other"))).model(source)
        assertSame(mesh, unrelated.drawables.single())
        val changed = ModelIdRemap(parameters = mapOf(parameter to ParameterId("q"))).model(source)
        assertEquals("q", changed.drawables.single().channelGrids[FormChannel.OPACITY]!!.axes.single().parameterId.raw)
        assertEquals("p", source.drawables.single().channelGrids[FormChannel.OPACITY]!!.axes.single().parameterId.raw)
    }
}
