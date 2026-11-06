package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** Local color/opacity algorithm shared by complete generation and incremental source templates. */
object MoodChannels {
    val parameters = setOf(ParameterId("ParamCheek"), ParameterId("ParamGloom"))

    fun apply(grids: ChannelGrids, warmth: Float, darkness: Float, highlight: Boolean, cheek: Boolean, gloom: Boolean): ChannelGrids {
        if (!cheek && !gloom) return grids
        var out = grids.gridsByChannel
        val axes = listOfNotNull(
            KeyformAxis(ParameterId("ParamCheek"), floatArrayOf(0f, 1f)).takeIf { cheek && warmth > 0f },
            KeyformAxis(ParameterId("ParamGloom"), floatArrayOf(0f, 1f)).takeIf { gloom && darkness > 0f })
        if (axes.isNotEmpty() && FormChannel.MULTIPLY_COLOR !in out) {
            val cells = mutableListOf<KeyformCell<ChannelValue>>()
            fun visit(index: Int, coordinate: IntArray) {
                if (index < axes.size) {
                    for (key in 0..1) { coordinate[index] = key; visit(index + 1, coordinate) }
                } else {
                    val c = if (axes.first().parameterId.raw == "ParamCheek") coordinate.first() * warmth else 0f
                    val g = (if (axes.last().parameterId.raw == "ParamGloom") coordinate.last().toFloat() else 0f) * darkness * .13f
                    cells += KeyformCell(coordinate.copyOf(), ChannelValue.Color(ColorRgb(
                        (1f - g).coerceIn(0f, 1f), (1f - c - g).coerceIn(0f, 1f), (1f - c - .6f * g).coerceIn(0f, 1f))))
                }
            }
            visit(0, IntArray(axes.size))
            out = out + (FormChannel.MULTIPLY_COLOR to KeyformGrid(axes, cells))
        }
        if (gloom && highlight && FormChannel.OPACITY !in out) {
            out = out + (FormChannel.OPACITY to KeyformGrid(listOf(KeyformAxis(ParameterId("ParamGloom"), floatArrayOf(0f, 1f))),
                (0..1).map { KeyformCell(intArrayOf(it), ChannelValue.Scalar(1f - .85f * it) as ChannelValue) }))
        }
        return if (out === grids.gridsByChannel) grids else ChannelGrids(out)
    }
}
