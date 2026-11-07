package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import kotlinx.serialization.Serializable

/** Source association for a drawing's nearest compatible opening. */
object EyeOpeningAssociation {
    @Serializable
    data class Layer(
        val id: String, val side: Side, val type: String, val parameter: String, val switchId: Int?,
        val groupPath: String, val bounds: ModifierSourceBounds, val centroidX: Float, val centroidY: Float,
    )

    fun select(layer: Layer, openings: List<Layer>): List<Layer> {
        val sides = if (layer.side == Side.NONE) listOf(Side.LEFT, Side.RIGHT) else listOf(layer.side)
        return sides.mapNotNull { side ->
            val compatible = openings.filter { it.side == side || it.side == Side.NONE }
            val state = compatible.filter {
                it.type == "SWITCH" && layer.type == "SWITCH" && it.parameter == layer.parameter && it.switchId == layer.switchId
            }.ifEmpty { compatible.filter { it.type != "SWITCH" } }
            state.ifEmpty { compatible }.minWithOrNull(compareByDescending<Layer> { candidate ->
                layer.groupPath.split('/').zip(candidate.groupPath.split('/')).takeWhile { it.first == it.second }.size
            }.thenBy { candidate ->
                val b = candidate.bounds
                val dx = layer.centroidX - layer.centroidX.coerceIn(b.left, b.right)
                val dy = layer.centroidY - layer.centroidY.coerceIn(b.top, b.bottom)
                dx * dx + dy * dy
            }.thenBy { candidate ->
                val dx = layer.centroidX - candidate.centroidX
                val dy = layer.centroidY - candidate.centroidY
                dx * dx + dy * dy
            }.thenBy { it.id })
        }.distinctBy { it.id }
    }
}
