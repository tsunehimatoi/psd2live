package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.Landmark
import io.github.psd2live.core.fit.TurnStyle
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId

/**
 * depth_turn/1 measures positions in aligned head pixels. The model input is the native, pre-lowering output
 * of the preceding bindings. Deformers in that projection use canonical IDs; mesh IDs are the target IDs.
 * No posed lattices, generated shapes or full legacy rig are part of the source measurements.
 */
data class DepthTurnInputs(
    val head: Bounds, val face: Bounds, val container: DepthTurnMotion.Container,
    val centerY: Float, val radiusX: Float, val radiusY: Float, val chinY: Float,
    val shellTurn: Float, val shellDepth: Float, val style: TurnStyle.Setting,
    val landmarks: FaceLandmarks, val fittedEyes: Set<String>, val fittedBrows: Set<String>,
    val frames: Map<DeformerId, Bounds>, val layers: Map<String, Layer>,
    val leanDown: Float, val leanUp: Float,
    val mouthWarp: DepthTurnMouthWarp? = null,
    val mouth: MouthSourceContext? = null, val mouthChin: Float = .6f,
    val sourceMeshes: ModifierAlgorithmResource? = null,
    val shapeName: String = "{meshName} Shape", val turnName: String = "{meshName} Turn",
) {
    data class Layer(val tag: String, val role: String?, val frame: Bounds, val farRight: Boolean = false,
                     val sourceMesh: String? = null, val follow: MouthRig.Follow? = null, val closesFace: Boolean = false)

    init {
        require(radiusX > 0f && radiusY > 0f && shapeName.isNotBlank() && turnName.isNotBlank())
        require(listOf(centerY, radiusX, radiusY, chinY, shellTurn, shellDepth, leanDown, leanUp, mouthChin,
            container.pivotX, container.pivotY, container.strength, container.shellTurn, container.shellCrown,
            container.shellTilt, style.turn, style.look).all(Float::isFinite))
        require((listOf(head, face) + frames.values + layers.values.map { it.frame }).all {
            it.width > 0f && it.height > 0f && listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite)
        })
        require(layers.size <= 20_000 && landmarks.contour.size <= 20_000 && landmarks.points.size <= 2_000)
        require(landmarks.points.distinctBy { it.id }.size == landmarks.points.size)
        require(style.preset == null || style.preset in TurnStyle.PRESETS)
        require(style.sliders.keys.all { it in TurnStyle.SLIDERS } && style.hair.keys.all { it in TurnStyle.HAIR_SLIDERS })
        require(layers.values.none { it.sourceMesh != null } || sourceMeshes != null)
        require(layers.values.none { it.follow != null || it.closesFace } || mouth != null)
    }

    fun encode(): ModifierAlgorithmContext {
        val mouthInput = mouth?.encode()
        val values = buildJsonObject {
            put("schema", SCHEMA)
            put("head", head.json()); put("face", face.json())
            put("container", floats(container.pivotX, container.pivotY, container.strength,
                container.shellTurn, container.shellCrown, container.shellTilt))
            put("shell", floats(centerY, radiusX, radiusY, chinY, shellTurn, shellDepth))
            put("lean", floats(leanDown, leanUp))
            putJsonObject("style") {
                put("turn", style.turn); put("look", style.look); style.preset?.let { put("preset", it) }
                put("sliders", floatMap(style.sliders)); put("hair", floatMap(style.hair))
            }
            putJsonArray("landmarks") {
                for (mark in landmarks.points) add(JsonArray(listOf(JsonPrimitive(mark.id), JsonPrimitive(mark.part),
                    JsonPrimitive(mark.x), JsonPrimitive(mark.y), JsonPrimitive(mark.later))))
            }
            put("contour", JsonArray(landmarks.contour.map { (x, y) -> floats(x, y) }))
            put("measures", floatMap(landmarks.measures))
            put("eyes", JsonArray(fittedEyes.sorted().map(::JsonPrimitive)))
            put("brows", JsonArray(fittedBrows.sorted().map(::JsonPrimitive)))
            putJsonObject("frames") { frames.forEach { (id, bounds) -> put(id.raw, bounds.json()) } }
            putJsonObject("layers") {
                layers.forEach { (id, layer) -> putJsonObject(id) {
                    put("tag", layer.tag); layer.role?.let { put("role", it) }; put("frame", layer.frame.json())
                    put("farRight", layer.farRight); layer.sourceMesh?.let { put("source", it) }
                    layer.follow?.let { put("follow", it.name) }; put("closesFace", layer.closesFace)
                } }
            }
            mouthWarp?.let { warp -> putJsonObject("mouthWarp") {
                put("region", warp.region.json()); put("parent", warp.parentFrame.json())
                put("tuning", floats(warp.yawEase, warp.pitchEase, warp.featurePlane, warp.nearLag, warp.cornerLag,
                    warp.shift, warp.arch, warp.tilt))
            } }
            mouthInput?.let { put("mouth", it.values); put("mouthChin", mouthChin) }
            sourceMeshes?.let { put("sourceMeshes", it.digest) }
            put("shapeName", shapeName); put("turnName", turnName)
        }
        return ModifierAlgorithmContext(values, mouthInput?.resources.orEmpty() +
            sourceMeshes?.let { mapOf(it.digest to it) }.orEmpty())
    }

    companion object {
        const val SCHEMA = "depth_turn/1"

        fun decode(context: ModifierAlgorithmContext): DepthTurnInputs {
            val values = context.values
            require(values["schema"] == JsonPrimitive(SCHEMA)) { "Unsupported depth turn inputs" }
            val c = values.getValue("container").numbers(6)
            val h = values.getValue("shell").numbers(6)
            val lean = values.getValue("lean").numbers(2)
            val style = values.getValue("style").jsonObject
            val mouth = values["mouth"]?.jsonObject?.let { payload ->
                val digest = payload.getValue("measurement").jsonPrimitive.content
                MouthSourceContext.decode(ModifierAlgorithmContext(payload, mapOf(digest to context.resources.getValue(digest))))
            }
            val mouthWarp = values["mouthWarp"]?.jsonObject?.let { warp ->
                val t = warp.getValue("tuning").numbers(8)
                DepthTurnMouthWarp(warp.getValue("region").bounds(), warp.getValue("parent").bounds(),
                    t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
            }
            val points = values.getValue("landmarks").jsonArray.map { value ->
                val point = value.jsonArray
                require(point.size == 5)
                Landmark(point[0].jsonPrimitive.content, point[1].jsonPrimitive.content, point[2].number(),
                    point[3].number(), point[4].jsonPrimitive.boolean)
            }
            val contour = values.getValue("contour").jsonArray.map { value ->
                val xy = value.numbers(2)
                xy[0] to xy[1]
            }
            val layers = values.getValue("layers").jsonObject.mapValues { (_, entry) ->
                val layer = entry.jsonObject
                Layer(layer.getValue("tag").jsonPrimitive.content, layer["role"]?.jsonPrimitive?.content,
                    layer.getValue("frame").bounds(), layer.getValue("farRight").jsonPrimitive.boolean,
                    layer["source"]?.jsonPrimitive?.content, layer["follow"]?.jsonPrimitive?.content?.let(MouthRig.Follow::valueOf),
                    layer.getValue("closesFace").jsonPrimitive.boolean)
            }
            fun sides(key: String) = values.getValue(key).jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
                .also { require(it.all { side -> side == "L" || side == "R" }) }
            val source = values["sourceMeshes"]?.jsonPrimitive?.content?.let { digest ->
                context.resources.getValue(digest).also { require(it.digest == digest) }
            }
            return DepthTurnInputs(values.getValue("head").bounds(), values.getValue("face").bounds(),
                DepthTurnMotion.Container(c[0], c[1], c[2], c[3], c[4], c[5]), h[0], h[1], h[2], h[3], h[4], h[5],
                TurnStyle.Setting(style.getValue("turn").number(), style.getValue("look").number(),
                    style.getValue("sliders").floatMap(), style.getValue("hair").floatMap(), style["preset"]?.jsonPrimitive?.content),
                FaceLandmarks(points, contour, values.getValue("measures").floatMap()), sides("eyes"), sides("brows"),
                values.getValue("frames").jsonObject.map { (id, frame) -> DeformerId(id) to frame.bounds() }.toMap(),
                layers, lean[0], lean[1], mouthWarp, mouth, values["mouthChin"]?.number() ?: .6f, source,
                values.getValue("shapeName").jsonPrimitive.content, values.getValue("turnName").jsonPrimitive.content)
        }

        private fun floats(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun floatMap(values: Map<String, Float>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        private fun Bounds.json() = floats(left, top, right, bottom)
        private fun JsonElement.number() = jsonPrimitive.float.also { require(it.isFinite()) }
        private fun JsonElement.numbers(count: Int) = jsonArray.also { require(it.size == count) }.map { it.number() }
        private fun JsonElement.bounds() = numbers(4).let { Bounds(it[0], it[1], it[2], it[3]) }
        private fun JsonElement.floatMap() = jsonObject.mapValues { it.value.number() }
    }
}
