package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowShapeAlgorithm
import io.github.psd2live.algorithms.binding.BrowShapeV3Algorithm
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

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye/fitted")
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, BrowShapeAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, BrowShapeV3Algorithm.VERSION, fittedAlgorithm)

    private fun definition(source: RigModifierSource?, version: Int,
                           algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, BrowShapeInputs>()
        fun measurements(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowShapeAlgorithm.TYPE), source).copy(
            version = version,
            parameters = { BrowShapeAlgorithm.parameters(measurements(it)) },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = setOf("mesh", "parameter", "hierarchy"),
            upstreamInputs = true,
            authorContext = { measurements(it).encode() },
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): BrowShapeInputs {
        val fitted = version == BrowShapeV3Algorithm.VERSION
        val browVersions = if (fitted) 1..3 else 1..2
        val eyeVersions = if (fitted) setOf(2, 5) else setOf(2)
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow shape v$version requires a shared v2 scaffold" }
        val upstream = activeBefore(instance).filter { previous ->
            previous.config["scaffold"] == JsonPrimitive(scaffold.id) && previous.targets.any(instance.targets::contains)
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(upstream.singleOrNull { it.id == baseId && it.type == "brow/fitted" && it.version in browVersions }) {
            "Brow shape v$version requires a compatible fitted brow provider"
        }
        require(upstream.none { previous ->
            previous.type in setOf(BrowShapeAlgorithm.TYPE, BrowShapeAlgorithm.ENTRYPOINT, BrowShapeV3Algorithm.ENTRYPOINT, "brow.spring", "brow.blink", "brow.lid-coupling")
        }) { "Brow shape v$version must precede other brow motion modifiers" }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
        require(eye == null || eye.version in eyeVersions) { "Brow shape v$version requires compatible fitted eyes" }
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
