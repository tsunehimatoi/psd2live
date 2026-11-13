package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Standard parameter group keys shared between host UI localization and standalone plugins. */
object ModifierParameterGroups {
    const val EYES = "ParamGroupEyes"
    const val BROWS = "ParamGroupBrows"
    const val FACE = "ParamGroupFace"
    const val BODY = "ParamGroupBody"
    const val HAIR = "ParamGroupHair"
    const val MOUTH = "ParamGroupMouth"
    const val PHYSICS = "ParamGroupPhysics"
    const val EXPRESSIONS = "ParamGroupExpressions"
}

/** Optional host-supplied localization overrides for plugin-declared parameters and groups. */
@Serializable
data class ModifierParameterI18n(
    val groupNames: Map<String, String> = emptyMap(),
    val parameterNames: Map<String, String> = emptyMap(),
) {
    fun resolveName(id: String, defaultName: String): String =
        parameterNames[id]?.takeIf { it.isNotBlank() } ?: defaultName

    fun resolveGroup(groupKeyOrDefault: String): String =
        groupNames[groupKeyOrDefault]?.takeIf { it.isNotBlank() } ?: groupKeyOrDefault

    fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    companion object {
        const val CONTEXT_KEY = "parameter_i18n"
        val Empty = ModifierParameterI18n()

        fun fromContext(context: ModifierAlgorithmContext): ModifierParameterI18n =
            context.values[CONTEXT_KEY]?.let { Json.decodeFromJsonElement<ModifierParameterI18n>(it) } ?: Empty
    }
}
