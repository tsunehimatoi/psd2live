package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigAuthoringJournal
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Captured automatic weights belong to the simulation; existing authored groups take precedence. */
internal object RigSimulationWeights {
    fun apply(model: PuppetModel, config: JsonObject): PuppetModel {
        var result = model
        for (raw in config["automatic_weights"]?.jsonArray.orEmpty()) {
            val command = raw.jsonObject
            val target = command.getValue("target").jsonPrimitive.content.removePrefix("mesh:")
            require(command["op"]?.jsonPrimitive?.content == "vertex_group_put" &&
                config.getValue("targets").jsonArray.any { it.jsonPrimitive.content == target }) {
                "Automatic simulation weights must address the captured simulation meshes"
            }
            val name = command.getValue("name").jsonPrimitive.content
            if (result.vertexGroups.any { it.drawableId.raw == target && it.name == name }) continue
            result = RigAuthoringJournal.apply(result, command)
        }
        return result
    }
}
