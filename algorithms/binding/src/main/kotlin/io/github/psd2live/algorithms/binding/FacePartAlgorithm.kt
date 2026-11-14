package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unified face part plugin (`mode: fitted | legacy`) combining base face binding with optional
 * `depthTurn` and `neck` sub-features in one single pass.
 */
class FacePartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val mode = context.values["mode"]?.jsonPrimitive?.content
            ?: instance.config["mode"]?.jsonPrimitive?.content
            ?: "fitted"
        require(mode == "fitted" || mode == "legacy") { "Unsupported face mode: $mode" }
        require(mode == "legacy" || !ModifierPartConfig.enabled(instance.config, "depthTurn")) { "${instance.id}: depthTurn requires legacy mode" }

        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseType = if (mode == "fitted") "face/fitted" else "face/legacy"
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["strength"]?.let { put("strength", it) }
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
        val baseContext = ModifierAlgorithmContext(baseValues, context.resources, upstream)

        var current = if (mode == "fitted") {
            FittedFaceBindingAlgorithm.changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        } else {
            LegacyFaceAlgorithm().changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        }

        upstream += ModifierUpstreamCapability(
            capability = baseType,
            type = baseType,
            version = 1,
            targets = instance.targets,
            instanceId = instance.id,
            config = baseConfig,
        )
        if (mode == "fitted") {
            upstream += ModifierUpstreamCapability(
                capability = "face/legacy",
                type = "face/legacy",
                version = 1,
                targets = instance.targets,
                instanceId = instance.id,
                config = baseConfig,
            )
        }

        val subContexts = context.values["sub_contexts"]?.jsonObject.orEmpty()
        val subConfigs = context.values["sub_configs"]?.jsonObject.orEmpty()

        if (mode == "legacy" && ModifierPartConfig.enabled(instance.config, "depthTurn")) {
            requireNotNull(subContexts["face.depth-turn"]?.jsonObject) { "${instance.id}: missing enabled sub-context face.depth-turn" }.let { subValues ->
                val subInstance = ModifierPartConfig.child(instance.copy(type = TYPE), "face.depth-turn", 1, upstream, subConfigs["face.depth-turn"]?.jsonObject)
                val prefix = "sub:face.depth-turn:"
                val subResources = context.resources.entries.associate { (k, v) ->
                    (if (k.startsWith(prefix)) k.removePrefix(prefix) else k) to v
                }
                val subContext = ModifierAlgorithmContext(subValues, subResources, upstream)
                current = DepthTurnAlgorithm().changes(current, subInstance.copy(version = 1), subContext, checkpoint).apply(current, checkpoint)
            }
        }

        if (ModifierPartConfig.enabled(instance.config, "neck")) {
            requireNotNull(subContexts["face.neck"]?.jsonObject) { "${instance.id}: missing enabled sub-context face.neck" }.let { subValues ->
                val subInstance = ModifierPartConfig.child(instance.copy(type = TYPE), "face.neck", NeckAlgorithm.VERSION, upstream, subConfigs["face.neck"]?.jsonObject)
                val prefix = "sub:face.neck:"
                val subResources = context.resources
                    .filterKeys { it.startsWith(prefix) }
                    .mapKeys { (k, _) -> k.removePrefix(prefix) }
                val subContext = ModifierAlgorithmContext(subValues, subResources, upstream)
                current = NeckAlgorithm().changes(ModifierEvaluationInput(current, subContext), subInstance.copy(version = NeckAlgorithm.VERSION), checkpoint).apply(current, checkpoint)
            }
        }

        return PartChangeComposer.between(input, current, checkpoint)
    }

    companion object {
        const val TYPE = "face"
        const val ENTRYPOINT = "part"
        const val VERSION = 1
    }
}
