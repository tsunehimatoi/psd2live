package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Captured automatic weights belong to the simulation; existing authored groups take precedence. */
object RigSimulationWeights {
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
            require(command.getValue("target").jsonPrimitive.content.startsWith("mesh:")) { "Vertex groups belong to an ArtMesh" }
            val kindName = command.getValue("kind").jsonPrimitive.content
            if (kindName.lowercase() in VertexGroupKind.RETIRED) continue
            val mesh = requireNotNull(result.drawables.singleOrNull { it.id.raw == target }?.mesh) { "Mesh not found: $target" }
            val weights = command.getValue("weights").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            require(weights.size == mesh.vertexCount) { "Vertex group has ${weights.size} weights for ${mesh.vertexCount} vertices" }
            val group = VertexGroup(name, DrawableId(target), VertexGroupKind.parse(kindName), weights)
            result = result.copy(vertexGroups = result.vertexGroups + group)
        }
        return result
    }
}
