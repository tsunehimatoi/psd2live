package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV3Algorithm
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigEyeAftershockModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_AFTERSHOCK.defines.associate { it.id to tr(it.nameKey) }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, EyeAftershockAlgorithm.VERSION, algorithm)

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, EyeAftershockV3Algorithm.VERSION, modernAlgorithm)

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(EyeAftershockAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(
            version = version,
            composableKinds = legacy.composableKinds + "physics",
            parameters = { instance ->
                val context = capture(instance)
                if (version == EyeAftershockV3Algorithm.VERSION) EyeAftershockV3Algorithm.parameters(context)
                else EyeAftershockAlgorithm.parameters(context)
            },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Blink aftershock requires captured source inputs") },
            plugin = algorithm,
            changeWrites = EyeAftershockAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val inputs = captureLidSource(this, instance, parameterNames())
        val context = EyeAftershockAlgorithm.context(inputs, tr("model.group.physics"),
            listOf(tr("model.physics.eyeBounce", "L"), tr("model.physics.eyeBounce", "R")))
        if (version != EyeAftershockV3Algorithm.VERSION) return context
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val carriers = buildJsonObject {
            for (target in instance.targets) {
                val layer = layersByMesh.getValue(target.id)
                putJsonObject(target.id) {
                    put("id", primaryByLayer.getValue(layer))
                    put("name", tr("model.deformer.meshShape", layers.getValue(layer).source.name))
                }
            }
        }
        return ModifierAlgorithmContext(JsonObject(context.values + ("carriers" to carriers)), context.resources)
    }
}
