package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV3Algorithm
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object RigEyeEmotionModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeEmotionAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeEmotionAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeEmotionV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeEmotionV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_EMOTION.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) {
            val captured = captureLidSource(requireNotNull(source), instance, parameterNames(), fittedHead = modern)
            if (!modern) captured else ModifierAlgorithmContext(
                JsonObject(captured.values + ("shape_name" to JsonPrimitive(tr("model.deformer.meshShape", "{meshName}")))),
                captured.resources)
        }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(EyeEmotionAlgorithm.TYPE), source).copy(
            version = if (modern) EyeEmotionV3Algorithm.VERSION else EyeEmotionAlgorithm.VERSION,
            parameters = { if (modern) EyeEmotionV3Algorithm.parameters(capture(it)) else EyeEmotionAlgorithm.parameters(capture(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Eye emotion requires captured source inputs") },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) EyeEmotionV3Algorithm.WRITES else EyeEmotionAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }
}
