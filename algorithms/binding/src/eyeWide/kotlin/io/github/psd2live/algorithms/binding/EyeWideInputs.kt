package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Source art and neutral mesh measurements, without any motion keys or model hierarchy. */
@Serializable
data class EyeWideInputs(
    val faceCenterX: Float,
    val alignment: EyeFittedInputs.Alignment?,
    val artworkStamps: Map<String, String>,
    val moves: Map<String, Map<String, RigMove>>,
    val corrections: Map<String, Map<String, EyePartOverride>>,
    val targets: Map<String, Target>,
    val jellySquash: Float,
    val jellyStretch: Float,
) {
    @Serializable
    data class Target(
        val layer: String,
        val parent: String?,
        val frame: ModifierSourceBounds,
        val centroidX: Float,
        val centroidY: Float,
        val preset: Boolean,
        val automaticOpacity: Boolean,
        val opacity: Float,
    )

    fun encode(sources: ModifierSourceInputs, topology: ModifierAlgorithmResource): ModifierAlgorithmContext {
        val source = sources.context()
        require(TOPOLOGY !in source.resources)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("measurements", Json.encodeToJsonElement(this@EyeWideInputs))
            put("art", source.values)
        }, source.resources + (TOPOLOGY to topology))
    }

    companion object {
        private const val SCHEMA = "eye-wide/1"
        private const val TOPOLOGY = "eye_wide_topology"

        data class Decoded(
            val measurements: EyeWideInputs,
            val art: ModifierSourceInputs,
            val topology: Map<String, ScaffoldMesh.RawMesh>,
        )

        fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): Decoded {
            require(context.values.keys == setOf("schema", "measurements", "art") &&
                context.values.getValue("schema").jsonPrimitive.content == SCHEMA) { "Unsupported eye widening input schema" }
            val measurements = Json.decodeFromJsonElement<EyeWideInputs>(context.values.getValue("measurements"))
            val art = ModifierSourceInputs.from(ModifierAlgorithmContext(
                context.values.getValue("art").jsonObject, context.resources - TOPOLOGY))
            val topology = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(TOPOLOGY), checkpoint)
            require(measurements.targets.mapValues { it.value.layer } == art.targets &&
                measurements.targets.values.all { it.layer in topology }) { "Invalid eye widening source references" }
            return Decoded(measurements, art, topology)
        }
    }
}
