package io.github.psd2live.algorithms.binding

/** Source observations needed to find the matching opening without using generated masks. */
data class EyeOpeningObservation(
    val id: String,
    val sourceId: String,
    val side: Side,
    val bounds: Bounds,
    val centroidX: Float,
    val centroidY: Float,
    val groupPath: String,
    val switchParameter: String?,
    val switchId: Int?,
)

/** Numeric name suffixes do not declare alternative drawings. */
object EyeOpeningMatch {
    fun select(layer: EyeOpeningObservation, openings: List<EyeOpeningObservation>): List<EyeOpeningObservation> {
        val sides = if (layer.side == Side.NONE) listOf(Side.LEFT, Side.RIGHT) else listOf(layer.side)
        return sides.mapNotNull { side ->
            val compatible = openings.filter { it.side == side || it.side == Side.NONE }
            val state = compatible.filter {
                it.switchParameter != null && it.switchParameter == layer.switchParameter && it.switchId == layer.switchId
            }.ifEmpty { compatible.filter { it.switchParameter == null } }
            val candidates = state.ifEmpty { compatible }
            candidates.minWithOrNull(compareByDescending<EyeOpeningObservation> { candidate ->
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
            }.thenBy { it.sourceId })
        }.distinctBy { it.sourceId }
    }
}
