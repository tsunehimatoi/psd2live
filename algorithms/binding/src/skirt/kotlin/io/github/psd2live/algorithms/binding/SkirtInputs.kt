package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Measurements of the lower garment and its original, unbound topology. */
data class SkirtInputs(val measurements: Measurements, val raw: ModifierAlgorithmContext) {
    @Serializable
    data class Measurements(
        val body: List<Float>,
        val layers: List<Layer>,
        val ribbonTargets: Set<String>,
        val impulses: List<String>,
        val folder: String,
        val swayLabel: String,
        val flareLabel: String,
    )

    @Serializable
    data class Layer(
        val id: String, val tag: String, val bounds: List<Float>, val pixels: Int,
        val kind: String, val meshes: List<String>, val paired: Boolean = false,
        val warpName: String = "", val parameterName: String = "",
    )

    fun context() = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "skirt/1")
        put("measurements", Json.encodeToJsonElement(Measurements.serializer(), measurements))
    }, raw.resources)

    companion object {
        fun bounds(values: List<Float>): Bounds {
            require(values.size == 4 && values.all(Float::isFinite) && values[0] < values[2] && values[1] < values[3])
            return Bounds(values[0], values[1], values[2], values[3])
        }

        fun decode(context: ModifierAlgorithmContext): Measurements {
            require(context.values.keys == setOf("schema", "measurements") && context.values["schema"] == JsonPrimitive("skirt/1") &&
                context.resources.keys.all { it == "source_meshes" }) { "Invalid skirt input" }
            val data = Json.decodeFromJsonElement(Measurements.serializer(), context.values.getValue("measurements"))
            bounds(data.body)
            require(data.layers.size <= 20_000 && data.layers.map { it.id }.toSet().size == data.layers.size)
            require(data.layers.all { it.kind in setOf("SKIRT", "RIBBON", "PENDANT") && it.pixels > 0 })
            return data
        }
    }
}
