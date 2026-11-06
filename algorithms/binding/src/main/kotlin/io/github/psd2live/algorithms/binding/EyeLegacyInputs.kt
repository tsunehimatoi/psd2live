package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Source measurements for eye/legacy, keyed by canonical scaffold mesh ID. */
data class EyeLegacyInputs(
    val faceCenterX: Float,
    val tuning: EyeLegacyMotion.Tuning,
    val layers: Map<String, Layer>,
    val sourceMeshes: ModifierAlgorithmResource,
    val openings: List<EyeOpeningObservation> = emptyList(),
) {
    data class Layer(
        val side: Side,
        val frame: Bounds,
        val blink: ScaffoldMesh.Geometry.Eye? = null,
        val iris: ScaffoldMesh.Geometry.Iris? = null,
        val closedDrawing: Boolean = false,
        val originalMasks: List<String> = emptyList(),
        val irisSource: EyeOpeningObservation? = null,
    )

    fun encode(): ModifierAlgorithmContext = ModifierAlgorithmContext(buildJsonObject {
        put("schema", SCHEMA)
        put("face_center_x", faceCenterX)
        put("gaze_x", tuning.gazeX)
        put("gaze_y", tuning.gazeY)
        put("tuning", numbers(tuning.blinkCorner, tuning.blinkDepth, tuning.lashSquash, tuning.irisSquash, tuning.irisStretch))
        put("source_meshes", sourceMeshes.digest)
        put("openings", JsonArray(openings.map(::observation)))
        putJsonObject("layers") {
            for ((id, layer) in layers) putJsonObject(id) {
                put("side", layer.side.name)
                put("frame", bounds(layer.frame))
                put("closed_drawing", layer.closedDrawing)
                put("original_masks", JsonArray(layer.originalMasks.map(::JsonPrimitive)))
                layer.irisSource?.let { put("iris_source", observation(it)) }
                layer.iris?.let { put("iris_center", numbers(it.centroidX, it.centroidY)) }
                layer.blink?.let { blink ->
                    putJsonObject("blink") {
                        put("lash", blink.lash)
                        put("bounds", bounds(blink.bounds))
                        put("whites", JsonArray(blink.whiteBounds.map(::bounds)))
                        put("centroid_y", blink.centroidY)
                        put("source_left", blink.sourceLeft)
                        blink.centerline?.let { put("centerline", numbers(*it)) }
                    }
                }
            }
        }
    }, mapOf("source_meshes" to sourceMeshes))

    companion object {
        const val SCHEMA = "eye-legacy/1"

        fun decode(context: ModifierAlgorithmContext): EyeLegacyInputs {
            val input = context.values
            require(input["schema"] == JsonPrimitive(SCHEMA) &&
                input.keys == setOf("schema", "face_center_x", "gaze_x", "gaze_y", "tuning", "source_meshes", "layers", "openings"))
            val resource = context.resources.getValue("source_meshes")
            require(context.resources.size == 1 && input["source_meshes"] == JsonPrimitive(resource.digest))
            val (corner, depth, lash, squash, stretch) = floats(input.getValue("tuning"), 5)
            val tuning = EyeLegacyMotion.Tuning(corner, depth, lash, squash, stretch,
                finite(input.getValue("gaze_x")), finite(input.getValue("gaze_y")))
            val layers = input.getValue("layers").jsonObject.mapValues { (id, value) ->
                require(id.isNotBlank())
                val layer = value.jsonObject
                require(layer.keys.all { it in setOf("side", "frame", "closed_drawing", "original_masks", "iris_source", "iris_center", "blink") })
                val blink = layer["blink"]?.jsonObject?.let {
                    require(it.keys.all { key -> key in setOf("lash", "bounds", "whites", "centroid_y", "source_left", "centerline") })
                    ScaffoldMesh.Geometry.Eye(it.getValue("lash").jsonPrimitive.boolean, readBounds(it.getValue("bounds")),
                        it.getValue("whites").jsonArray.map(::readBounds), finite(it.getValue("centroid_y")),
                        it.getValue("source_left").jsonPrimitive.int, it["centerline"]?.jsonArray?.let { values ->
                            require(values.size in 1..4_194_304)
                            FloatArray(values.size) { index -> finite(values[index]) }
                        }, corner, depth, lash)
                }
                val iris = layer["iris_center"]?.let {
                    val (x, y) = floats(it, 2)
                    ScaffoldMesh.Geometry.Iris(x, y, squash, stretch)
                }
                val drawing = layer.getValue("closed_drawing").jsonPrimitive.boolean
                require(listOf(blink != null, iris != null, drawing).count { it } <= 1)
                Layer(Side.valueOf(layer.getValue("side").jsonPrimitive.content), readBounds(layer.getValue("frame")),
                    blink, iris, drawing, strings(layer.getValue("original_masks")), layer["iris_source"]?.let(::readObservation))
            }
            return EyeLegacyInputs(finite(input.getValue("face_center_x")), tuning, layers, resource,
                input.getValue("openings").jsonArray.map(::readObservation))
        }

        private fun observation(value: EyeOpeningObservation) = buildJsonObject {
            put("id", value.id)
            put("source_id", value.sourceId)
            put("side", value.side.name)
            put("bounds", bounds(value.bounds))
            put("centroid", numbers(value.centroidX, value.centroidY))
            put("group", value.groupPath)
            value.switchParameter?.let { put("switch_parameter", it) }
            value.switchId?.let { put("switch_id", it) }
        }

        private fun readObservation(value: JsonElement): EyeOpeningObservation {
            val objectValue = value.jsonObject
            require(objectValue.keys.all { it in setOf("id", "source_id", "side", "bounds", "centroid", "group", "switch_parameter", "switch_id") })
            val (x, y) = floats(objectValue.getValue("centroid"), 2)
            return EyeOpeningObservation(objectValue.getValue("id").jsonPrimitive.content,
                objectValue.getValue("source_id").jsonPrimitive.content,
                Side.valueOf(objectValue.getValue("side").jsonPrimitive.content), readBounds(objectValue.getValue("bounds")),
                x, y, objectValue.getValue("group").jsonPrimitive.content,
                objectValue["switch_parameter"]?.jsonPrimitive?.content, objectValue["switch_id"]?.jsonPrimitive?.int)
        }
        private fun bounds(value: Bounds) = numbers(value.left, value.top, value.right, value.bottom)
        private fun numbers(vararg values: Float) = JsonArray(values.map { require(it.isFinite()); JsonPrimitive(it) })
        private fun finite(value: JsonElement) = value.jsonPrimitive.float.also { require(it.isFinite()) }
        private fun floats(value: JsonElement, count: Int) = value.jsonArray.also { require(it.size == count) }.map(::finite)
        private fun readBounds(value: JsonElement): Bounds {
            val (left, top, right, bottom) = floats(value, 4)
            require(left <= right && top <= bottom)
            return Bounds(left, top, right, bottom)
        }
        private fun strings(value: JsonElement) = value.jsonArray.map {
            it.jsonPrimitive.also { id -> require(id.isString && id.content.isNotBlank()) }.content
        }
    }
}
