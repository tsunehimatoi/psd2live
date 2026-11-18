package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthFittedInputs

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.keyform.*
import org.umamo.runtime.model.*

/** Authored opacity stops, independent of the fitted mouth's geometry keys. */
internal data class ClosedMouthVisibility(val points: List<Point>) {
    data class Point(val open: Float, val opacity: Float)

    init {
        require(points.size in 2..128 && points.all {
            it.open.isFinite() && it.open in 0f..1f && it.opacity.isFinite() && it.opacity in 0f..1f
        }) { "Visibility needs 2–128 keys with Mouth Open and opacity between 0 and 1" }
        require(points.zipWithNext().all { (a, b) -> b.open - a.open >= EPS_SPAN }) { "Visibility keys must be distinct and ordered" }
    }

    fun toJson() = JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it.open), JsonPrimitive(it.opacity))) })

    fun grid(): KeyformGrid<ChannelValue> = KeyformGrid(
        listOf(KeyformAxis(PARAMETER, points.map { it.open }.toFloatArray())),
        points.mapIndexed { i, p -> KeyformCell(intArrayOf(i), ChannelValue.Scalar(p.opacity)) },
    )

    fun sample(open: Float): Float = ChannelGrids(mapOf(FormChannel.OPACITY to grid())).scalarAt(FormChannel.OPACITY, 1f,
        { open.coerceIn(points.first().open, points.last().open) })

    // Use the runtime's ordinary channel-grid editing and interpolation, just like other keyforms.
    fun insert(open: Float): ClosedMouthVisibility {
        require(open.isFinite() && open in 0f..1f)
        return fromGrid(grid().withKeyInserted(PARAMETER, open, ChannelValueInterpolator))
    }
    fun move(from: Float, to: Float): ClosedMouthVisibility {
        val grid = grid()
        val index = grid.keyIndexAt(PARAMETER, from)
        require(index >= 0 && grid.keyDestinationFor(PARAMETER, index, to) == to) { "Visibility keys must remain distinct" }
        return fromGrid(grid.withKeyMoved(PARAMETER, index, to))
    }
    fun remove(open: Float): ClosedMouthVisibility {
        require(points.size > 2) { "Keep at least two visibility keys" }
        val grid = grid()
        val index = grid.keyIndexAt(PARAMETER, open)
        require(index >= 0) { "Visibility key no longer exists" }
        return fromGrid(requireNotNull(grid.withKeyRemoved(PARAMETER, index)))
    }
    fun setOpacity(open: Float, opacity: Float): ClosedMouthVisibility {
        require(open.isFinite() && open in 0f..1f && opacity.isFinite() && opacity in 0f..1f)
        return fromGrid(grid().withFormCaptured({ open }, ChannelValue.Scalar(opacity), ChannelValueInterpolator))
    }

    companion object {
        const val CONFIG = "closedMouthVisibility"
        val PARAMETER = ParameterId("ParamMouthOpenY")
        val Default = ClosedMouthVisibility(listOf(Point(0f, 1f), Point(MouthRig.SWAP, 0f), Point(1f, 0f)))
        fun targets(inputs: MouthFittedInputs, targets: Set<String>): Set<String> {
            if (inputs.source == null) return emptySet()
            val layers = inputs.layers.filter { (id, layer) -> id in targets && layer.preset &&
                layer.variant == inputs.variant && layer.tag in setOf("MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE") }
            if (layers.values.none { it.tag == "MOUTH_CLOSE" && it.opaque } ||
                layers.values.none { it.tag != "MOUTH_CLOSE" && it.opaque }) return emptySet()
            return layers.keys
        }
        fun fromConfig(config: JsonObject): ClosedMouthVisibility? = config[CONFIG]?.let { value ->
            ClosedMouthVisibility(value.jsonArray.map { pair ->
                require(pair.jsonArray.size == 2) { "Each visibility key needs Mouth Open and opacity" }
                Point(pair.jsonArray[0].jsonPrimitive.float, pair.jsonArray[1].jsonPrimitive.float)
            })
        }
        private fun fromGrid(grid: KeyformGrid<ChannelValue>) = ClosedMouthVisibility(grid.axes.single().keys.mapIndexed { i, open ->
            Point(open, (grid.cells.single { it.coordinate.single() == i }.form as ChannelValue.Scalar).value)
        })
    }
}
