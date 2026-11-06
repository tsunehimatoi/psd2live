package io.github.psd2live.algorithms.binding

import org.umamo.runtime.eval.*
import org.umamo.runtime.model.*

/** Composes generated channel changes with authored channel residuals. */
object ChannelResidual {
    fun channels(current: PuppetModel, actual: Drawable, previous: Drawable, desired: Drawable,
                 checkpoint: () -> Unit): ChannelGrids {
        val live = current.parameters.associateBy { it.id }
        val channelIds = actual.channelGrids.gridsByChannel.keys + previous.channelGrids.gridsByChannel.keys + desired.channelGrids.gridsByChannel.keys
        return ChannelGrids(channelIds.associateWith { channel ->
            val axes = unionAxes((actual.channelGrids[channel]?.axes.orEmpty() + previous.channelGrids[channel]?.axes.orEmpty() +
                desired.channelGrids[channel]?.axes.orEmpty()).filter { it.parameterId in live })
            val cells = coordinates(axes, 4).map { coordinate ->
                checkpoint()
                val pose = axes.indices.associate { axes[it].parameterId to axes[it].keys[coordinate[it]] }
                fun parameter(d: Drawable, id: ParameterId): Float {
                    val value = pose[id] ?: live[id]?.default ?: 0f
                    // A generated switch may acquire a larger range. Project its old automatic
                    // contribution to the nearest old knot, including the authored residual on
                    // that contribution; falling back to static channels would discard that residual.
                    if (previous.channelGrids[channel]?.axes.orEmpty().any { it.parameterId == id }) {
                        val axis = d.channelGrids[channel]?.axes?.singleOrNull { it.parameterId == id }
                        if (axis != null) return value.coerceIn(axis.keys.first(), axis.keys.last())
                    }
                    return value
                }
                fun scalar(d: Drawable) = d.channelGrids.scalarAt(channel,
                    if (channel == FormChannel.DRAW_ORDER) d.drawOrder else d.opacity, paramValue = { parameter(d, it) })
                fun color(d: Drawable) = d.channelGrids.colorAt(channel,
                    if (channel == FormChannel.MULTIPLY_COLOR) d.multiplyColor else d.screenColor, paramValue = { parameter(d, it) })
                val value = when (channel.valueKind) {
                    ChannelValueKind.SCALAR -> ChannelValue.Scalar(scalar(actual) - scalar(previous) + scalar(desired))
                    ChannelValueKind.COLOR -> {
                        val a = color(actual); val b = color(previous); val c = color(desired)
                        ChannelValue.Color(ColorRgb(a.red - b.red + c.red, a.green - b.green + c.green, a.blue - b.blue + c.blue))
                    }
                    ChannelValueKind.FLAG -> {
                        val a = actual.channelGrids.flagAt(channel, false, paramValue = { parameter(actual, it) })
                        val b = previous.channelGrids.flagAt(channel, false, paramValue = { parameter(previous, it) })
                        ChannelValue.Flag(if (a != b) a else desired.channelGrids.flagAt(channel, false, paramValue = { parameter(desired, it) }))
                    }
                }
                KeyformCell(coordinate, value)
            }
            KeyformGrid(axes, cells)
        })
    }

    /** All current knot values survive; generated near-duplicates use the existing knot. */
    private fun unionAxes(input: List<KeyformAxis>): List<KeyformAxis> = input.groupBy { it.parameterId }.map { (id, axes) ->
        val values = mutableListOf<Float>()
        axes.forEach { axis -> axis.keys.forEach { value -> if (values.none { kotlin.math.abs(it - value) < EPS_KEY }) values += value } }
        KeyformAxis(id, values.sorted().toFloatArray())
    }

    private fun coordinates(axes: List<KeyformAxis>, components: Int): List<IntArray> {
        var count = 1L
        for (axis in axes) {
            require(axis.keys.isNotEmpty()) { "Generation migration has an empty axis" }
            count = Math.multiplyExact(count, axis.keys.size.toLong())
            require(count <= 262144 && count * components <= 16777216) { "Generation migration exceeds the keyform allocation limit" }
        }
        return (0 until count.toInt()).map { index ->
            var value = index
            IntArray(axes.size) { axis -> (value % axes[axis].keys.size).also { value /= axes[axis].keys.size } }
        }
    }

}
