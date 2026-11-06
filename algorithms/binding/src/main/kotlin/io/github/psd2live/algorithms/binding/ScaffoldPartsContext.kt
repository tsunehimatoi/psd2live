package io.github.psd2live.algorithms.binding

import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId

/** Source classifications and localized labels used to construct the scaffold's organization tree. */
object ScaffoldPartsContext {
    private const val SCHEMA = "scaffold_parts/1"
    private val labelKeys = listOf("model.part.head") + ScaffoldParts.Slot.entries.map { it.label }.distinct()

    data class Input(val materials: List<ScaffoldParts.Material>, val labels: Map<String, String>)

    fun capture(materials: List<ScaffoldParts.Material>, label: (String) -> String, checkpoint: () -> Unit = {}): JsonObject {
        checkpoint()
        val ids = HashSet<DrawableId>()
        return buildJsonObject {
            put("schema", SCHEMA)
            put("materials", buildJsonArray {
                for (material in materials) {
                    checkpoint()
                    require(material.id.raw.isNotBlank() && ids.add(material.id)) { "Invalid or duplicate scaffold material ID" }
                    add(buildJsonObject {
                        put("id", material.id.raw)
                        put("slot", material.slot.name)
                        put("order", material.sourceOrder)
                    })
                }
            })
            put("labels", buildJsonObject {
                for (key in labelKeys) {
                    checkpoint()
                    put(key, label(key))
                }
            })
        }
    }

    fun decode(context: JsonObject, checkpoint: () -> Unit = {}): Input {
        checkpoint()
        require(context.keys == setOf("schema", "materials", "labels") && string(context["schema"]) == SCHEMA) {
            "Invalid scaffold parts context"
        }
        val ids = HashSet<DrawableId>()
        val records = requireNotNull(context["materials"] as? JsonArray) { "Invalid scaffold materials" }
        val materials = records.map { record ->
            checkpoint()
            require(record is JsonObject && record.keys == setOf("id", "slot", "order")) { "Invalid scaffold material" }
            val id = DrawableId(string(record["id"]))
            require(id.raw.isNotBlank() && ids.add(id)) { "Invalid or duplicate scaffold material ID" }
            val name = string(record["slot"])
            val slot = requireNotNull(ScaffoldParts.Slot.entries.find { it.name == name }) { "Unknown scaffold part slot: $name" }
            val order = (record["order"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ScaffoldParts.Material(id, slot, requireNotNull(order) { "Invalid scaffold material order" })
        }
        val labels = requireNotNull(context["labels"] as? JsonObject) { "Invalid scaffold part labels" }
        require(labels.keys == labelKeys.toSet()) { "Invalid scaffold part label keys" }
        return Input(materials, labels.mapValues { string(it.value) })
    }

    fun build(context: JsonObject, checkpoint: () -> Unit = {}): ScaffoldParts.Result {
        val input = decode(context, checkpoint)
        val result = ScaffoldParts.build(input.materials) { key ->
            checkpoint()
            input.labels.getValue(key)
        }
        checkpoint()
        return result
    }

    private fun string(value: JsonElement?): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }) { "Expected a scaffold text value" }.content
}
