package example

import io.github.psd2live.algorithms.binding.MoodBinding
import org.umamo.runtime.model.*
import kotlin.test.*

class MoodBindingTest {
    @Test fun repeatedEvaluationReplacesGeneratedColorWithoutDuplicatingParametersOrTouchingOtherMeshes() {
        val cheek = Parameter(ParameterId("ParamCheek"), "Cheek", 0f, 1f, 0f)
        val dynamic = Parameter(ParameterId("Hair"), "Hair", -1f, 1f, 0f)
        val face = Drawable(DrawableId("Face"), "Face", null, BlendMode.Normal, emptyList(), null, null)
        val untouched = face.copy(id = DrawableId("Other"))
        val source = PuppetModel(listOf(dynamic), emptyList(), emptyList(), listOf(face, untouched), emptyList(), null,
            parameterTree = listOf(ParameterNode.Group(ParameterGroupId("ParamGroupFace"), "Face", true, emptyList())))
        val kernel = MoodBinding({ _, _ -> error("No geometry to lower") })
        fun evaluate(input: PuppetModel, warmth: Float) = kernel.evaluate(input, listOf(cheek), mapOf(cheek.id to 0),
            setOf(dynamic.id), mapOf(face.id to MoodBinding.Influence(warmth, 0f, false)), true, false)
        val first = evaluate(source, .1f)
        val second = evaluate(first.source, .3f)
        assertEquals(listOf(cheek.id, dynamic.id), second.model.parameters.map { it.id })
        assertEquals(listOf(ParameterNode.Param(cheek.id)), (second.model.parameterTree.single() as ParameterNode.Group).children)
        val grid = second.model.drawables.first().channelGrids[FormChannel.MULTIPLY_COLOR]!!
        assertEquals(.7f, (grid.cells.last().form as ChannelValue.Color).color.green, .00001f)
        assertSame(untouched, second.model.drawables.last())
        assertSame(ChannelGrids.Empty, source.drawables.first().channelGrids)
        assertEquals(listOf(dynamic), source.parameters)
    }
}
