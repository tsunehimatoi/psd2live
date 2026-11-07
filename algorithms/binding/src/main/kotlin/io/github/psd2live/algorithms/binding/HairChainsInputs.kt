package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Source measurements in the scaffold's aligned rig-pixel space, in original layer order. */
data class HairChainsInputs(
    val head: HairFollowMotion.Head,
    val eyeY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val planes: Map<String, Plane>,
    val layers: List<Layer>,
    val physicsFolder: String,
    val physicsLabels: Map<String, String>,
    val expressionInputs: List<String> = emptyList(),
) {
    data class Plane(val frame: Bounds, val follow: String, val sway: String?, val part: String)
    data class Layer(
        val sourceId: String, val plane: String, val bounds: Bounds,
        val pixels: Int, val kind: ChainKind?, val shading: Boolean, val paired: Boolean,
        val meshes: List<String>, val warpName: String, val parameterName: String,
    )

    fun context() = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "hair-chains/1")
        put("head", buildJsonObject {
            put("frame", encode(head.frame))
            put("pivot_x", head.pivotX)
            put("pivot_y", head.pivotY)
            put("chin_y", head.chinY)
        })
        put("eye_y", eyeY)
        put("radius_x", radiusX)
        put("radius_y", radiusY)
        put("planes", JsonObject(planes.mapValues { (_, plane) -> buildJsonObject {
            put("frame", encode(plane.frame))
            put("follow", plane.follow)
            put("sway", plane.sway?.let(::JsonPrimitive) ?: JsonNull)
            put("part", plane.part)
        } }))
        put("layers", JsonArray(layers.map { layer -> buildJsonObject {
            put("source", layer.sourceId)
            put("plane", layer.plane)
            put("bounds", encode(layer.bounds))
            put("pixels", layer.pixels)
            put("kind", layer.kind?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("shading", layer.shading)
            put("paired", layer.paired)
            put("meshes", JsonArray(layer.meshes.map(::JsonPrimitive)))
            put("warp_name", layer.warpName)
            put("parameter_name", layer.parameterName)
        } }))
        put("physics_folder", physicsFolder)
        put("physics_labels", JsonObject(physicsLabels.mapValues { JsonPrimitive(it.value) }))
        put("expression_inputs", JsonArray(expressionInputs.map(::JsonPrimitive)))
    })

    companion object {
        fun decode(context: ModifierAlgorithmContext): HairChainsInputs {
            val data = context.values
            require(data.string("schema") == "hair-chains/1") { "Missing hair chain source measurements" }
            val planes = data.getValue("planes").jsonObject.mapValues { (_, value) ->
                val plane = value.jsonObject
                Plane(bounds(plane.getValue("frame")), plane.string("follow"), plane.getValue("sway").jsonPrimitive.contentOrNull,
                    plane.string("part"))
            }
            val layers = data.getValue("layers").jsonArray.map { value ->
                val layer = value.jsonObject
                Layer(layer.string("source"), layer.string("plane"), bounds(layer.getValue("bounds")),
                    layer.getValue("pixels").jsonPrimitive.int, layer.getValue("kind").jsonPrimitive.contentOrNull?.let(ChainKind::valueOf),
                    layer.getValue("shading").jsonPrimitive.boolean, layer.getValue("paired").jsonPrimitive.boolean,
                    layer.strings("meshes"), layer.string("warp_name"), layer.string("parameter_name"))
            }
            require(planes.keys.all { it == "Front" || it == "Back" })
            require(layers.all { it.plane in planes && it.pixels >= 0 && it.kind?.hair != false } &&
                layers.map { it.sourceId }.distinct().size == layers.size &&
                layers.flatMap { it.meshes }.let { it.distinct().size == it.size }) { "Invalid hair layer measurements" }
            val head = data.getValue("head").jsonObject
            val measurement = HairFollowMotion.Head(bounds(head.getValue("frame")), number(head.getValue("pivot_x")),
                number(head.getValue("pivot_y")), number(head.getValue("chin_y")))
            return HairChainsInputs(measurement, number(data.getValue("eye_y")), number(data.getValue("radius_x")), number(data.getValue("radius_y")),
                planes, layers, data.string("physics_folder"), data.getValue("physics_labels").jsonObject.mapValues { it.value.jsonPrimitive.content }, data.strings("expression_inputs")).also {
                require(it.radiusX > 0f && it.radiusY > 0f) { "Invalid hair chain face radii" }
            }
        }

        private fun encode(bounds: Bounds) = JsonArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(::JsonPrimitive))
        private fun bounds(value: JsonElement): Bounds {
            val points = value.jsonArray.map(::number)
            require(points.size == 4)
            return Bounds(points[0], points[1], points[2], points[3]).also {
                require(it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f)
            }
        }
        private fun number(value: JsonElement) = value.jsonPrimitive.float.also { require(it.isFinite()) }
        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    }
}
