package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.FittedFaceV2Algorithm
import io.github.psd2live.algorithms.binding.FittedFaceV3Algorithm
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

/** Explicit source bindings use complete shared scaffold targets. */
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

    fun bodyDefinition(source: RigModifierSource?): RigModifierDefinition {
        val algorithm = object : ChangingModifierAlgorithmPlugin by FittedFaceV3Algorithm,
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf(RigBodyModifier.TYPE)
            override val canonicalOptInType = FittedFaceV2Algorithm.TYPE
        }
        return definition(source).copy(version = FittedFaceV3Algorithm.VERSION, plugin = algorithm,
            evaluate = { _, _, _ -> error("Fitted face v3 requires captured scaffold and body inputs") },
            authorContext = { requireNotNull(source).capture(it, afterBody = true) })
    }

    private fun angles() = StandardParameters.all.filter { it.id in spec.parameters }

    private fun RigModifierSource.capture(instance: RigModifierInstance, afterBody: Boolean = false): ModifierAlgorithmContext {
        val provider = provider(instance)
        val prior = activeBefore(instance).filter { it.id != provider.id && it.targets.any(instance.targets::contains) }
        val targets = instance.targets.toSet()
        val body = if (afterBody) {
            val candidate = prior.singleOrNull()
            require(provider.version == 2 && provider.targets.toSet() == targets &&
                candidate != null && candidate.type == RigBodyModifier.TYPE && candidate.version in 1..2 &&
                candidate.targets.toSet() == targets && candidate.config["scaffold"]?.jsonPrimitive?.content == provider.id) {
                "Fitted face v3 requires one preceding legacy body on the same complete shared v2 scaffold"
            }
            candidate
        } else {
            require(provider.version >= 2 && provider.targets.toSet() == targets && prior.isEmpty()) {
                "Fitted face v2 requires neutral shared scaffold targets. Use v1 with preceding bindings."
            }
            null
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
            headTurnStrength = RigLegacyBindings.strength(instance), rigTuning = RigLegacyBindings.tuning(instance,
                body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning()),
            featureDisplacementEnabled = head.featureDisplacement)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.associate { target -> target.id to byLayer.getValue(layersByMesh.getValue(target.id)) }
        require(layers.values.any { it.semantic.tag == SemanticTag.FACE }) { "Fitted face v${instance.version} requires a face layer" }
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
