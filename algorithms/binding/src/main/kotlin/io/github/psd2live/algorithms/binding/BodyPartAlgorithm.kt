package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
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
 * Unified body part plugin combining `body/legacy` torso motion with optional `bust` and `arms`
 * physics/swing bindings in one pass.
 */
class BodyPartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["strength"]?.let { put("strength", it) }
            instance.config["tuning"]?.let { put("tuning", it) }
        }
        val baseInstance = RigModifierInstance(
            id = instance.id,
            type = "body/legacy",
            version = 1,
            targets = instance.targets,
            config = baseConfig,
            outputAliases = instance.outputAliases,
            plugin = instance.plugin,
        )
        val baseValues = context.values["base_context"]?.jsonObject ?: context.values
        val upstream = context.upstream.toMutableList()
        val baseContext = ModifierAlgorithmContext(
            baseValues,
            context.resources.filterKeys { !it.startsWith("sub:") },
            upstream,
        )

        var current = LegacyBodyAlgorithm().changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        upstream += ModifierUpstreamCapability(
            capability = "body/legacy",
            type = "body/legacy",
            version = 1,
            targets = instance.targets,
            instanceId = instance.id,
            config = baseConfig,
        )

        val subContexts = context.values["sub_contexts"]?.jsonObject.orEmpty()
        val subConfigs = context.values["sub_configs"]?.jsonObject.orEmpty()

        for ((flagKey, subType, subVersion) in listOf(
            Triple("bust", BustBindingAlgorithm.TYPE, BustBindingAlgorithm.VERSION),
            Triple("arms", ArmSwingAlgorithm.TYPE, ArmSwingAlgorithm.VERSION),
        )) {
            val enabled = instance.config[flagKey]?.jsonPrimitive?.booleanOrNull == true || subType in subContexts
            if (!enabled) continue
            val subValues = subContexts[subType]?.jsonObject ?: continue
            val subCfg = subConfigs[subType]?.jsonObject ?: JsonObject(emptyMap())
            val subTargets = subCfg["targets"]?.jsonArray?.map {
                Json.decodeFromJsonElement(ModifierTarget.serializer(), it)
            } ?: instance.targets
            val subConfig = buildJsonObject {
                put("scaffold", scaffold)
                val deps = buildJsonObject {
                    (subCfg["dependencies"] ?: instance.config["dependencies"])?.jsonObject?.forEach { (k, v) -> put(k, v) }
                    if ("body/legacy" !in (subCfg["dependencies"]?.jsonObject ?: instance.config["dependencies"]?.jsonObject.orEmpty())) {
                        put("body/legacy", instance.id)
                    }
                    if ("face/legacy" !in (subCfg["dependencies"]?.jsonObject ?: instance.config["dependencies"]?.jsonObject.orEmpty())) {
                        upstream.lastOrNull { it.capability == "face/legacy" }?.let { put("face/legacy", it.instanceId) }
                    }
                }
                put("dependencies", deps)
                subCfg["values"]?.let { put("values", it) }
                subCfg["physics"]?.let { put("physics", it) }
                subCfg["physicsEnabled"]?.let { put("physicsEnabled", it) }
            }
            val subInstance = RigModifierInstance(
                id = "${instance.id}:$subType",
                type = subType,
                version = subVersion,
                targets = subTargets,
                config = subConfig,
            )
            val prefix = "sub:$subType:"
            val subResources = context.resources.entries
                .filter { (k, _) -> k.startsWith(prefix) }
                .associate { (k, v) -> k.removePrefix(prefix) to v }
            val evalInput = ModifierEvaluationInput(current, ModifierAlgorithmContext(subValues, subResources, upstream))
            val subChanges = if (subType == BustBindingAlgorithm.TYPE) {
                BustBindingAlgorithm().changes(evalInput, subInstance, checkpoint)
            } else {
                ArmSwingAlgorithm().changes(evalInput, subInstance, checkpoint)
            }
            current = subChanges.apply(current, checkpoint)
        }

        return PartChangeComposer.between(input, current, checkpoint)
    }

    companion object {
        const val TYPE = "body"
        const val ENTRYPOINT = "part"
        const val VERSION = 1
    }
}
