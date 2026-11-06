package io.github.psd2live.core.modifier

import io.github.psd2live.core.ClassifiedLayer

/** Converts already captured source art to the neutral plugin contract; never generates binding output. */
internal object RigSourceCapture {
    fun capture(layers: List<ClassifiedLayer>, targets: Map<String, String> = emptyMap(),
                checkpoint: () -> Unit = {}): ModifierSourceInputs {
        val resources = LinkedHashMap<String, ModifierAlgorithmResource>()
        val observations = layers.map { layer ->
            checkpoint()
            val source = layer.source
            val raster = source.raster
            val key = source.id.raw
            resources[key] = ModifierAlgorithmResource(raster.rgba)
            ModifierSourceLayer(key, layer.semantic.tag.name, layer.semantic.side.name, layer.semantic.role?.name,
                source.bounds.left.toFloat(), source.bounds.top.toFloat(), raster.width, raster.height, key,
                layer.bounds.let { ModifierSourceBounds(it.left, it.top, it.right, it.bottom) },
                layer.centroidX, layer.centroidY, layer.opaquePixels)
        }
        checkpoint()
        return ModifierSourceInputs(observations, resources, targets)
    }
}
