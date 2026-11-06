package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*

/** Source measurements in aligned canvas pixels. No keyforms or evaluated meshes cross this boundary. */
data class BrowFittedInputs(
    val scaffold: String,
    val fits: Map<Side, BrowFit>,
    val eyes: Map<Side, Eye>,
    val layers: Map<String, Layer>,
    val leftName: String,
    val rightName: String,
    val groupName: String,
    val fallbackLift: Float,
) {
    data class Eye(val provider: String, val opening: Float, val width: Float) {
        init {
            require(provider.isNotBlank() && opening.isFinite() && width.isFinite() && width > 0f)
        }
    }

    /** Canonical source vertex ID and parent frame, and the legacy fallback's aligned artwork height. */
    data class Layer(val canonical: String, val side: Side, val primary: Boolean, val frame: Bounds, val height: Float) {
        init {
            require(canonical.isNotBlank() && frame.width.isFinite() && frame.width > 0f &&
                frame.height.isFinite() && frame.height > 0f && height.isFinite() && height >= 0f)
        }
    }

    init {
        require(scaffold.isNotBlank() && fallbackLift.isFinite() && Side.NONE !in fits && Side.NONE !in eyes)
        require(fits.all { (side, fit) ->
            side == fit.side && fit.length > 0f && fit.centre.size == BrowFit.SAMPLES &&
                (fit.centre + listOf(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length)).all(Float::isFinite)
        })
    }

    fun encode(vertices: ModifierAlgorithmResource, upstream: List<ModifierUpstreamCapability>) =
        ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("scaffold", scaffold)
            put("left_name", leftName)
            put("right_name", rightName)
            put("group_name", groupName)
            put("fallback_lift", fallbackLift)
            putJsonObject("fits") {
                for ((side, fit) in fits) put(side.name, numbers(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length, *fit.centre.toFloatArray()))
            }
            putJsonObject("eyes") {
                for ((side, eye) in eyes) putJsonObject(side.name) {
                    put("provider", eye.provider)
                    put("measurements", numbers(eye.opening, eye.width))
                }
            }
            putJsonObject("layers") {
                for ((id, layer) in layers) putJsonObject(id) {
                    put("canonical", layer.canonical)
                    put("side", layer.side.name)
                    put("primary", layer.primary)
                    put("frame", numbers(layer.frame.left, layer.frame.top, layer.frame.right, layer.frame.bottom))
                    put("height", layer.height)
                }
            }
        }, mapOf(VERTICES to vertices), upstream)

    companion object {
        const val SCHEMA = "brow-fitted/1"
        const val VERTICES = "source_meshes"

        fun decode(context: ModifierAlgorithmContext): BrowFittedInputs {
            val values = context.values
            require(values.string("schema") == SCHEMA && context.resources.keys == setOf(VERTICES)) {
                "Missing fitted brow source inputs"
            }
            val scaffold = values.string("scaffold")
            val fits = values.getValue("fits").jsonObject.map { (name, encoded) ->
                val side = Side.valueOf(name)
                val fit = numbers(encoded, 5 + BrowFit.SAMPLES)
                side to BrowFit(side, fit[0], fit[1], fit[2], fit[3], fit[4], fit.drop(5))
            }.toMap()
            val eyes = values.getValue("eyes").jsonObject.map { (name, encoded) ->
                val eye = encoded.jsonObject
                val measurements = numbers(eye.getValue("measurements"), 2)
                Side.valueOf(name) to Eye(eye.string("provider"), measurements[0], measurements[1])
            }.toMap()
            val layers = values.getValue("layers").jsonObject.mapValues { (_, encoded) ->
                val layer = encoded.jsonObject
                val frame = numbers(layer.getValue("frame"), 4)
                Layer(layer.string("canonical"), Side.valueOf(layer.string("side")), layer.getValue("primary").jsonPrimitive.boolean,
                    Bounds(frame[0], frame[1], frame[2], frame[3]), layer.getValue("height").jsonPrimitive.float)
            }
            return BrowFittedInputs(scaffold, fits, eyes, layers, values.string("left_name"), values.string("right_name"),
                values.string("group_name"), values.getValue("fallback_lift").jsonPrimitive.float)
        }

        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        private fun numbers(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun numbers(value: JsonElement, size: Int) = value.jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == size && it.all(Float::isFinite)) { "Invalid fitted brow measurement" }
        }
    }
}
