package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.modifier.RigSourceCapture

typealias EyeFit = io.github.psd2live.algorithms.binding.EyeFit
typealias EyelidModel = io.github.psd2live.algorithms.binding.EyelidModel

/** Source capture adapter; fitting runs in the independent binding module. */
object EyeFitting {
    class Result(val fits: Map<Side, EyeFit>, val reasons: Map<Side, String>, val level: Pair<Float, Float> = 1f to 0f)

    fun fit(layers: List<ClassifiedLayer>, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>): Result {
        val relevant = layers.filter { it.semantic.tag in setOf(SemanticTag.EYEWHITE, SemanticTag.EYE_CLOSE, SemanticTag.IRIDES) }
        val sources = RigSourceCapture.capture(relevant)
        val result = io.github.psd2live.algorithms.binding.EyeFitting.fit(sources, faceCenterX, toRig)
        return Result(result.fits, result.reasons, result.level)
    }
}
