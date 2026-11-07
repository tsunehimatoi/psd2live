package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Source measurements before any authored opacity edits. */
data class LidFadeInputs(val parameter: String, val crease: Float, val opacity: Float) {
    init {
        require(parameter in setOf("ParamEyeLOpen", "ParamEyeROpen"))
        require(crease in .15f.. .25f && opacity in 0f..1f)
    }

    companion object {
        private const val SCHEMA = "lid-fade/1"

        fun encode(targets: Map<String, LidFadeInputs>) = ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            putJsonObject("targets") {
                for ((id, source) in targets) putJsonObject(id) {
                    put("parameter", source.parameter)
                    put("crease", source.crease)
                    put("opacity", source.opacity)
                }
            }
        })

        fun decode(context: ModifierAlgorithmContext): Map<String, LidFadeInputs> {
            val values = context.values
            require(values.keys == setOf("schema", "targets") && values["schema"] == JsonPrimitive(SCHEMA) && context.resources.isEmpty())
            return values.getValue("targets").jsonObject.mapValues { (_, encoded) ->
                val source = encoded.jsonObject
                require(source.keys == setOf("parameter", "crease", "opacity"))
                LidFadeInputs(source.getValue("parameter").jsonPrimitive.content,
                    source.getValue("crease").jsonPrimitive.float, source.getValue("opacity").jsonPrimitive.float)
            }
        }
    }
}
