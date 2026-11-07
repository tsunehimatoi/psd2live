package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowShapeAlgorithm
import io.github.psd2live.algorithms.binding.BrowShapeInputs
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

internal object RigBrowShapeModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye/fitted")
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, BrowShapeInputs>()
        fun measurements(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowShapeAlgorithm.TYPE), source).copy(
            version = BrowShapeAlgorithm.VERSION,
            parameters = { BrowShapeAlgorithm.parameters(measurements(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Brow shape requires resolved modifier inputs") },
            plugin = algorithm,
            changeWrites = setOf("mesh", "parameter", "hierarchy"),
            upstreamInputs = true,
            authorContext = { measurements(it).encode() },
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): BrowShapeInputs {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow shape v2 requires a shared v2 scaffold" }
        val upstream = activeBefore(instance).filter { previous ->
            previous.config["scaffold"] == JsonPrimitive(scaffold.id) && previous.targets.any(instance.targets::contains)
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(upstream.singleOrNull { it.id == baseId && it.type == "brow/fitted" && it.version in 1..2 }) {
            "Brow shape v2 requires a compatible fitted brow provider"
        }
        require(upstream.none { previous ->
            previous.type in setOf(BrowShapeAlgorithm.TYPE, BrowShapeAlgorithm.ENTRYPOINT, "brow.spring", "brow.blink", "brow.lid-coupling")
        }) { "Brow shape v2 must precede other brow motion modifiers" }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
        require(eye == null || eye.version == 2) { "Brow shape v2 requires compatible fitted eyes" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val bases = RigModules.areas.associate { area ->
            val chosen = when (area.id) {
                "eye" -> eye
                "brow" -> base
                else -> null
            }
            area.id to if (chosen == null) BaseChoice(area.legacy.id, area.legacy.version)
            else BaseChoice(chosen.type, chosen.version, RigLegacyBindings.values(chosen))
        }
        val recipe = RigRecipe(true, bases = bases, modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        return RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, eye?.id) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
    }
}
