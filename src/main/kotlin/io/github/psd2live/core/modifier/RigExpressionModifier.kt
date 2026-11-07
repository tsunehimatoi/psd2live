package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ExpressionV2Algorithm

internal object RigExpressionModifier {
    private val plugin = object : ContextualModifierAlgorithmPlugin by ExpressionV2Algorithm(), ModifierVersionSelection {
        override val canonicalOptInType = ExpressionV2Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(ExpressionV2Algorithm.TYPE), source).copy(
            version = ExpressionV2Algorithm.VERSION,
            parameters = { requireNotNull(source).expressionSourceParameters(it) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Expressions v2 require captured layer inputs") },
            plugin = plugin,
            authorContext = { requireNotNull(source).expressionContext(it, requireUpstreamStandards = true) },
        )
}
