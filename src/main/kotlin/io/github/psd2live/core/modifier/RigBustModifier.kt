package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BustBindingAlgorithm
import io.github.psd2live.algorithms.binding.BustBindingInputs
import io.github.psd2live.algorithms.binding.BustBindingV3Algorithm
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

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
            plugin = algorithm,
            changeWrites = BustBindingAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { capture(it).encode() },
        )
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val implementation = object : ReadingModifierAlgorithmPlugin by BustBindingV3Algorithm(), ModifierVersionSelection {
            override val canonicalOptInType = BustBindingAlgorithm.TYPE
        }
        return definition(source).copy(version = BustBindingV3Algorithm.VERSION, plugin = implementation,
            authorContext = { instance -> captureFitted(requireNotNull(source), instance) })
    }

    private fun captureFitted(source: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val measured = inputs(source, instance)
        val ordinary = measured.encode()
        if (!measured.applies || measured.targets.isEmpty()) return ordinary
        val scaffold = source.provider(instance)
        val targets = instance.targets.filter { it.id in measured.targets }
        val prefix = source.activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(targets::contains) &&
                RigLegacyBindings.find(it.type)?.let { spec -> !spec.expressionToggles && !spec.seeThrough } == true
        }
        require(prefix.all { it.targets.containsAll(targets) }) { "Chest binding requires shared source frames for its targets" }
        val (previous, before) = RigFittedMouthModuleSource.context(source, scaffold, prefix, null)
        val (desired, after) = RigFittedMouthModuleSource.context(source, scaffold, prefix + instance, null)
        fun layers(context: io.github.psd2live.core.RigBuilder.RigContext) = context.analysis.layers
            .associateBy { it.source.id.raw }.let { classified ->
                targets.associate { it.id to classified.getValue(source.layersByMesh.getValue(it.id)) }
            }
        val axes = prefix.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(ordinary.values, ordinary.resources + mapOf(
            "bustBefore" to source.outlineShellSource(before, previous, layers(before), axes),
            "bustAfter" to source.outlineShellSource(after, desired, layers(after), axes + RigLegacyBindings.spec(instance.type).parameters),
        ))
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
