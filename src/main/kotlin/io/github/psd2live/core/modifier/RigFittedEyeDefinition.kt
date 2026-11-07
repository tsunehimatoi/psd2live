package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeFittedAlgorithm
import io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm

/** Source-only v3 entry for an explicit version selection; persisted v2 instances retain their definition. */
internal object RigFittedEyeDefinition {
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
