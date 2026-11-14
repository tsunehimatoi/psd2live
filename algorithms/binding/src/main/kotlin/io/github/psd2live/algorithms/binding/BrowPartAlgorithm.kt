package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.core.modifier.ModifierAlgorithmStages
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unified brow part plugin (`mode: fitted | legacy`) combining base brow fitting/binding and
 * optional brow sub-features (`shape`, `spring`, `blink`, `lidCoupling`) in one single pass.
 */
class BrowPartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val mode = (context.values["mode"]?.jsonPrimitive?.content
            ?: instance.config["mode"]?.jsonPrimitive?.content
            ?: "fitted")
        require(mode == "fitted" || mode == "legacy") { "Unsupported brow mode: $mode" }

        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseType = if (mode == "fitted") "brow/fitted" else "brow/legacy"
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["tuning"]?.let { put("tuning", it) }
            instance.config["values"]?.let { put("values", it) }
        }
        val baseInstance = RigModifierInstance(
            id = instance.id,
            type = baseType,
            version = 1,
            targets = instance.targets,
            config = baseConfig,
            outputAliases = instance.outputAliases,
            plugin = instance.plugin,
        )
        val baseValues = context.values["base_context"]?.jsonObject ?: context.values
        val upstream = context.upstream.toMutableList()
        val baseContext = ModifierAlgorithmContext(baseValues, context.resources.filterKeys { !it.startsWith("sub:") }, upstream)

        var current = ModifierAlgorithmStages.stage("part brow base: $baseType") {
            if (mode == "fitted") {
                BrowFittedAlgorithm().changes(input, baseInstance, baseContext, checkpoint)
            } else {
                val evalInput = ModifierEvaluationInput(input, baseContext)
                BrowLegacyAlgorithm().changes(evalInput, baseInstance, checkpoint)
            }
        }.apply(input, checkpoint)

        if (mode == "legacy") {
            require(ModifierPartConfig.features.getValue(TYPE).keys.none { ModifierPartConfig.enabled(instance.config, it) }) { "${instance.id}: sub-features require fitted mode" }
            return PartChangeComposer.between(input, current, checkpoint)
        }

        upstream += ModifierUpstreamCapability(
            capability = baseType,
            type = baseType,
            version = 1,
            targets = instance.targets,
            instanceId = instance.id,
            config = baseConfig,
        )

        val subContexts = context.values["sub_contexts"]?.jsonObject.orEmpty()
        val subConfigs = context.values["sub_configs"]?.jsonObject.orEmpty()

        for ((flagKey, subType, subPlugin) in subFeatures) {
            val enabled = ModifierPartConfig.enabled(instance.config, flagKey)
            if (!enabled) continue
            val subValues = requireNotNull(subContexts[subType]?.jsonObject) { "${instance.id}: missing enabled sub-context $subType" }
            val subInstance = ModifierPartConfig.child(instance.copy(type = TYPE), subType, 2, upstream, subConfigs[subType]?.jsonObject)
            val subTargets = subInstance.targets
            val subConfig = subInstance.config
            val prefix = "sub:$subType:"
            val subResources = context.resources
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
            val subContext = ModifierAlgorithmContext(subValues, subResources, upstream)
            val evalInput = ModifierEvaluationInput(current, subContext)
            current = ModifierAlgorithmStages.stage("part brow sub: $subType") {
                subPlugin.changes(evalInput, subInstance.copy(version = 2), checkpoint)
            }.apply(current, checkpoint)
            upstream += ModifierUpstreamCapability(
                capability = subType,
                type = subType,
                version = 2,
                targets = subTargets,
                instanceId = subInstance.id,
                config = subConfig,
            )
        }

        return PartChangeComposer.between(input, current, checkpoint)
    }

    private data class SubFeature(
        val configFlag: String,
        val type: String,
        val plugin: ReadingModifierAlgorithmPlugin,
    )

    companion object {
        const val TYPE = "brow"
        const val ENTRYPOINT = "part"
        const val VERSION = 1

        private val subFeatures: List<SubFeature> = listOf(
            SubFeature("shape", BrowShapeAlgorithm.TYPE, BrowShapeAlgorithm()),
            SubFeature("spring", BrowSpringAlgorithm.TYPE, BrowSpringAlgorithm()),
            SubFeature("blink", BrowBlinkAlgorithm.TYPE, BrowBlinkAlgorithm()),
            SubFeature("lidCoupling", BrowLidAlgorithm.TYPE, BrowLidAlgorithm()),
        )
    }
}
