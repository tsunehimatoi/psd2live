package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.Side

/** A drawing's nearest compatible opening. Numeric name suffixes do not declare alternative drawings. */
internal object EyeOpeningAssociation {
	fun select(layer: ClassifiedLayer, openings: List<ClassifiedLayer>): List<ClassifiedLayer> {
		val sides = if (layer.semantic.side == Side.NONE) listOf(Side.LEFT, Side.RIGHT) else listOf(layer.semantic.side)
		return sides.mapNotNull { side ->
			val compatible = openings.filter { it.semantic.side == side || it.semantic.side == Side.NONE }
			val state = compatible.filter {
				it.semantic.type == LayerType.SWITCH && layer.semantic.type == LayerType.SWITCH &&
				it.semantic.parameter == layer.semantic.parameter && it.semantic.switchId == layer.semantic.switchId
			}.ifEmpty { compatible.filter { it.semantic.type != LayerType.SWITCH } }
			val candidates = state.ifEmpty { compatible }
			candidates.minWithOrNull(compareByDescending<ClassifiedLayer> { candidate ->
				layer.source.groupPath.split('/').zip(candidate.source.groupPath.split('/')).takeWhile { it.first == it.second }.size
			}.thenBy { candidate ->
				val b = candidate.bounds
				val dx = layer.centroidX - layer.centroidX.coerceIn(b.left, b.right)
				val dy = layer.centroidY - layer.centroidY.coerceIn(b.top, b.bottom)
				dx * dx + dy * dy
			}.thenBy { candidate ->
				val dx = layer.centroidX - candidate.centroidX; val dy = layer.centroidY - candidate.centroidY
				dx * dx + dy * dy
			}.thenBy { it.source.id.raw })
		}.distinctBy { it.source.id }
	}
}
