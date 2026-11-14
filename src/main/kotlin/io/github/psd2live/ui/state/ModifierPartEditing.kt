package io.github.psd2live.ui.state

import io.github.psd2live.core.modifier.*
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

    fun child(part: RigModifierInstance, type: String): RigModifierInstance = ModifierPartConfig.child(part, type)

    fun hasOwnTargets(part: RigModifierInstance, type: String): Boolean =
        part.config["subConfigs"]?.jsonObject?.get(type)?.jsonObject?.containsKey("targets") == true

    fun withChildConfig(part: RigModifierInstance, type: String, config: JsonObject): JsonObject {
        require(type in ModifierPartConfig.features.getValue(part.type).values)
        val children = part.config["subConfigs"]?.jsonObject.orEmpty()
        val original = children[type]?.jsonObject.orEmpty()
        val preserved = original.filterKeys { it in metadata }
        val effective = child(part, type).config
        // Removing the effective outline color means automatic sampling, not falling back to the legacy parent color.
        val parentConfig = if (type == "mouth.outline" && "color" in effective && "color" !in config)
            JsonObject(part.config - "color") else part.config
        // Unchanged inherited values stay inherited, including dependencies merged by the shared helper.
        val authored = config.mapNotNull { (key, value) ->
            val next = if (key in metadata || value != effective[key]) value else original[key]
            next?.let { key to it }
        }.toMap()
        val child = JsonObject(authored + preserved)
        if (child.isEmpty() && type !in children) return parentConfig
        return JsonObject(parentConfig + ("subConfigs" to JsonObject(children + (type to child))))
    }
}
