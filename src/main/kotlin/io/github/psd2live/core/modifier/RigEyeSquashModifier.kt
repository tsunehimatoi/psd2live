package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeSquashAlgorithm
import io.github.psd2live.algorithms.binding.EyeSquashV3Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.jsonPrimitive

internal object RigEyeSquashModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeSquashAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye.wide", EyeSquashAlgorithm.TYPE, EyeSquashAlgorithm.ENTRYPOINT) + EyeSquashAlgorithm.HEAD_TURN
        override val canonicalOptInType = EyeSquashAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeSquashV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye.wide", EyeSquashAlgorithm.TYPE, EyeSquashAlgorithm.ENTRYPOINT, EyeSquashV3Algorithm.ENTRYPOINT) + EyeSquashAlgorithm.HEAD_TURN
        override val canonicalOptInType = EyeSquashAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeSquashAlgorithm.TYPE), source).copy(
            version = if (modern) EyeSquashV3Algorithm.VERSION else EyeSquashAlgorithm.VERSION,
            parameters = { emptyList() },
            evaluate = { _, _, _ -> error("Eye squash requires resolved modifier inputs") },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) EyeSquashV3Algorithm.WRITES else setOf("mesh"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it, modern) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, modern: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = activeBefore(instance).single { it.id == baseId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(base.type, base.version, RigLegacyBindings.values(base))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()))
        val captured = RigBuilder.eyeSquashSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }) { target, parent ->
            io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(scaffold, target, parent)
        }
        return if (modern) captureLidHeadSource(instance, scaffold, base, native, captured) else captured
    }
}
