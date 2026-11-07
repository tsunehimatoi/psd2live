package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BustBindingAlgorithm
import io.github.psd2live.algorithms.binding.BustBindingInputs
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

internal object RigBustModifier {
    private val algorithm = BustBindingAlgorithm()

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val spec = RigLegacyBindings.spec(BustBindingAlgorithm.TYPE)
        val legacy = RigLegacyBindings.definition(spec, source)
        val cache = HashMap<RigModifierInstance, BustBindingInputs>()
        fun capture(instance: RigModifierInstance) = cache.getOrPut(instance) { inputs(requireNotNull(source), instance) }
        return legacy.copy(
            version = BustBindingAlgorithm.VERSION,
            parameters = { BustBindingAlgorithm.parameters(capture(it), it) },
            validate = { rig, instance ->
                legacy.validate(rig, instance)
                val captured = capture(instance)
                RigBustBindingSource.validatePhysics(rig, instance, spec.parameters.map { it.raw }.takeIf { captured.applies }.orEmpty())
            },
            composableKinds = legacy.composableKinds + "physics",
            evaluate = { _, _, _ -> error("Chest bounce requires resolved modifier inputs") },
            plugin = algorithm,
            changeWrites = BustBindingAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { capture(it).encode() },
        )
    }

    fun inputs(source: RigModifierSource, instance: RigModifierInstance): BustBindingInputs {
        val scaffold = source.provider(instance)
        val options = scaffold.config
        val corrections = WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
        val config = source.sourceConfig().copy(
            bodyStrength = 1f, headTurnStrength = 1f, rigTuning = RigTuning(),
            recipe = RigRecipe.Legacy.copy(moves = corrections.moves, eyeParts = corrections.eyeParts),
            featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val earlier = source.activeBefore(instance).filter {
            it.type == BustBindingAlgorithm.TYPE && it.config["scaffold"] == instance.config["scaffold"]
        }
        val captured = RigBustBindingSource.capture(source.analysis, source.atlas, config, source.meshCache,
            source.primaryByLayer, source.layersByMesh, instance)
        return captured.copy(previousBounce = captured.targets.keys.mapNotNull { mesh ->
            earlier.lastOrNull { ModifierTarget("mesh", mesh) in it.targets }?.let {
                mesh to (RigLegacyBindings.values(it)["bounce"] ?: 100f)
            }
        }.toMap())
    }
}
