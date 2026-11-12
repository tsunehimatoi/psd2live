package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.model.Deformer
import kotlinx.serialization.json.*

/** Numeric source contract. No host-evaluated motion, lowered mesh or generated grid is admitted. */
object BrowShapeV7Source {
    const val SCHEMA = "brow-shape/7"
    private val fields = setOf("schema", "brow", "source_ancestry", "providers", "provider_packages", "head_turn",
        "native_ids", "mesh_names", "turn_name", "shape_name")

    fun validate(context: ModifierAlgorithmContext) {
        require(context.values.keys == fields && context.values["schema"] == JsonPrimitive(SCHEMA) &&
            context.resources.keys == setOf("source_meshes")) {
            "Brow shape v7 accepts only numerical source observations and raw brow topology"
        }
    }

    fun source(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}): BrowShapeInputs {
        validate(context)
        return BrowShapeInputs.decode(ModifierAlgorithmContext(context.values.getValue("brow").jsonObject,
            context.resources), checkpoint)
    }

    /** Version envelope stays here; the frozen kernel sees only numerical observations. */
    fun branch(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}): List<Deformer> {
        val brow = source(context, checkpoint)
        val headTurn = context.values.getValue("head_turn").jsonObject
        require(headTurn.keys == setOf("provider", "shell"))
        val shell = headTurn.getValue("shell").jsonArray.map { value -> value.jsonPrimitive.let { p ->
            require(!p.isString)
            p.float.also { require(it.isFinite()) }
        } }
        return BrowSourceAncestryV1.branch(context.values.getValue("source_ancestry").jsonObject, brow, shell,
            context.values.getValue("native_ids").jsonObject, context.values.getValue("mesh_names").jsonObject, checkpoint)
    }
}
