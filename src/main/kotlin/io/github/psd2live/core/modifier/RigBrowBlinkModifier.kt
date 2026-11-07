package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowBlinkAlgorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV3Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigBrowBlinkModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkV3Algorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkV3Algorithm.TYPE
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowBlinkV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
    )

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(BrowBlinkAlgorithm.TYPE), source).copy(
            version = BrowBlinkAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Brow blink requires captured source inputs") },
            plugin = algorithm,
            changeWrites = BrowBlinkAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow blink v${instance.version} requires a shared v2 scaffold" }
        val upstream = activeBefore(instance).filter { previous ->
            previous.config["scaffold"] == JsonPrimitive(scaffold.id) && previous.targets.any(instance.targets::contains)
        }
        val base = upstream.single { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "brow/fitted" }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
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
        val measurements = RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, eye?.id) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        require(eye == null || measurements.targets.keys.all { ModifierTarget("mesh", it) in eye.targets }) {
            "Brow blink v${instance.version} requires the same fitted eye provider for all fitted brow targets"
        }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val names = JsonObject(measurements.targets.keys.associateWith { id ->
            JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layersByMesh.getValue(id)).source.name))
        })
        val encoded = measurements.encode()
        return ModifierAlgorithmContext(JsonObject(encoded.values + ("carrier_names" to names)), encoded.resources)
    }
}
