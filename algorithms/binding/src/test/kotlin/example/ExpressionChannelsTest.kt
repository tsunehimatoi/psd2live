package example

import io.github.psd2live.algorithms.binding.ExpressionChannels
import org.umamo.runtime.model.ChannelValue
import kotlin.test.*

class ExpressionChannelsTest {
    @Test fun togglesAndSparseSwitchesPreserveOrderingRangesAndOverlap() {
        val layers = listOf(ExpressionChannels.Layer("ToggleB"), ExpressionChannels.Layer("ToggleA"),
            ExpressionChannels.Layer("Mood", 1), ExpressionChannels.Layer("Mood", 3))
        val catalog = ExpressionChannels.catalog(layers, staggered = true)
        assertEquals(listOf("ToggleA", "ToggleB", "Mood"), catalog.parameters.map { it.id.raw })
        assertContentEquals(floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f), catalog.switchKeys.getValue("Mood"))
        val mood = catalog.parameters.last()
        assertEquals(1f, mood.min); assertEquals(3f, mood.max); assertEquals(1f, mood.default)
        val toggle = ExpressionChannels.opacity(layers.first(), catalog.switchKeys)!!
        assertEquals(listOf(ChannelValue.Scalar(0f), ChannelValue.Scalar(1f)), toggle.cells.map { it.form })
        val first = ExpressionChannels.opacity(layers[2], catalog.switchKeys)!!
        assertEquals(listOf(1f, 1f, 0f, 0f, 0f).map(ChannelValue::Scalar), first.cells.map { it.form })
    }

    @Test fun singleVariantRetainsTheFollowingOffKey() {
        val layer = ExpressionChannels.Layer("Variant", 4)
        val plain = ExpressionChannels.catalog(listOf(layer), staggered = false)
        assertContentEquals(floatArrayOf(4f, 5f), plain.switchKeys.getValue("Variant"))
        assertEquals(listOf(ChannelValue.Scalar(1f), ChannelValue.Scalar(0f)),
            ExpressionChannels.opacity(layer, plain.switchKeys)!!.cells.map { it.form })
        assertNull(ExpressionChannels.opacity(ExpressionChannels.Layer(""), emptyMap()))
    }
}
