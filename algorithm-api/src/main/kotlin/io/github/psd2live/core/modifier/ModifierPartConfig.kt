package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/** Shared interpretation of persisted part settings, including older UI spellings. */
object ModifierPartConfig {
    const val LID_COUPLING = "lidCoupling"

    /** The canonical key wins even when explicitly false. Reading never rewrites history. */
    fun enabled(config: JsonObject, key: String): Boolean =
        (config[key] ?: if (key == LID_COUPLING) config["lid"] else null)
            ?.jsonPrimitive?.booleanOrNull == true

    fun withFlag(config: JsonObject, key: String, enabled: Boolean): JsonObject =
        JsonObject((if (key == LID_COUPLING) config - "lid" else config) + (key to JsonPrimitive(enabled)))
}
