package example

import io.github.psd2live.algorithms.binding.MoodChannels
import org.umamo.runtime.model.*
import kotlin.test.*

class MoodChannelsTest {
    @Test fun combinesWarmthAndDarknessInCanonicalAxisOrderAndPreservesExistingTracks() {
        val result = MoodChannels.apply(ChannelGrids.Empty, .3f, 1f, true, true, true)
        val color = result[FormChannel.MULTIPLY_COLOR]!!
        assertEquals(listOf("ParamCheek", "ParamGloom"), color.axes.map { it.parameterId.raw })
        assertEquals(listOf(listOf(0, 0), listOf(0, 1), listOf(1, 0), listOf(1, 1)), color.cells.map { it.coordinate.toList() })
        val last = (color.cells.last().form as ChannelValue.Color).color
        assertEquals(.87f, last.red, .00001f)
        assertEquals(.57f, last.green, .00001f)
        assertEquals(.622f, last.blue, .00001f)
        assertEquals(.15f, (result[FormChannel.OPACITY]!!.cells.last().form as ChannelValue.Scalar).value, .00001f)
        assertSame(result, MoodChannels.apply(result, .6f, 2f, true, true, true))
        assertSame(ChannelGrids.Empty, MoodChannels.apply(ChannelGrids.Empty, 0f, 0f, false, true, true))
    }
}
