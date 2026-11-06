package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*
import kotlin.math.abs

/** Source-independent expression channel construction shared by full and incremental generation. */
object ExpressionChannels {
    data class Layer(val parameter: String, val switchId: Int? = null)
    data class Catalog(val parameters: List<Parameter>, val switchKeys: Map<String, FloatArray>)

    fun catalog(layers: List<Layer>, staggered: Boolean): Catalog {
        val parameters = layers.filter { it.switchId == null && it.parameter.isNotBlank() }
            .map { it.parameter }.distinct().sorted().mapTo(ArrayList()) {
                Parameter(ParameterId(it), it, 0f, 1f, 0f)
            }
        val switches = linkedMapOf<String, FloatArray>()
        layers.filter { it.switchId != null && it.parameter.isNotBlank() }.groupBy { it.parameter }.forEach { (name, group) ->
            val low = group.minOf { requireNotNull(it.switchId) }
            val high = maxOf(group.maxOf { requireNotNull(it.switchId) }, low + 1)
            val count = high - low + 1
            val keys = if (staggered) FloatArray(count * 2 - 1) { low + it * .5f } else FloatArray(count) { (low + it).toFloat() }
            switches[name] = keys
            parameters += Parameter(ParameterId(name), name, keys.first(), keys.last(), keys.first())
        }
        return Catalog(parameters, switches)
    }

    fun opacity(layer: Layer, switchKeys: Map<String, FloatArray>): KeyformGrid<ChannelValue>? {
        if (layer.parameter.isBlank()) return null
        val keys = if (layer.switchId == null) floatArrayOf(0f, 1f) else switchKeys[layer.parameter] ?: return null
        if (keys.isEmpty()) return null
        return KeyformGrid(listOf(KeyformAxis(ParameterId(layer.parameter), keys)), keys.mapIndexed { index, key ->
            val value = if (layer.switchId == null) key else if (abs(key - layer.switchId) <= .5f) 1f else 0f
            KeyformCell(intArrayOf(index), ChannelValue.Scalar(value))
        })
    }
}
