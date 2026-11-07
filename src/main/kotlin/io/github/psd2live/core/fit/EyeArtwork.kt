package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.RasterDigest
import io.github.psd2live.core.modifier.RigSourceCapture
import io.github.psd2live.core.recipe.EyePartOverride
import java.security.MessageDigest

typealias EyeRegion = io.github.psd2live.algorithms.binding.EyeRegion
typealias EyePrimitive = io.github.psd2live.algorithms.binding.EyePrimitive
typealias EyePoint = io.github.psd2live.algorithms.binding.EyePoint
typealias EyeStroke = io.github.psd2live.algorithms.binding.EyeStroke
typealias EyeContact = io.github.psd2live.algorithms.binding.EyeContact
typealias EyeBranch = io.github.psd2live.algorithms.binding.EyeBranch
typealias EyeMaterialPart = io.github.psd2live.algorithms.binding.EyeMaterialPart
typealias EyeComponent = io.github.psd2live.algorithms.binding.EyeComponent
typealias EyeArtwork = io.github.psd2live.algorithms.binding.EyeArtwork

/** Capture the historical component stamp with the source, keeping stored corrections addressable. */
object EyeArtworkFitting {
    internal fun stamp(layer: ClassifiedLayer): String {
        val src = layer.source
        val stamp = "eye-artwork-v1:${src.raster.width}:${src.raster.height}:${src.bounds}:${RasterDigest.of(src.raster.rgba)}"
        return MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun fit(layers: List<ClassifiedLayer>, fits: Map<Side, EyeFit>,
            overrides: Map<String, Map<String, EyePartOverride>> = emptyMap(),
            toRig: (Float, Float) -> Pair<Float, Float>): Map<Side, EyeArtwork> {
        if (fits.isEmpty()) return emptyMap()
        val lashes = layers.filter { it.semantic.tag == SemanticTag.EYELASH && it.opaquePixels > 0 }
        return io.github.psd2live.algorithms.binding.EyeArtworkFitting.fit(
            RigSourceCapture.capture(lashes), lashes.associate { it.source.id.raw to stamp(it) }, fits, overrides, toRig)
    }
}
