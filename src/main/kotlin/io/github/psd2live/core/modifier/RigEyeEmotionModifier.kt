package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr

internal object RigEyeEmotionModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeEmotionAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeEmotionAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_EMOTION.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) {
            captureLidSource(requireNotNull(source), instance, parameterNames())
        }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(EyeEmotionAlgorithm.TYPE), source).copy(
            version = EyeEmotionAlgorithm.VERSION,
            parameters = { EyeEmotionAlgorithm.parameters(capture(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Eye emotion requires captured source inputs") },
            plugin = algorithm,
            changeWrites = EyeEmotionAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }
}
