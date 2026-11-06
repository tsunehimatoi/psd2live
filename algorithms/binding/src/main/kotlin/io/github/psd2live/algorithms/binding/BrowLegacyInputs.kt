package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Captured aligned source bounds and the scaffold space in which each brow was bound. */
data class BrowLegacyInputs(
    val targets: Map<String, Brow>,
    val lift: Float,
    val leftName: String,
    val rightName: String,
    val groupName: String,
) {
    data class Brow(val side: Side, val height: Float, val frameHeight: Float, val parent: String? = null) {
        init {
            require(height.isFinite() && height >= 0f && frameHeight.isFinite() && frameHeight > 0f)
            require(parent == null || parent.isNotBlank())
        }
    }

    init {
        require(lift.isFinite() && lift in 0f..40f)
        require(targets.size <= 20_000 && targets.keys.all { it.isNotBlank() && it.length <= 512 })
        require(listOf(leftName, rightName, groupName).all { it.isNotBlank() && it.length <= 512 })
    }

    fun encode(): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("lift", lift)
        put("leftName", leftName)
        put("rightName", rightName)
        put("groupName", groupName)
        putJsonObject("targets") {
            for ((id, brow) in targets) putJsonObject(id) {
                put("side", brow.side.name)
                put("height", brow.height)
                put("frameHeight", brow.frameHeight)
                put("parent", brow.parent?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    })

    companion object {
        const val SCHEMA = "brow-legacy/1"

        fun decode(context: ModifierAlgorithmContext): BrowLegacyInputs {
            val values = context.values
            require(values.keys == setOf("schema", "lift", "leftName", "rightName", "groupName", "targets") &&
                values["schema"] == JsonPrimitive(SCHEMA) && context.resources.isEmpty()) { "Invalid legacy brow inputs" }
            val targets = values.getValue("targets").jsonObject.mapValues { (_, encoded) ->
                val brow = encoded.jsonObject
                require(brow.keys == setOf("side", "height", "frameHeight", "parent"))
                Brow(Side.valueOf(brow.getValue("side").jsonPrimitive.content), brow.getValue("height").jsonPrimitive.float,
                    brow.getValue("frameHeight").jsonPrimitive.float, brow.getValue("parent").jsonPrimitive.contentOrNull)
            }
            return BrowLegacyInputs(targets, values.getValue("lift").jsonPrimitive.float,
                values.getValue("leftName").jsonPrimitive.content, values.getValue("rightName").jsonPrimitive.content,
                values.getValue("groupName").jsonPrimitive.content)
        }
    }
}
