package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Source iris topology and the measurements needed to express its motion in the fitted eye frame. */
data class EyeSquashInputs(
    val side: Side, val crease: Float, val parent: String?, val frame: Bounds,
    val iris: ScaffoldMesh.Geometry.Iris, val raw: ScaffoldMesh.RawMesh,
) {
    init {
        require(side != Side.NONE && crease.isFinite() && crease > 0f && crease < 1f)
        require(listOf(frame.left, frame.top, frame.right, frame.bottom, iris.centroidX, iris.centroidY,
            iris.jellySquash, iris.jellyStretch).all(Float::isFinite) && frame.width > 0f && frame.height > 0f)
    }

    companion object {
        private const val SCHEMA = "eye-squash/1"

        fun encode(targets: Map<String, EyeSquashInputs>): ModifierAlgorithmContext {
            val meshes = ScaffoldMeshContext.capture(targets.map { (id, source) ->
                ScaffoldMeshContext.Material(id, id, source.raw, source.iris)
            })
            return ModifierAlgorithmContext(buildJsonObject {
                put("schema", SCHEMA)
                putJsonObject("targets") {
                    targets.forEach { (id, source) -> putJsonObject(id) {
                        put("side", source.side.name)
                        put("crease", source.crease)
                        put("parent", source.parent?.let(::JsonPrimitive) ?: JsonNull)
                        put("frame", numbers(source.frame.left, source.frame.top, source.frame.right, source.frame.bottom))
                        put("iris", numbers(source.iris.centroidX, source.iris.centroidY, source.iris.jellySquash, source.iris.jellyStretch))
                    } }
                }
            }, meshes.resources)
        }

        fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): Map<String, EyeSquashInputs> {
            require(context.values.keys == setOf("schema", "targets") && context.values["schema"] == JsonPrimitive(SCHEMA))
            val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("source_meshes"), checkpoint)
            val targets = context.values.getValue("targets").jsonObject
            require(targets.keys == raw.keys)
            return targets.mapValues { (id, value) ->
                checkpoint()
                val target = value.jsonObject
                require(target.keys == setOf("side", "crease", "parent", "frame", "iris"))
                val (left, top, right, bottom) = floats(target.getValue("frame"))
                val (x, y, squash, stretch) = floats(target.getValue("iris"))
                EyeSquashInputs(Side.valueOf(target.getValue("side").jsonPrimitive.content),
                    target.getValue("crease").jsonPrimitive.float, target.getValue("parent").jsonPrimitive.contentOrNull,
                    Bounds(left, top, right, bottom), ScaffoldMesh.Geometry.Iris(x, y, squash, stretch), raw.getValue(id))
            }
        }

        private fun numbers(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun floats(value: JsonElement) = value.jsonArray.map { it.jsonPrimitive.float }.also { require(it.size == 4) }
    }
}
