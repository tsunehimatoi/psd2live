package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.HexFormat

/** Source fit, artwork and fingerprints for the supported native parent frames. */
data class BrowSpringInputs(
    val names: Map<Side, String>,
    val physicsNames: Map<Side, String>,
    val group: String,
    val targets: Map<String, Target>,
    val eyeProvider: String?,
    val shapeProvider: String?,
) {
    data class Eye(val opening: Float, val width: Float)
    data class Target(
        val fit: BrowFit,
        val eye: Eye?,
        val frame: Bounds,
        val raw: ScaffoldMesh.RawMesh,
        val branch: List<String>,
        val order: Float,
        val opacity: Float,
    )

    fun encode(): ModifierAlgorithmContext {
        val meshes = ScaffoldMeshContext.capture(targets.map { (id, target) ->
            ScaffoldMeshContext.Material(id, id, target.raw, ScaffoldMesh.Geometry.Zero)
        })
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            putJsonObject("names") { names.forEach { (side, name) -> put(side.name, name) } }
            putJsonObject("physics_names") { physicsNames.forEach { (side, name) -> put(side.name, name) } }
            put("group", group)
            put("eye", eyeProvider?.let(::JsonPrimitive) ?: JsonNull)
            put("shape", shapeProvider?.let(::JsonPrimitive) ?: JsonNull)
            putJsonObject("targets") {
                targets.forEach { (id, target) -> putJsonObject(id) {
                    val (fit, eye, frame, _, branch, order, opacity) = target
                    put("side", fit.side.name)
                    put("fit", numbers(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length, *fit.centre.toFloatArray()))
                    put("eye", eye?.let { numbers(it.opening, it.width) } ?: JsonNull)
                    put("frame", numbers(frame.left, frame.top, frame.right, frame.bottom))
                    put("branch", JsonArray(branch.map(::JsonPrimitive)))
                    put("drawing", numbers(order, opacity))
                } }
            }
        }, meshes.resources)
    }

    companion object {
        private const val SCHEMA = "brow-spring/1"
        private const val VERTICES = "source_meshes"

        fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): BrowSpringInputs {
            val values = context.values
            require(values.keys == setOf("schema", "names", "physics_names", "group", "targets", "eye", "shape") &&
                values["schema"] == JsonPrimitive(SCHEMA) && context.resources.keys == setOf(VERTICES)) {
                "Missing brow spring source inputs"
            }
            val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(VERTICES), checkpoint)
            val targets = values.getValue("targets").jsonObject
            require(raw.keys == targets.keys)
            fun names(key: String) = values.getValue(key).jsonObject.mapKeys { Side.valueOf(it.key) }
                .mapValues { it.value.jsonPrimitive.content }
            val names = names("names")
            val physicsNames = names("physics_names")
            require((names.isEmpty() || names.keys == setOf(Side.LEFT, Side.RIGHT)) &&
                physicsNames.keys == setOf(Side.LEFT, Side.RIGHT))
            return BrowSpringInputs(names, physicsNames, values.getValue("group").jsonPrimitive.content,
                targets.mapValues { (id, encoded) ->
                    checkpoint()
                    val target = encoded.jsonObject
                    require(target.keys == setOf("side", "fit", "eye", "frame", "branch", "drawing"))
                    val side = Side.valueOf(target.getValue("side").jsonPrimitive.content)
                    val fit = floats(target.getValue("fit"), 5 + BrowFit.SAMPLES)
                    require(side != Side.NONE && fit[4] > 0f)
                    val eye = target.getValue("eye").takeUnless { it == JsonNull }?.let {
                        val (opening, width) = floats(it, 2)
                        require(opening >= 0f && width > 0f)
                        Eye(opening, width)
                    }
                    val (left, top, right, bottom) = floats(target.getValue("frame"), 4)
                    val (order, opacity) = floats(target.getValue("drawing"), 2)
                    require(right > left && bottom > top && opacity in 0f..1f)
                    Target(BrowFit(side, fit[0], fit[1], fit[2], fit[3], fit[4], fit.drop(5)), eye,
                        Bounds(left, top, right, bottom), raw.getValue(id),
                        target.getValue("branch").jsonArray.map { it.jsonPrimitive.content }, order, opacity)
                }, values.getValue("eye").jsonPrimitive.contentOrNull, values.getValue("shape").jsonPrimitive.contentOrNull)
        }

        /** Part ownership is independent of the parent frame. */
        fun fingerprint(deformer: Deformer): String {
            val frame = when (deformer) {
                is Deformer.Warp -> deformer.copy(part = null)
                is Deformer.Rotation -> deformer.copy(part = null)
            }
            val encoded = RigIrBinary.encode(RigIR(Canvas(0f, 0f), emptyList(), deformers = listOf(frame)))
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))
        }

        private fun numbers(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))

        private fun floats(value: JsonElement, size: Int) = value.jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == size && it.all(Float::isFinite)) { "Invalid brow spring measurement" }
        }
    }
}
