package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigMouthCheekLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(MouthCheekLidAlgorithm.TYPE), source).copy(
            version = MouthCheekLidAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Cheek and lid coupling requires captured lid inputs") },
            plugin = algorithm,
            changeWrites = MouthCheekLidAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { instance ->
                val capture = requireNotNull(source)
                val eye = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
                val measurements = captureLidSource(capture, instance, emptyMap(), eye)
                val layers = capture.analysis.layers.associateBy { it.source.id.raw }
                MouthCheekLidAlgorithm.context(measurements, instance.targets.associate { target ->
                    val layer = capture.layersByMesh.getValue(target.id)
                    target.id to (capture.primaryByLayer.getValue(layer) to tr("model.deformer.meshShape", layers.getValue(layer).source.name))
                })
            },
        )
}
