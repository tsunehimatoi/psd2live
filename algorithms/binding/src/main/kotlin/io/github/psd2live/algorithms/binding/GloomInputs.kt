package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*

/**
 * Source measurements and the preceding binding's neutral channel projection for one gloom pass.
 * Projection meshes carry only static channel values and channel grids. The provider must certify
 * that applying mood before lowering gives the same geometry and channel result as applying it here.
 */
data class GloomInputs(
    val applies: Boolean,
    val parameterName: String,
    val groupName: String,
    val layers: Map<String, Layer>,
    val neutral: Map<String, Mesh>,
) {
    data class Layer(
        val skin: Boolean,
        val highlight: Boolean,
        val cheekWarmth: Float,
        val precedingCheekStrength: Float?,
        val precedingGloomStrength: Float? = null,
    )

    init {
        require(neutral.keys == layers.keys && neutral.all { (id, mesh) ->
            mesh.id == id && mesh.geometry == null && mesh.offsets == null && mesh.parent == null && mesh.shapes.isEmpty()
        }) { "Gloom needs a channel-only upstream projection for each measured layer" }
        require(layers.values.all { it.cheekWarmth in 0f..1f &&
            listOfNotNull(it.precedingCheekStrength, it.precedingGloomStrength).all { strength -> strength in 0f..200f } })
    }

    fun context(): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "expression.gloom/1")
        put("applies", applies)
        put("parameter_name", parameterName)
        put("group_name", groupName)
        putJsonObject("layers") {
            for ((id, layer) in layers) put(id, buildJsonObject {
                put("skin", layer.skin)
                put("highlight", layer.highlight)
                put("cheek_warmth", layer.cheekWarmth)
                layer.precedingCheekStrength?.let { put("preceding_cheek_strength", it) }
                layer.precedingGloomStrength?.let { put("preceding_gloom_strength", it) }
            })
        }
    }, mapOf("neutral_channels" to ModifierAlgorithmResource(RigIrBinary.encode(
        RigIR(Canvas(1f, 1f), emptyList(), meshes = neutral.values.toList())))))

    companion object {
        fun decode(context: ModifierAlgorithmContext): GloomInputs {
            val values = context.values
            require(values["schema"]?.jsonPrimitive?.content == "expression.gloom/1") { "Missing gloom author inputs" }
            val layers = values.getValue("layers").jsonObject.mapValues { (_, value) ->
                val layer = value.jsonObject
                Layer(layer.getValue("skin").jsonPrimitive.boolean, layer.getValue("highlight").jsonPrimitive.boolean,
                    layer.getValue("cheek_warmth").jsonPrimitive.float, layer["preceding_cheek_strength"]?.jsonPrimitive?.float,
                    layer["preceding_gloom_strength"]?.jsonPrimitive?.float)
            }
            val neutral = RigIrBinary.decode(context.resources.getValue("neutral_channels").read()).meshes
            require(neutral.map { it.id }.distinct().size == neutral.size) { "Duplicate gloom projection mesh" }
            return GloomInputs(values.getValue("applies").jsonPrimitive.boolean, values.getValue("parameter_name").jsonPrimitive.content,
                values.getValue("group_name").jsonPrimitive.content, layers, neutral.associateBy { it.id })
        }
    }
}
