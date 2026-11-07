package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeFittedAlgorithm
import io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm
import io.github.psd2live.algorithms.binding.EyeFittedV4Algorithm
import io.github.psd2live.algorithms.binding.EyeFittedV5Algorithm

/** Explicit source eye versions coexist with the compatibility version. */
internal object RigFittedEyeDefinition {
    fun mixedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val standalone = definition(source)
        val algorithm = object : ChangingModifierAlgorithmPlugin by EyeFittedV4Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = EyeFittedSourceAlgorithm.HEAD_TURN + "body/legacy"
            override val canonicalOptInType = EyeFittedSourceAlgorithm.TYPE
        }
        return standalone.copy(version = EyeFittedV4Algorithm.VERSION,
            evaluate = { _, _, _ -> error("Fitted eye v4 requires captured scaffold inputs") },
            plugin = algorithm, authorContext = { instance ->
                val owner = requireNotNull(source)
                RigFittedEyeHeadSource.capture(owner, instance) ?: RigFittedEyeSource.captureTransition(owner, instance)
            })
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val algorithm = object : ChangingModifierAlgorithmPlugin by EyeFittedV5Algorithm(),
            ModifierVersionSelection {
            override val canonicalOptInType = EyeFittedSourceAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeFittedV5Algorithm.VERSION, requires = EyeFittedV5Algorithm.REQUIRES,
            evaluate = { _, _, _ -> error("Fitted eye v5 requires captured scaffold inputs") },
            plugin = algorithm, authorContext = { instance ->
                requireNotNull(RigFittedEyeHeadSource.capture(requireNotNull(source), instance, modern = true)) {
                    "Fitted eye v5 requires a preceding fitted face"
                }
            })
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(EyeFittedSourceAlgorithm.TYPE), source)
        val algorithm = object : ChangingModifierAlgorithmPlugin by EyeFittedSourceAlgorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = EyeFittedSourceAlgorithm.HEAD_TURN
            override val canonicalOptInType = EyeFittedSourceAlgorithm.TYPE
        }
        return legacy.copy(version = EyeFittedAlgorithm.VERSION,
            resolveParameter = { _, _, _ -> null },
            parameters = { EyeFittedAlgorithm.parameters(RigFittedEyeSource.parameterNames()) },
            evaluate = { _, _, _ -> error("Fitted eye v3 requires captured scaffold inputs") },
            plugin = algorithm, upstreamInputs = true, changeWrites = EyeFittedSourceAlgorithm.WRITES,
            authorContext = { RigFittedEyeSource.captureTransition(requireNotNull(source), it) })
    }
}
