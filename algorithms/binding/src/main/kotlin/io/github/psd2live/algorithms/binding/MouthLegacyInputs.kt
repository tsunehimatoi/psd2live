package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import kotlinx.serialization.json.*

/** Aligned source pixels and neutral scaffold measurements for the legacy mouth. */
data class MouthLegacyInputs(
    val layers: Map<String, Layer>,
    val motion: MouthFittedInputs.Fallback,
    val teethFade: Float,
    val closedMouth: Boolean,
    val formName: String,
    val openName: String,
    val groupName: String,
    val scaffold: ModifierAlgorithmContext,
) {
    data class Layer(
        val canonical: String,
        val tag: String,
        val side: Side,
        val variant: Int?,
        val frame: Bounds,
        val aperture: Bounds,
        val bounds: Bounds,
        val preset: Boolean,
        val opacity: Float,
        val originalMask: String?,
        val maskSource: Boolean,
    ) {
        init {
            require(canonical.isNotBlank() && tag in TAGS && opacity.isFinite())
            require(!maskSource || tag in OPEN_TAGS)
            require(listOf(frame, aperture, bounds).all { box ->
                listOf(box.left, box.top, box.right, box.bottom, box.width, box.height).all(Float::isFinite) &&
                    box.width >= 0f && box.height >= 0f
            })
        }
    }

    init {
        require(teethFade.isFinite() && teethFade in 0f..100f)
        require(layers.size <= 20_000 && layers.keys.none(String::isBlank))
        require(scaffold.values["schema"] == JsonPrimitive("scaffold/5") && "template" !in scaffold.resources)
        require(!motion.exactClose) { "Exact closure belongs to the outline modifier" }
    }

    fun encode(): ModifierAlgorithmContext {
        val resources = scaffold.resources.values.associateBy(ModifierAlgorithmResource::digest)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", SCHEMA)
            put("scaffold", scaffold.values)
            put("resources", JsonObject(scaffold.resources.mapValues { JsonPrimitive(it.value.digest) }))
            put("curve", JsonArray(motion.curve.points.map { floats(it.x, it.y) }))
            put("motion", floats(motion.closedNarrow, motion.formWiden, motion.seam, motion.smileBase, motion.smile))
            put("fade", teethFade)
            put("closedMouth", closedMouth)
            put("formName", formName)
            put("openName", openName)
            put("groupName", groupName)
            put("layers", JsonArray(layers.map { (id, layer) -> buildJsonObject {
                put("id", id)
                put("canonical", layer.canonical)
                put("tag", layer.tag)
                put("side", layer.side.name)
                put("variant", layer.variant?.let(::JsonPrimitive) ?: JsonNull)
                put("frame", layer.frame.encode())
                put("aperture", layer.aperture.encode())
                put("bounds", layer.bounds.encode())
                put("preset", layer.preset)
                put("opacity", layer.opacity)
                put("originalMask", layer.originalMask?.let(::JsonPrimitive) ?: JsonNull)
                put("maskSource", layer.maskSource)
            } }))
        }, resources)
    }

    companion object {
        const val SCHEMA = "mouth-legacy/1"
        internal val OPEN_TAGS = setOf("MOUTH", "MOUTH_OPEN")
        internal val COMPONENTS = setOf("TOOTH_T", "TOOTH_B", "TONGUE")
        private val TAGS = OPEN_TAGS + COMPONENTS + "MOUTH_CLOSE"

        fun decode(context: ModifierAlgorithmContext): MouthLegacyInputs {
            val value = context.values
            require(value.keys == setOf("schema", "scaffold", "resources", "curve", "motion", "fade",
                "closedMouth", "formName", "openName", "groupName", "layers") &&
                value["schema"] == JsonPrimitive(SCHEMA)) { "Invalid legacy mouth input" }
            val measurements = value.getValue("resources").jsonObject.mapValues { (_, digest) ->
                context.resources.getValue(digest.jsonPrimitive.content).also {
                    require(it.digest == digest.jsonPrimitive.content) { "Invalid legacy mouth resource digest" }
                }
            }
            val scaffold = ModifierAlgorithmContext(value.getValue("scaffold").jsonObject, measurements)
            val curve = MouthCurve(value.getValue("curve").jsonArray.map { point ->
                val (x, y) = point.numbers(2)
                MouthCurvePoint(x, y)
            })
            val tuning = value.getValue("motion").numbers(5)
            val records = value.getValue("layers").jsonArray
            val layers = records.associate { record ->
                val layer = record.jsonObject
                require(layer.keys == setOf("id", "canonical", "tag", "side", "variant", "frame",
                    "aperture", "bounds", "preset", "opacity", "originalMask", "maskSource"))
                layer.text("id") to Layer(layer.text("canonical"), layer.text("tag"), Side.valueOf(layer.text("side")),
                    layer.getValue("variant").jsonPrimitive.intOrNull, layer.getValue("frame").box(),
                    layer.getValue("aperture").box(), layer.getValue("bounds").box(), layer.getValue("preset").jsonPrimitive.boolean,
                    layer.getValue("opacity").jsonPrimitive.float, layer.getValue("originalMask").jsonPrimitive.contentOrNull,
                    layer.getValue("maskSource").jsonPrimitive.boolean)
            }
            require(layers.size == records.size) { "Duplicate legacy mouth layer" }
            return MouthLegacyInputs(layers, MouthFittedInputs.Fallback(curve, false, tuning[0], tuning[1], tuning[2],
                tuning[3], tuning[4]), value.getValue("fade").jsonPrimitive.float, value.getValue("closedMouth").jsonPrimitive.boolean,
                value.text("formName"), value.text("openName"), value.text("groupName"), scaffold)
        }

        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonElement.numbers(count: Int) = jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == count && it.all(Float::isFinite))
        }
        private fun JsonElement.box() = numbers(4).let { Bounds(it[0], it[1], it[2], it[3]) }
        private fun floats(vararg values: Float) = JsonArray(values.map(::JsonPrimitive))
        private fun Bounds.encode() = floats(left, top, right, bottom)
    }
}
