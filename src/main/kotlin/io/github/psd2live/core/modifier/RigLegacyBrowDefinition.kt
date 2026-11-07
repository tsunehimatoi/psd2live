package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowLegacyAlgorithm
import io.github.psd2live.algorithms.binding.BrowLegacyInputs
import io.github.psd2live.algorithms.binding.BrowLegacyV2Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

internal object RigLegacyBrowDefinition {
    private const val TYPE = BrowLegacyV2Algorithm.TYPE
    private val plugin = object : ReadingModifierAlgorithmPlugin by BrowLegacyV2Algorithm,
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val canonicalOptInType = TYPE
        override val optionalUpstream = BrowLegacyV2Algorithm.HEAD_TURN
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source)
        return legacy.copy(version = BrowLegacyV2Algorithm.VERSION, plugin = plugin,
            parameters = { BrowLegacyAlgorithm.parameters(inputs(emptyMap())) },
            resolveParameter = { _, _, _ -> null },
            validate = { rig, instance ->
                legacy.validate(rig, instance)
                BrowLegacyV2Algorithm.validateConfig(instance)
            },
            upstreamInputs = true, changeWrites = BrowLegacyV2Algorithm.WRITES,
            evaluate = { _, _, _ -> error("Legacy brow v2 requires captured source measurements") },
            authorContext = { requireNotNull(source).capture(it) })
    }

    private fun inputs(targets: Map<String, BrowLegacyInputs.Brow>) = BrowLegacyInputs(
        targets, RigTuning().browLift, tr("model.parameter.browLY"), tr("model.parameter.browRY"), tr("model.group.brows"))

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        BrowLegacyV2Algorithm.requireScaffold(scaffold, instance)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val config = sourceConfig().copy(generateDeformers = false,
            recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val measured = RigBuilder.rigContext(analysis, config, meshCache)
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val source = RigBuilder.scaffoldSourceInputs(analysis, config, meshCache)
        val parents = ScaffoldBranchesContext.build(source.branches, ScaffoldFramesContext.build(source.frames)).parents
        return inputs(instance.targets.mapNotNull { target ->
            val layerId = layersByMesh.getValue(target.id)
            val layer = byLayer.getValue(layerId)
            if (layer.semantic.tag != SemanticTag.EYEBROW || layer.opaquePixels == 0) return@mapNotNull null
            val parent = parents.getValue(layerId)
            target.id to BrowLegacyInputs.Brow(layer.semantic.side, measured.rigLayer(layer).bounds.height,
                parent.frame.height, ScaffoldBinding.deformerId(scaffold, target.id, parent.id.raw))
        }.toMap()).encode()
    }
}
