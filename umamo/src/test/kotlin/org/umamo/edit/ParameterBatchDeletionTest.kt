package org.umamo.edit

import org.umamo.runtime.model.*
import kotlin.test.*

class ParameterBatchDeletionTest {
    @Test fun batchMatchesOrderedDeletionIncludingLiftedPartChannelsAndRenderTree() {
        val x = ParameterId("x"); val y = ParameterId("y")
        val axes = listOf(KeyformAxis(x, floatArrayOf(0f, 1f)), KeyformAxis(y, floatArrayOf(0f, 1f)))
        val channels = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(axes,
            (0..1).flatMap { a -> (0..1).map { b -> KeyformCell(intArrayOf(a, b), ChannelValue.Scalar(1f - a * .3f - b * .2f)) } })))
        val part = Part(PartId("part"), "part", emptyList(), channelGrids = channels)
        val model = PuppetModel(listOf(Parameter(x, "x", 0f, 1f, .25f), Parameter(y, "y", 0f, 1f, .75f)),
            listOf(part), emptyList(), emptyList(), listOf(OrgChild.Part(part.id)), null,
            parameterLinks = listOf(ParameterLink(x, y)),
            parameterTree = listOf(ParameterNode.Param(x), ParameterNode.Param(y))).withDerivedRenderRoot()
        for (ids in listOf(listOf(x, y), listOf(y, x), listOf(x, x, ParameterId("missing")))) {
            val expected = ids.fold(model) { current, id -> current.withParameterDeleted(id) }
            val actual = model.withParametersDeleted(ids)
            val expectedChannels = expected.parts.single().channelGrids.gridsByChannel
            val actualChannels = actual.parts.single().channelGrids.gridsByChannel
            assertEquals(expectedChannels.keys, actualChannels.keys)
            for ((channel, grid) in expectedChannels) {
                val other = actualChannels.getValue(channel)
                assertEquals(grid.axes.map { it.parameterId to it.keys.toList() }, other.axes.map { it.parameterId to it.keys.toList() })
                assertEquals(grid.cells.map { it.coordinate.toList() to it.form }, other.cells.map { it.coordinate.toList() to it.form })
            }
            assertEquals(expected, actual.copy(parts = actual.parts.map { it.copy(channelGrids = expected.parts.single().channelGrids) }))
        }
        assertSame(model, model.withParametersDeleted(emptyList()))
        assertSame(model, model.withParametersDeleted(listOf(ParameterId("missing"))))
        var calls = 0
        assertFailsWith<InterruptedException> {
            model.withParametersDeleted(listOf(x, y)) { if (++calls == 2) throw InterruptedException() }
        }
        assertEquals(2, model.parameters.size)
        assertEquals(channels, model.parts.single().channelGrids)
    }
}
