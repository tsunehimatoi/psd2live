package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.model.Parameter
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.DrawableMesh
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/** hair-legacy/1 contains source frame measurements and the resolved scaffold carrier identities. */
object HairLegacyInputs {
    data class Source(
        val frame: HairLegacyMotion.Frame,
        val current: HairLegacyMotion.Tuning,
        val defaults: HairLegacyMotion.Tuning,
    )

    data class Target(
        val plane: HairLegacyMotion.Plane,
        val follow: String,
        val sway: String?,
        val head: String,
        val earlierBinding: Boolean,
        val activeTurnAxes: Set<ParameterId>,
        val fittedFace: String?,
        val neutralMesh: String?,
    )

    data class Input(
        val planes: Map<HairLegacyMotion.Plane, Source>,
        val targets: Map<String, Target>,
        val parameters: List<Parameter>,
        val physicsFolderName: String,
    )

    fun capture(input: Input) = ModifierAlgorithmContext(buildJsonObject {
        put("schema", "hair-legacy/1")
        put("physics_folder", input.physicsFolderName)
        putJsonObject("parameters") {
            for (p in input.parameters) put(p.id, p.name)
        }
        putJsonObject("planes") {
            for ((plane, source) in input.planes) putJsonObject(plane.name) {
                put("hair", source.frame.hair.encode())
                put("head", source.frame.head.encode())
                put("pivot", numbers(source.frame.pivot.x, source.frame.pivot.y))
                put("chin_y", source.frame.chinY)
                put("tuning", source.current.encode())
                put("defaults", source.defaults.encode())
            }
        }
        putJsonObject("targets") {
            for ((id, target) in input.targets) putJsonObject(id) {
                put("plane", target.plane.name)
                put("follow", target.follow)
                put("sway", target.sway?.let(::JsonPrimitive) ?: JsonNull)
                put("head", target.head)
                put("earlier_binding", target.earlierBinding)
                put("axes", JsonArray(target.activeTurnAxes.map { JsonPrimitive(it.raw) }))
                put("fitted_face", target.fittedFace?.let(::JsonPrimitive) ?: JsonNull)
                put("neutral_mesh", target.neutralMesh?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    })

    fun decode(context: ModifierAlgorithmContext): Input {
        val values = context.values
        require(values.getValue("schema").jsonPrimitive.content == "hair-legacy/1") { "Unknown legacy hair input schema" }
        val planes = values.getValue("planes").jsonObject.entries.associate { (key, value) ->
            val plane = HairLegacyMotion.Plane.valueOf(key)
            val data = value.jsonObject
            val pivot = data.getValue("pivot").tuple(2)
            plane to Source(HairLegacyMotion.Frame(data.getValue("hair").bounds(), data.getValue("head").bounds(),
                ScaffoldHead.Point(pivot[0], pivot[1]), data.getValue("chin_y").finite()),
                data.getValue("tuning").tuning(), data.getValue("defaults").tuning())
        }
        val names = values.getValue("parameters").jsonObject
        val parameters = HairLegacyMotion.Plane.entries.mapNotNull { plane ->
            names[plane.parameter.raw]?.jsonPrimitive?.content?.let { parameter(plane, it) }
        }
        require(names.size == parameters.size) { "Unknown legacy hair parameter" }
        val targets = values.getValue("targets").jsonObject.mapValues { (_, value) ->
            val target = value.jsonObject
            val plane = HairLegacyMotion.Plane.valueOf(target.getValue("plane").jsonPrimitive.content)
            require(plane in planes) { "Missing legacy hair plane measurements" }
            val axes = target.getValue("axes").jsonArray.mapTo(linkedSetOf()) { ParameterId(it.jsonPrimitive.content) }
            require(HairLegacyMotion.turnAxes.containsAll(axes)) { "Invalid legacy hair turn axes" }
            val sway = target.getValue("sway").jsonPrimitive.contentOrNull
            require(sway == null || plane.parameter.raw in names) { "Missing legacy hair sway parameter" }
            Target(plane, target.getValue("follow").jsonPrimitive.content, sway, target.getValue("head").jsonPrimitive.content,
                target.getValue("earlier_binding").jsonPrimitive.boolean, axes, target.getValue("fitted_face").jsonPrimitive.contentOrNull, target.getValue("neutral_mesh").jsonPrimitive.contentOrNull)
        }
        return Input(planes, targets, parameters, values.getValue("physics_folder").jsonPrimitive.content)
    }

    fun parameter(plane: HairLegacyMotion.Plane, name: String) = Parameter(plane.parameter.raw, name, -1f, 1f, 0f)

    fun meshIdentity(mesh: DrawableMesh): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val word = ByteBuffer.allocate(Int.SIZE_BYTES)
        fun number(bits: Int) { digest.update(word.putInt(0, bits).array()) }
        number(mesh.positions.size)
        for (value in mesh.positions) number(value.toRawBits())
        for (value in mesh.uvs) number(value.toRawBits())
        for (index in mesh.indices) number(index)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun JsonElement.finite() = jsonPrimitive.float.also { require(it.isFinite()) { "Non-finite legacy hair measurement" } }
    private fun JsonElement.tuple(size: Int) = jsonArray.map { it.finite() }.also { require(it.size == size) }
    private fun JsonElement.bounds() = tuple(4).let { Bounds(it[0], it[1], it[2], it[3]) }.also {
        require(it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f) { "Invalid legacy hair frame" }
    }
    private fun JsonElement.tuning() = tuple(6).let { HairLegacyMotion.Tuning(it[0], it[1], it[2], it[3], it[4], it[5]) }
    private fun HairLegacyMotion.Tuning.encode() = numbers(turn, tilt, perspective, sway, curl, roll)
    private fun Bounds.encode() = numbers(left, top, right, bottom)
    private fun numbers(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
}
