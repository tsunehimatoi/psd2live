package io.github.psd2live.core.fit

import io.github.psd2live.algorithms.binding.EyeOpeningAssociation as SourceAssociation
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.modifier.ModifierSourceBounds

internal object EyeOpeningAssociation {
    fun observation(layer: ClassifiedLayer) = SourceAssociation.Layer(
        layer.source.id.raw, layer.semantic.side, layer.semantic.type.name, layer.semantic.parameter,
        layer.semantic.switchId, layer.source.groupPath, layer.bounds.let { ModifierSourceBounds(it.left, it.top, it.right, it.bottom) },
        layer.centroidX, layer.centroidY,
    )

    fun select(layer: ClassifiedLayer, openings: List<ClassifiedLayer>): List<ClassifiedLayer> {
        val byId = openings.associateBy { it.source.id.raw }
        return SourceAssociation.select(observation(layer), openings.map(::observation)).map { byId.getValue(it.id) }
    }
}
