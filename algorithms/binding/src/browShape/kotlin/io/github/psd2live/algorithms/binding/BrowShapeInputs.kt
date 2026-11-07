package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*

/** Artwork measurements and source topology, in the fitted brow's aligned canvas frame. */
data class BrowShapeInputs(val names: List<String>, val groupName: String, val targets: Map<String, Target>, val eyeProvider: String?) {
    data class Eye(val opening: Float, val width: Float) {
        init { require(opening.isFinite() && width.isFinite() && width > 0f) }
    }

    data class Target(
        val fit: BrowFit, val eye: Eye?, val parent: String?, val frame: Bounds, val raw: ScaffoldMesh.RawMesh,
    ) {
        init {
            require(fit.side != Side.NONE && fit.length > 0f && fit.centre.size == BrowFit.SAMPLES &&
                (fit.centre + listOf(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length)).all(Float::isFinite))
            require(listOf(frame.left, frame.top, frame.right, frame.bottom).all(Float::isFinite) &&
                frame.width > 0f && frame.height > 0f)
        }
    }

    init {
        require(names.size == 6 || names.isEmpty() && targets.isEmpty())
        require(eyeProvider != null || targets.values.none { it.eye != null })
    }

    fun encode(): ModifierAlgorithmContext {
        val vertices = ScaffoldMeshContext.capture(targets.map { (id, target) ->
            ScaffoldMeshContext.Material(id, id, target.raw, ScaffoldMesh.Geometry.Zero)
        })
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("names", JsonArray(names.map(::JsonPrimitive)))
            put("group", groupName)
            put("eye_provider", eyeProvider?.let(::JsonPrimitive) ?: JsonNull)
            putJsonObject("targets") {
                for ((id, target) in targets) putJsonObject(id) {
                    val fit = target.fit
                    val frame = target.frame
                    put("side", fit.side.name)
                    put("fit", numbers(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.length, *fit.centre.toFloatArray()))
                    put("eye", target.eye?.let { numbers(it.opening, it.width) } ?: JsonNull)
                    put("parent", target.parent?.let(::JsonPrimitive) ?: JsonNull)
                    put("frame", numbers(frame.left, frame.top, frame.right, frame.bottom))
                }
            }
        }, vertices.resources)
    }

    companion object {
        private const val SCHEMA = "brow-shape/1"
        private const val VERTICES = "source_meshes"

        fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): BrowShapeInputs {
            val values = context.values
            require(values.keys == setOf("schema", "names", "group", "targets", "eye_provider") && values["schema"] == JsonPrimitive(SCHEMA) &&
                context.resources.keys == setOf(VERTICES)) { "Missing brow shape source inputs" }
            val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(VERTICES), checkpoint)
            val targets = values.getValue("targets").jsonObject
            require(targets.keys == raw.keys)
            return BrowShapeInputs(values.getValue("names").jsonArray.map { it.jsonPrimitive.content },
                values.getValue("group").jsonPrimitive.content, targets.mapValues { (id, entry) ->
                    checkpoint()
                    val target = entry.jsonObject
                    require(target.keys == setOf("side", "fit", "eye", "parent", "frame"))
                    val fit = floats(target.getValue("fit"), 5 + BrowFit.SAMPLES)
                    val side = Side.valueOf(target.getValue("side").jsonPrimitive.content)
                    val eye = target.getValue("eye").takeUnless { it == JsonNull }?.let {
                        val (opening, width) = floats(it, 2)
                        Eye(opening, width)
                    }
                    val (left, top, right, bottom) = floats(target.getValue("frame"), 4)
                    Target(BrowFit(side, fit[0], fit[1], fit[2], fit[3], fit[4], fit.drop(5)), eye,
                        target.getValue("parent").jsonPrimitive.contentOrNull, Bounds(left, top, right, bottom), raw.getValue(id))
                }, values.getValue("eye_provider").jsonPrimitive.contentOrNull)
        }

        private fun numbers(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun floats(value: JsonElement, size: Int) = value.jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == size && it.all(Float::isFinite)) { "Invalid brow shape measurement" }
        }
    }
}
