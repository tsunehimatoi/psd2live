package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.LidFadeAlgorithm
import io.github.psd2live.algorithms.binding.LidFadeV3Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.jsonPrimitive

internal object RigLidFadeModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by LidFadeAlgorithm(), ModifierOptionalUpstream {
        override val optionalUpstream = setOf("eye.wide", LidFadeAlgorithm.TYPE, LidFadeAlgorithm.ENTRYPOINT)
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by LidFadeV3Algorithm(), ModifierOptionalUpstream {
        override val optionalUpstream = algorithm.optionalUpstream + LidFadeV3Algorithm.ENTRYPOINT
    }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(LidFadeAlgorithm.TYPE), source).copy(
            version = if (modern) LidFadeV3Algorithm.VERSION else LidFadeAlgorithm.VERSION,
            parameters = { emptyList() },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = setOf("mesh"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = activeBefore(instance).single { it.id == baseId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(base.type, base.version, RigLegacyBindings.values(base))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()), headTurnStrength = 1f)
        return RigBuilder.lidFadeSourceInputs(analysis, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) })
    }
}
