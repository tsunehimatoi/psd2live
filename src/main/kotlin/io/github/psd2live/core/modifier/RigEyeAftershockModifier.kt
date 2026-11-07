package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr

internal object RigEyeAftershockModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_AFTERSHOCK.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(EyeAftershockAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return legacy.copy(
            version = EyeAftershockAlgorithm.VERSION,
            composableKinds = legacy.composableKinds + "physics",
            parameters = { EyeAftershockAlgorithm.parameters(capture(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Blink aftershock requires captured source inputs") },
            plugin = algorithm,
            changeWrites = EyeAftershockAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val inputs = captureLidSource(this, instance, parameterNames())
        return EyeAftershockAlgorithm.context(inputs, tr("model.group.physics"),
            listOf(tr("model.physics.eyeBounce", "L"), tr("model.physics.eyeBounce", "R")))
    }
}
