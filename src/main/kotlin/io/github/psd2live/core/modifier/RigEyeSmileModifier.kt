package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeSmileAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

internal object RigEyeSmileModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeSmileAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeSmileAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeSmileAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_SMILE.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeSmileAlgorithm.TYPE), source).copy(
            version = EyeSmileAlgorithm.VERSION,
            parameters = { EyeSmileAlgorithm.parameters(parameterNames()) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Eye smile requires captured source inputs") },
            plugin = algorithm,
            changeWrites = EyeSmileAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { captureLidSource(requireNotNull(source), it, parameterNames()) },
        )
}

internal fun captureLidSource(
    source: RigModifierSource, instance: RigModifierInstance, parameterNames: Map<String, String>,
    fittedEyeId: String = instance.config.getValue("base").jsonPrimitive.content,
): ModifierAlgorithmContext = with(source) {
    val scaffold = provider(instance)
    val base = activeBefore(instance).single { it.id == fittedEyeId && it.type == "eye/fitted" }
    val options = scaffold.config
    val corrections = WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
    val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(base.type, base.version)),
        modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
        moves = corrections.moves, eyeParts = corrections.eyeParts)
    val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()),
        headTurnStrength = 1f, bodyStrength = 1f,
        featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
        hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
        hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true))
    RigBuilder.eyeSmileSourceInputs(analysis, atlas, native, meshCache,
        instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, parameterNames) { mesh, parent ->
        ScaffoldBinding.deformerId(scaffold, mesh, parent)
    }
}
