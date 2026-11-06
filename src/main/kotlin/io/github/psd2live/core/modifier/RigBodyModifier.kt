package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LegacyBodyAlgorithm
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.format.model.Parameter

/** Stable entry point retained for the original body algorithm and its editable fields. */
internal object RigBodyModifier {
    const val TYPE = "body/legacy"
    val parameters get() = RigLegacyBindings.spec(TYPE).parameters
    val fields get() = RigLegacyBindings.spec(TYPE).fields
    fun definition(source: RigModifierSource?) = RigLegacyBindings.definition(spec = RigLegacyBindings.spec(TYPE), source = source)

    /** Versioned source adapter for the package registry once independent head pitch is available. */
    fun sourceDefinition(source: RigModifierSource?, headPitch: (RigModifierInstance) -> ModifierAlgorithmResource?): RigModifierDefinition {
        val algorithm = LegacyBodyAlgorithm()
        return definition(source).copy(
            plugin = algorithm, evaluate = algorithm::evaluate,
            parameters = { StandardParameters.all.filter { it.id in parameters }.map {
                Parameter(it.id.raw, it.name, it.min, it.max, it.default)
            } },
            authorContext = { requireNotNull(source).bodyContext(it, headPitch(it)) },
            changeWrites = setOf("mesh", "deformer", "hierarchy", "relations", "parameter"),
            upstreamInputs = true,
        )
    }
}
