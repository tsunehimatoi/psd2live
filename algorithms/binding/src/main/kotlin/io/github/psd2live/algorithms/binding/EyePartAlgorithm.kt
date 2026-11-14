package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierEvaluationInput
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
 * Unified eye part plugin (`mode: fitted | legacy`) combining base eye fitting/binding and
 * optional eye sub-features (`wide`, `smile`, `emotion`, `gazeLid`, `highlight`, `lidFade`,
 * `squash`, `aftershock`) in one single pass.
 */
class EyePartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val mode = (context.values["mode"]?.jsonPrimitive?.content
            ?: instance.config["mode"]?.jsonPrimitive?.content
            ?: "fitted")
        require(mode == "fitted" || mode == "legacy") { "Unsupported eye mode: $mode" }

        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseType = if (mode == "fitted") EyeFittedSourceAlgorithm.TYPE else EyeLegacySourceAlgorithm.TYPE
        val baseVersion = if (mode == "fitted") EyeFittedAlgorithm.VERSION else EyeLegacySourceAlgorithm.VERSION
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["tuning"]?.let { put("tuning", it) }
            instance.config["values"]?.let { put("values", it) }
        }
        val baseInstance = RigModifierInstance(
            id = instance.id,
            type = baseType,
            version = baseVersion,
            targets = instance.targets,
            config = baseConfig,
            outputAliases = instance.outputAliases,
            plugin = instance.plugin,
        )
        val baseValues = context.values["base_context"]?.jsonObject ?: context.values
        val upstream = context.upstream.toMutableList()
        val baseContext = ModifierAlgorithmContext(baseValues, context.resources, upstream)

        var current = if (mode == "fitted") {
            EyeFittedSourceAlgorithm().changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        } else {
            val evalInput = ModifierEvaluationInput(input, baseContext)
            EyeLegacySourceAlgorithm().changes(evalInput, baseInstance, checkpoint).apply(input, checkpoint)
        }

        if (mode == "legacy") {
            return PartChangeComposer.between(input, current, checkpoint)
        }

        upstream += ModifierUpstreamCapability(
            capability = baseType,
            type = baseType,
            version = baseVersion,
            targets = instance.targets,
            instanceId = instance.id,
            config = baseConfig,
        )

        val subContexts = context.values["sub_contexts"]?.jsonObject.orEmpty()
        val subConfigs = context.values["sub_configs"]?.jsonObject.orEmpty()

        for ((flagKey, subType, subPlugin) in subFeatures) {
            val enabled = instance.config[flagKey]?.jsonPrimitive?.booleanOrNull == true || subType in subContexts
            if (!enabled) continue
            val subValues = subContexts[subType]?.jsonObject ?: continue
            val subCfgJson = subConfigs[subType]?.jsonObject ?: JsonObject(emptyMap())
            val subTargets = subCfgJson["targets"]?.jsonArray?.map {
                Json.decodeFromJsonElement(ModifierTarget.serializer(), it)
            } ?: instance.targets
            val subConfig = buildJsonObject {
                put("scaffold", scaffold)
                put("base", instance.id)
                (subCfgJson["dependencies"] ?: instance.config["dependencies"])?.let { put("dependencies", it) }
                subCfgJson["values"]?.let { put("values", it) }
                subCfgJson["tuning"]?.let { put("tuning", it) }
                subCfgJson["physics"]?.let { put("physics", it) }
                subCfgJson["physicsEnabled"]?.let { put("physicsEnabled", it) }
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
            val subPrefix = "sub:$subType:"
            val subResources = context.resources + context.resources
                .filterKeys { it.startsWith(subPrefix) }
                .mapKeys { it.key.removePrefix(subPrefix) }
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
        const val TYPE = "eye"
        const val ENTRYPOINT = "part"
        const val VERSION = 1

        private val subFeatures: List<SubFeature> = listOf(
            SubFeature("wide", EyeWideAlgorithm.TYPE, EyeWideAlgorithm()),
            SubFeature("smile", EyeSmileAlgorithm.TYPE, EyeSmileAlgorithm()),
            SubFeature("emotion", EyeEmotionAlgorithm.TYPE, EyeEmotionAlgorithm()),
            SubFeature("gazeLid", EyeGazeLidAlgorithm.TYPE, EyeGazeLidAlgorithm()),
            SubFeature("highlight", EyeHighlightAlgorithm.TYPE, EyeHighlightAlgorithm()),
            SubFeature("lidFade", LidFadeAlgorithm.TYPE, LidFadeAlgorithm()),
            SubFeature("squash", EyeSquashAlgorithm.TYPE, EyeSquashAlgorithm()),
            SubFeature("aftershock", EyeAftershockAlgorithm.TYPE, EyeAftershockAlgorithm()),
        )
    }
}
