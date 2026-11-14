package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

        var current = if (mode == "fitted") {
            BrowFittedAlgorithm().changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        } else {
            val evalInput = ModifierEvaluationInput(input, baseContext)
            BrowLegacyAlgorithm().changes(evalInput, baseInstance, checkpoint).apply(input, checkpoint)
        }

        if (mode == "legacy") {
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
            val enabled = ModifierPartConfig.enabled(instance.config, flagKey) || subType in subContexts
            if (!enabled) continue
            val subValues = subContexts[subType]?.jsonObject ?: continue
            val subCfgJson = subConfigs[subType]?.jsonObject ?: JsonObject(emptyMap())
            val subTargets = subCfgJson["targets"]?.jsonArray?.map {
                Json.decodeFromJsonElement(ModifierTarget.serializer(), it)
            } ?: instance.targets
            val subConfig = buildJsonObject {
                put("scaffold", scaffold)
                put("base", instance.id)
                subCfgJson["values"]?.let { put("values", it) }
                subCfgJson["tuning"]?.let { put("tuning", it) }
                subCfgJson["physics"]?.let { put("physics", it) }
                subCfgJson["physicsEnabled"]?.let { put("physicsEnabled", it) }
                val deps = subCfgJson["dependencies"]?.jsonObject ?: instance.config["dependencies"]?.jsonObject ?: JsonObject(emptyMap())
                val mergedDeps = buildJsonObject {
                    deps.forEach { (k, v) -> put(k, v) }
                    if (subType == BrowLidAlgorithm.TYPE && "eye/fitted" !in deps) {
                        upstream.lastOrNull { it.capability == "eye/fitted" }?.instanceId?.let { put("eye/fitted", it) }
                    }
                }
                if (mergedDeps.isNotEmpty()) put("dependencies", mergedDeps)
                subCfgJson["dip"]?.let { put("dip", it) }
            }
            val subInstance = RigModifierInstance(
                id = "${instance.id}:$subType",
                type = subType,
                version = 2,
                targets = subTargets,
                config = subConfig,
                outputAliases = instance.outputAliases,
                plugin = instance.plugin,
            )
            val prefix = "sub:$subType:"
            val subResources = context.resources
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
            val subContext = ModifierAlgorithmContext(subValues, subResources, upstream)
            val evalInput = ModifierEvaluationInput(current, subContext)
            current = subPlugin.changes(evalInput, subInstance, checkpoint).apply(current, checkpoint)
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
