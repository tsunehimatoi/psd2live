package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowLidAlgorithm
import io.github.psd2live.algorithms.binding.BrowLidV3Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV4Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigBrowLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowLidAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV3Algorithm.TYPE
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
        changeWrites = BrowLidV3Algorithm.WRITES,
    )

    private val fittedEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV4Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV4Algorithm.TYPE
    }

    fun fittedEyeDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV4Algorithm.VERSION,
        plugin = fittedEyeAlgorithm,
        changeWrites = BrowLidV4Algorithm.WRITES,
    )

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(BrowLidAlgorithm.TYPE), source).copy(
            version = BrowLidAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = BrowLidAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val eyeId = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val eye = activeBefore(instance).single { it.id == eyeId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val targets = instance.targets.associate { it.id to layersByMesh.getValue(it.id) }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val measurements = RigBuilder.eyeWideSourceInputs(analysis, atlas, native, meshCache, targets) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        val context = RigBuilder.rigContext(analysis, native, meshCache, neutralScaffold = true)
        return ModifierAlgorithmContext(JsonObject(measurements.values + mapOf(
            "brow_cover" to JsonPrimitive(context.browCover),
            "brow_meshes" to JsonArray(targets.filterValues { layer ->
                layers.getValue(layer).semantic.let { it.tag == SemanticTag.EYEBROW && it.role == null }
            }.keys.map(::JsonPrimitive)),
            "carrier_names" to JsonObject(targets.mapValues { (_, layer) ->
                JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layer).source.name))
            }),
            "native_ids" to JsonObject(targets.mapValues { (_, layer) -> JsonPrimitive(primaryByLayer.getValue(layer)) }),
        )), measurements.resources)
    }
}
