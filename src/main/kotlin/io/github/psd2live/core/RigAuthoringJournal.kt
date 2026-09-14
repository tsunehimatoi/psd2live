package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Materialized edits: no point arrays cross the MCP boundary, but replay never reinterprets a brush. */
internal object RigAuthoringJournal {
    fun target(text: String): RigTargetRef {
        val pair = text.split(':', limit = 2)
        require(pair.size == 2 && pair[1].isNotBlank()) { "Use the kind:id reference returned by inspect" }
        return RigTargetRef(RigTargetKind.fromString(pair[0]), pair[1])
    }

    fun apply(model: PuppetModel, edit: JsonObject): PuppetModel = when (edit.getValue("op").jsonPrimitive.content) {
        "path_put", "path_delete" -> DeformPathJournal.apply(model, edit)
        "set" -> applyKeyformSet(model, RigKeyformSetEdit(target(edit.text("target")), edit.coordinate("key"),
            edit["geometry"]?.jsonObject?.let { g -> RigKeyformGeometryEdit(
                controlPoints = g.floats("controlPoints"), positionDeltas = g.floats("positionDeltas"),
                originX = g.number("originX"), originY = g.number("originY"), angle = g.number("angle"), scale = g.number("scale")) },
            edit["channels"]?.jsonObject?.let { c -> RigKeyformChannelsEdit(
                opacity = c.number("opacity"), drawOrder = c.number("drawOrder"),
                multiplyColor = c.floats("multiplyColor"), screenColor = c.floats("screenColor"),
                glueIntensity = c.number("glueIntensity"), flipX = c["flipX"]?.jsonPrimitive?.boolean,
                flipY = c["flipY"]?.jsonPrimitive?.boolean) }))
        "copy" -> applyKeyformCopy(model, RigKeyformCopyEdit(target(edit.text("target")), edit.coordinate("from"),
            target(edit["destination"]?.jsonPrimitive?.content ?: edit.text("target")), edit.coordinate("key"),
            edit["channels"]?.jsonArray?.map { it.jsonPrimitive.content }))
        "delete" -> applyKeyformDelete(model, RigKeyformDeleteEdit(target(edit.text("target")), edit.text("parameter"),
            edit.number("value"), edit["channel"]?.jsonPrimitive?.content))
        "warp" -> RigWarpEdit.fromJson(edit.getValue("warp").jsonObject).applyTo(model)
        "structure" -> RigStructureEdits.apply(model, edit.getValue("edits").jsonArray.map { it.jsonObject })
        else -> error("Unknown authoring journal operation")
    }

    /** Compile against the preceding edit's evaluated model; validate the whole batch before persisting. */
    fun compile(model: PuppetModel, commands: JsonArray): Pair<PuppetModel, List<JsonObject>> {
        require(commands.size in 1..128) { "Use 1..128 edits" }
        var current = model
        val journal = mutableListOf<JsonObject>()
        for (element in commands) {
            val command = element.jsonObject
            val op = command.text("op")
            val compiled = when (op) {
                "deform", "seed" -> {
                    val ref = target(command.text("target"))
                    val key = command.coordinate("key")
                    require(key.isNotEmpty()) { "Specify the exact destination key" }
                    val kind = command.text("target").substringBefore(':')
                    val geometry = RigGeometryTools.geometry(current, kind, ref.id, key)
                    require(geometry.axes.all { it.parameterId.raw in key }) { "KEY_INCOMPLETE: include all directly bound geometry axes" }
                    val points = if (op == "seed") geometry.points else RigGeometryTools.transform(geometry, command.getValue("operations").jsonArray,
                        command["selection"]?.jsonObject ?: JsonObject(emptyMap()))
                    buildJsonObject {
                        put("op", "set"); put("target", command.getValue("target")); put("key", command.getValue("key"))
                        putJsonObject("geometry") {
                            put(if (kind == "warp") "controlPoints" else "positionDeltas",
                                JsonArray(points.indices.map { JsonPrimitive(if (kind == "warp") points[it] else points[it] - geometry.base[it]) }))
                        }
                    }
                }
                "set", "copy", "delete", "warp", "structure", "path_put", "path_delete" -> command
                else -> error("Unknown authoring operation: $op")
            }
            current = apply(current, compiled)
            journal += compiled
        }
        return current to journal
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.coordinate(key: String) = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.float }
    private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.float
    private fun JsonObject.floats(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.float }
}
