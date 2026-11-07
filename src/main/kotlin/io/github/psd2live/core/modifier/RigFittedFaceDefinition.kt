package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.FittedFaceV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.ScaffoldBranchesContext
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** The explicit source-only version starts on a shared neutral scaffold. */
internal object RigFittedFaceDefinition {
    private val spec get() = RigLegacyBindings.spec(FittedFaceV2Algorithm.TYPE)

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(spec, source)
        val algorithm = object : ChangingModifierAlgorithmPlugin by FittedFaceV2Algorithm, ModifierVersionSelection {
            override val canonicalOptInType = FittedFaceV2Algorithm.TYPE
        }
        return legacy.copy(version = FittedFaceV2Algorithm.VERSION,
            parameters = { angles().map { io.github.psd2live.format.model.Parameter(it.id.raw, it.name, it.min, it.max, it.default) } },
            evaluate = { _, _, _ -> error("Fitted face v2 requires captured scaffold inputs") },
            plugin = algorithm, upstreamInputs = true, changeWrites = FittedFaceV2Algorithm.WRITES,
            composableKinds = FittedFaceV2Algorithm.WRITES, resolveParameter = { _, _, _ -> null },
            authorContext = { requireNotNull(source).capture(it) })
    }

    private fun angles() = StandardParameters.all.filter { it.id in spec.parameters }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val provider = provider(instance)
        require(provider.version >= 2 && provider.targets.toSet() == instance.targets.toSet() &&
            activeBefore(instance).none { it.id != provider.id && it.targets.any(instance.targets::contains) }) {
            "Fitted face v2 requires neutral shared scaffold targets. Use v1 with preceding bindings."
        }
        val scaffold = scaffoldContext(provider)
        val frameInput = scaffold.values.getValue("frames").jsonObject
        val head = ScaffoldFramesContext.decode(frameInput).head
        val static = ScaffoldFramesContext.build(frameInput)
        val branches = ScaffoldBranchesContext.build(scaffold.values.getValue("branches").jsonObject, static)
        val corrections = WorkspaceSettingsCodec.decodeRecipe(provider.config["corrections"])
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area -> area.id to BaseChoice(area.legacy.id, area.legacy.version) },
            modules = RigModules.modules.associate { module ->
                val enabled = module == RigModules.FACE_DEPTH_TURN
                module.id to ModuleChoice(enabled, module.version, if (enabled) RigLegacyBindings.values(instance) else emptyMap())
            }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(generateDeformers = false, recipe = recipe,
            headTurnStrength = RigLegacyBindings.strength(instance), rigTuning = RigLegacyBindings.tuning(instance, RigTuning()),
            featureDisplacementEnabled = head.featureDisplacement)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.associate { target -> target.id to byLayer.getValue(layersByMesh.getValue(target.id)) }
        require(layers.values.any { it.semantic.tag == SemanticTag.FACE }) { "Fitted face v2 requires a face layer" }
        val parents = layers.mapValues { (_, layer) -> branches.parents.getValue(layer.source.id.raw).id }
        val frames = ScaffoldHead.build(head, { it }).frames + branches.parents.values.associate { it.id to it.frame }
        val face = measured.faceRig
        val base = LegacyFaceInputs.capture(ModifierAlgorithmContext(scaffold.values), face.centerY, face.radiusX, face.radiusY,
            face.eyeLineY, native.rigTuning.legacyFaceTuning(), native.headTurnStrength, angles().map { it.name }, tr("model.group.face"))
        val turn = RigDepthTurnSource.capture(measured, native, layers, parents, frames, scaffold.resources.getValue("source_meshes"),
            layers.mapValues { (_, layer) -> primaryByLayer.getValue(layer.source.id.raw) }).encode()
        return ModifierAlgorithmContext(buildJsonObject {
            put("base", base.values)
            put("turn", turn.values)
        }, turn.resources)
    }
}
