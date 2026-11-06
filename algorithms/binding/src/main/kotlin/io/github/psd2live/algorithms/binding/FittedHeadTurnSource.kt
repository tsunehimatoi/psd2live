package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.fit.Landmark
import io.github.psd2live.core.fit.TurnStyle
import kotlinx.serialization.json.*

/** Raw, aligned-space head-turn measurements and style knobs shared by facial bindings. */
data class FittedHeadTurnSource(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val chinY: Float,
    val turn: Float,
    val depth: Float,
    val landmarks: FaceLandmarks,
    val style: TurnStyle.Setting,
) {
    init {
        require(listOf(centerX, centerY, radiusX, radiusY, chinY, turn, depth, style.turn, style.look).all(Float::isFinite))
        require(radiusX > 0f && radiusY > 0f)
        require(landmarks.points.size <= 4096 && landmarks.contour.size <= 4096)
        require(landmarks.points.all { it.x.isFinite() && it.y.isFinite() } &&
            landmarks.contour.all { it.first.isFinite() && it.second.isFinite() })
        require(landmarks.measures.values.all(Float::isFinite))
        require(style.sliders.values.all(Float::isFinite) && style.hair.values.all(Float::isFinite))
    }

    fun depthTurn(checkpoint: () -> Unit = {}) = HeadTurn(centerX, centerY, radiusX, radiusY, turn, depth, chinY, checkpoint)
    fun styledTurn(): FaceTurn? = FaceTurn(landmarks, style).takeIf { it.usable }

    // Repeated landmarks (e.g. multiple ear layers on one side) retain source order and last-wins lookup.
    fun encode(): JsonObject = buildJsonObject {
        put("schema", CAPABILITY)
        put("shell", tuple(centerX, centerY, radiusX, radiusY, chinY, turn, depth))
        putJsonArray("landmarks") {
            for (point in landmarks.points) add(buildJsonObject {
                put("id", point.id)
                put("part", point.part)
                put("xy", tuple(point.x, point.y))
                put("later", point.later)
            })
        }
        put("contour", JsonArray(landmarks.contour.map { tuple(it.first, it.second) }))
        put("measures", floats(landmarks.measures))
        putJsonObject("style") {
            put("turn", style.turn)
            put("look", style.look)
            put("sliders", floats(style.sliders))
            put("hair", floats(style.hair))
            style.preset?.let { put("preset", it) }
        }
    }

    companion object {
        const val CAPABILITY = "fitted-head-turn/1"

        fun decode(value: JsonObject): FittedHeadTurnSource {
            require(value.getValue("schema").jsonPrimitive.content == CAPABILITY) { "Unknown head-turn source schema" }
            val shell = readTuple(value.getValue("shell"), 7)
            val points = value.getValue("landmarks").jsonArray.also { require(it.size <= 4096) }
            val outline = value.getValue("contour").jsonArray.also { require(it.size <= 4096) }
            val marks = FaceLandmarks(points.map {
                val point = it.jsonObject
                val (x, y) = readTuple(point.getValue("xy"), 2)
                Landmark(point.getValue("id").jsonPrimitive.content, point.getValue("part").jsonPrimitive.content,
                    x, y, point.getValue("later").jsonPrimitive.boolean)
            }, outline.map {
                val (x, y) = readTuple(it, 2)
                x to y
            }, readFloats(value.getValue("measures")))
            val style = value.getValue("style").jsonObject
            return FittedHeadTurnSource(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], marks,
                TurnStyle.Setting(number(style.getValue("turn")), number(style.getValue("look")),
                    readFloats(style.getValue("sliders")), readFloats(style.getValue("hair")),
                    style["preset"]?.jsonPrimitive?.content))
        }

        internal fun tuple(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        internal fun readTuple(value: JsonElement, size: Int) = value.jsonArray.also { require(it.size == size) }.map(::number)
        private fun number(value: JsonElement): Float {
            val primitive = value.jsonPrimitive
            require(!primitive.isString)
            return primitive.float.also { require(it.isFinite()) }
        }
        private fun floats(values: Map<String, Float>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        private fun readFloats(value: JsonElement) = value.jsonObject.mapValues { number(it.value) }
    }
}
