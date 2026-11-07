package io.github.psd2live.core.modifier

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec

/** Use the same source frame as the preceding bases that bind these clothing targets. */
internal object RigClothingSourceFrame {
    fun config(source: RigModifierSource, instance: RigModifierInstance, targets: List<ModifierTarget>,
               previous: List<RigModifierInstance>): PipelineConfig {
        val scaffold = source.provider(instance)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        fun measurementProviders(target: ModifierTarget) = previous.filter { preceding ->
            target in preceding.targets && (RigLegacyBindings.find(preceding.type)?.isBase == true || preceding.type == "mouth.outline")
        }.associateBy { it.type }
        val providers = targets.firstOrNull()?.let(::measurementProviders).orEmpty()
        require(targets.all { measurementProviders(it) == providers }) { "Clothing targets use different source frames" }
        val bases = providers.values.filter { it.type != RigLegacyBindings.FITTED_FACE && RigLegacyBindings.find(it.type)?.isBase == true }
            .associate { RigLegacyBindings.spec(it.type).area to BaseChoice(it.type, it.version, RigLegacyBindings.values(it)) }
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            area.id to (bases[area.id] ?: BaseChoice(area.legacy.id, area.legacy.version))
        }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { tuning, spec ->
            providers[spec.type]?.let { RigLegacyBindings.tuning(it, tuning) } ?: tuning
        }
        return source.sourceConfig().copy(generateDeformers = false, mouthOutlineEnabled = "mouth.outline" in providers,
            bodyStrength = providers["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f, rigTuning = tuning, recipe = recipe)
    }
}
