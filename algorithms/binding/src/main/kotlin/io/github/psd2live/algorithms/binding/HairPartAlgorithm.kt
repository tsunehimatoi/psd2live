package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unified hair part plugin (`mode: chains | legacy`) combining `hair/chains` and `hair/legacy`.
 */
class HairPartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val mode = context.values["mode"]?.jsonPrimitive?.content
            ?: instance.config["mode"]?.jsonPrimitive?.content
            ?: "chains"
        require(mode == "chains" || mode == "legacy") { "Unsupported hair mode: $mode" }

        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseType = if (mode == "chains") "hair/chains" else "hair/legacy"
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["dependencies"]?.let { put("dependencies", it) }
            instance.config["tuning"]?.let { put("tuning", it) }
            instance.config["values"]?.let { put("values", it) }
            instance.config["physics"]?.let { put("physics", it) }
            instance.config["physicsEnabled"]?.let { put("physicsEnabled", it) }
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
        val baseContext = ModifierAlgorithmContext(baseValues, context.resources, context.upstream)
        return if (mode == "chains") {
            HairChainsAlgorithm().changes(input, baseInstance, baseContext, checkpoint)
        } else {
            HairLegacyAlgorithm().changes(input, baseInstance, baseContext, checkpoint)
        }
    }

    companion object {
        const val TYPE = "hair"
        const val ENTRYPOINT = "part"
        const val VERSION = 1
    }
}
