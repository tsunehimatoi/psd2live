package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Source chain order, attachment frames and original topology for ribbons and pendants. */
data class RibbonsInputs(val measurements: Measurements, val raw: ModifierAlgorithmContext) {
    @Serializable
    data class Measurements(
        val layers: List<Layer>,
        val skirtTargets: Set<String>,
        val impulses: List<String>,
        val folder: String,
        val swayLabel: String,
        val bounceLabel: String,
    )

    @Serializable
    data class Layer(
        val id: String, val tag: String, val bounds: List<Float>, val pixels: Int,
        val kind: ChainKind, val meshes: List<String> = emptyList(), val attachment: Attachment? = null,
        val warpName: String = "", val parameterName: String = "",
    )

    @Serializable
    data class Attachment(val deformer: String, val part: String, val frame: List<Float>, val accessoryName: String? = null)

    fun context() = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "ribbons/1")
        put("measurements", Json.encodeToJsonElement(Measurements.serializer(), measurements))
    }, raw.resources)

    companion object {
        fun bounds(values: List<Float>): Bounds {
            require(values.size == 4 && values.all(Float::isFinite) && values[0] < values[2] && values[1] < values[3])
            return Bounds(values[0], values[1], values[2], values[3])
        }

        fun decode(context: ModifierAlgorithmContext): Measurements {
            require(context.values.keys == setOf("schema", "measurements") && context.values["schema"] == JsonPrimitive("ribbons/1") &&
                context.resources.keys.all { it == "source_meshes" }) { "Invalid ribbons input" }
            val data = Json.decodeFromJsonElement(Measurements.serializer(), context.values.getValue("measurements"))
            require(data.layers.size <= 20_000 && data.layers.map { it.id }.toSet().size == data.layers.size)
            val targets = data.layers.flatMap { it.meshes }
            require(targets.toSet().size == targets.size)
            require(data.layers.all { layer ->
                bounds(layer.bounds)
                layer.attachment?.let { bounds(it.frame) }
                layer.kind in setOf(ChainKind.SKIRT, ChainKind.RIBBON, ChainKind.PENDANT) && layer.pixels > 0 &&
                    (layer.meshes.isEmpty() || layer.kind != ChainKind.SKIRT && layer.attachment != null)
            })
            return data
        }
    }
}
