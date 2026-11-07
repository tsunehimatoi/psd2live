package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Source measurements for neck motion. Raw mesh points are in the original rig frame (Y down). */
data class NeckInputs(
    val pivotX: Float,
    val pivotY: Float,
    val neck: Bounds?,
    val masks: List<String>,
    val targets: Map<String, Target>,
) {
    data class Target(
        val sourceMesh: String,
        val bounds: Bounds,
        val frame: Bounds,
        val parent: String?,
        val shadow: Boolean,
    )

    init {
        require(pivotX.isFinite() && pivotY.isFinite())
        neck?.validate()
        require(masks.distinct().size == masks.size && masks.all(String::isNotBlank))
        require(targets.keys.all(String::isNotBlank))
        targets.values.forEach {
            require(it.sourceMesh.isNotBlank() && (it.parent == null || it.parent.isNotBlank()))
            it.bounds.validate()
            it.frame.validate()
        }
    }

    fun encode(meshes: ModifierAlgorithmContext): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("pivot", JsonArray(listOf(pivotX, pivotY).map(::JsonPrimitive)))
        put("neck", neck?.let(::box) ?: JsonNull)
        put("masks", JsonArray(masks.map(::JsonPrimitive)))
        putJsonObject("targets") {
            for ((id, target) in targets) putJsonObject(id) {
                put("mesh", target.sourceMesh)
                put("bounds", box(target.bounds))
                put("frame", box(target.frame))
                put("parent", target.parent?.let(::JsonPrimitive) ?: JsonNull)
                put("shadow", target.shadow)
            }
        }
    }, meshes.resources.filterKeys { it == SOURCE_MESHES })

    companion object {
        const val SCHEMA = "neck/1"
        const val SOURCE_MESHES = "source_meshes"

        fun decode(context: ModifierAlgorithmContext): NeckInputs {
            val values = context.values
            require(values.keys == setOf("schema", "pivot", "neck", "masks", "targets") &&
                values["schema"] == JsonPrimitive(SCHEMA) && context.resources.keys == setOf(SOURCE_MESHES)) {
                "Invalid neck source inputs"
            }
            val pivot = values.getValue("pivot").jsonArray
            require(pivot.size == 2)
            return NeckInputs(pivot[0].jsonPrimitive.float, pivot[1].jsonPrimitive.float,
                values.getValue("neck").takeUnless { it == JsonNull }?.let(::readBox),
                values.getValue("masks").jsonArray.map { it.jsonPrimitive.content },
                values.getValue("targets").jsonObject.mapValues { (_, value) ->
                    val target = value.jsonObject
                    require(target.keys == setOf("mesh", "bounds", "frame", "parent", "shadow"))
                    Target(target.getValue("mesh").jsonPrimitive.content,
                        readBox(target.getValue("bounds")), readBox(target.getValue("frame")),
                        target.getValue("parent").jsonPrimitive.contentOrNull,
                        target.getValue("shadow").jsonPrimitive.boolean)
                })
        }

        private fun Bounds.validate() {
            require(listOf(left, top, right, bottom, width, height).all(Float::isFinite) && width >= 0f && height >= 0f)
        }

        private fun box(bounds: Bounds) =
            JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(::JsonPrimitive))

        private fun readBox(value: JsonElement): Bounds {
            val edges = value.jsonArray
            require(edges.size == 4)
            return Bounds(edges[0].jsonPrimitive.float, edges[1].jsonPrimitive.float,
                edges[2].jsonPrimitive.float, edges[3].jsonPrimitive.float)
        }
    }
}
