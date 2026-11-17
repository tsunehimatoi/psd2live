package io.github.psd2live.core

import kotlinx.serialization.json.*

/** Semantic choice carried by geometry operations, independent of the tool producing them. */
internal object GeometryEditIntent {
    const val FIELD = "correction_mode"
    fun mode(command: JsonObject, default: String = "delta"): String =
        (command[FIELD]?.jsonPrimitive?.content ?: default).also { require(it in setOf("delta", "absolute")) { "Unknown geometry correction mode: $it" } }
    fun withMode(command: JsonObject, mode: String): JsonObject {
        require(mode in setOf("delta", "absolute"))
        return when (command["op"]?.jsonPrimitive?.content) {
            "canvas_geometry", "set" -> JsonObject(command + (FIELD to JsonPrimitive(mode)))
            RigBezierJournal.OP -> (command["geometry"] as? JsonObject)?.let {
                JsonObject(command + ("geometry" to withMode(it, mode)))
            } ?: command
            else -> command
        }
    }
}
