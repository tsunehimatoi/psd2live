package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BrowFittedInputs
import io.github.psd2live.i18n.tr

/** Captures the same source measurements as the legacy fit, before constructing any brow keyforms. */
internal object RigFittedBrowSource {
    fun capture(
        context: RigBuilder.RigContext,
        config: PipelineConfig,
        scaffold: String,
        targetLayers: Map<String, Pair<String, ClassifiedLayer>>,
        eyeProvider: String? = null,
    ): BrowFittedInputs {
        val (fits, eyes) = context.fittedBrowMeasurements(config)
        val eyeMeasurements = if (eyes == null) emptyMap() else eyes.rigs.mapValues { (_, eye) ->
            BrowFittedInputs.Eye(requireNotNull(eyeProvider), eye.opening, eye.fit.width)
        }
        val layers = targetLayers.mapNotNull { (mesh, captured) ->
            val (canonical, layer) = captured
            if (layer.semantic.tag != SemanticTag.EYEBROW) return@mapNotNull null
            val frame = context.parentAndFrame(layer, config).second
            val aligned = context.rigLayer(layer)
            mesh to BrowFittedInputs.Layer(canonical, layer.semantic.side, layer.semantic.role == null, frame, aligned.bounds.height)
        }.toMap()
        return BrowFittedInputs(scaffold, fits, eyeMeasurements, layers,
            tr("model.parameter.browLY"), tr("model.parameter.browRY"), tr("model.group.brows"), config.rigTuning.browLift)
    }
}
