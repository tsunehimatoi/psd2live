package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV3Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV4Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV5Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigMouthCheekLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    private val fittedMouthAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    fun fittedMouthDefinition(source: RigModifierSource?): RigModifierDefinition = fittedDefinition(source).copy(
        version = MouthCheekLidV4Algorithm.VERSION,
        plugin = fittedMouthAlgorithm,
    )

    private val fittedEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    fun fittedEyeDefinition(source: RigModifierSource?): RigModifierDefinition = fittedDefinition(source).copy(
        version = MouthCheekLidV5Algorithm.VERSION,
        plugin = fittedEyeAlgorithm,
    )

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = MouthCheekLidV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
        authorContext = { instance ->
            val capture = requireNotNull(source)
            val (measurements, carriers) = inputs(capture, instance)
            val browCover = if (capture.activeBefore(instance).any { it.type == "brow.lid-coupling" }) {
                RigBuilder.rigContext(capture.analysis, capture.sourceConfig(), capture.meshCache, neutralScaffold = true).browCover
            } else 0f
            MouthCheekLidV3Algorithm.context(measurements, carriers, browCover)
        },
    )

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
                val (measurements, carriers) = inputs(requireNotNull(source), instance)
                MouthCheekLidAlgorithm.context(measurements, carriers)
            },
        )

    private fun inputs(capture: RigModifierSource, instance: RigModifierInstance): Pair<ModifierAlgorithmContext, Map<String, Pair<String, String>>> {
        val eye = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val measurements = captureLidSource(capture, instance, emptyMap(), eye)
        val layers = capture.analysis.layers.associateBy { it.source.id.raw }
        return measurements to instance.targets.associate { target ->
            val layer = capture.layersByMesh.getValue(target.id)
            target.id to (capture.primaryByLayer.getValue(layer) to tr("model.deformer.meshShape", layers.getValue(layer).source.name))
        }
    }
}
