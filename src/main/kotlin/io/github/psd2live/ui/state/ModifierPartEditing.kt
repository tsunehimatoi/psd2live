package io.github.psd2live.ui.state

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** UI adapters for one captured part; metadata and sibling feature configurations are never regenerated. */
internal object ModifierPartEditing {
    private val metadata = setOf("id", "version", "targets", "outputAliases")

    fun baseType(part: RigModifierInstance): String {
        val mode = part.config["mode"]?.jsonPrimitive?.contentOrNull ?: when (part.type) {
            "body" -> "legacy"
            "hair" -> "chains"
            else -> "fitted"
        }
        return "${part.type}/$mode"
    }

    fun child(part: RigModifierInstance, type: String): RigModifierInstance {
        val flag = ModifierPartConfig.features.getValue(part.type).entries.single { it.value == type }.key
        val config = part.config["subConfigs"]?.jsonObject?.get(type)?.jsonObject ?: JsonObject(emptyMap())
        return RigModifierInstance(
            id = config["id"]?.jsonPrimitive?.content ?: "${part.id}:$flag",
            type = type, version = config["version"]?.jsonPrimitive?.int ?: requireNotNull(RigLegacyBindings.find(type)).version,
            targets = config["targets"]?.let { Json.decodeFromJsonElement(ListSerializer(ModifierTarget.serializer()), it) } ?: part.targets,
            config = config, outputAliases = config["outputAliases"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: part.outputAliases,
        )
    }

    fun withChildConfig(part: RigModifierInstance, type: String, config: JsonObject): JsonObject {
        require(type in ModifierPartConfig.features.getValue(part.type).values)
        val children = part.config["subConfigs"]?.jsonObject.orEmpty()
        val original = children[type]?.jsonObject.orEmpty()
        val preserved = original.filterKeys { it in metadata }
        val child = JsonObject(config + preserved)
        return JsonObject(part.config + ("subConfigs" to JsonObject(children + (type to child))))
    }
}
