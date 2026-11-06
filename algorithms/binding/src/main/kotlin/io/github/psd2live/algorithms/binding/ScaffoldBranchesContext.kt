package io.github.psd2live.algorithms.binding

import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId

/** Source inputs for the paired and hanging-arm branches of the legacy scaffold. */
object ScaffoldBranchesContext {
    private const val SCHEMA = "scaffold_branches/1"
    private const val LABEL_ARGUMENT = "__scaffold_layer_name__"

    data class Layer(
        val id: String, val name: String, val tag: String, val side: Side, val bounds: Bounds, val slot: ScaffoldParts.Slot,
    )

    data class Result(val deformers: List<Deformer.Warp>, val parents: Map<String, ScaffoldArms.Parent>,
                      val armFrames: Map<DeformerId, Bounds>)

    fun capture(
        layers: List<Layer>, body: Bounds, legs: Bounds?, head: ScaffoldHead.Input,
        pairLabel: (String) -> String, armLabel: (String) -> String,
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("body", encodeBounds(body))
        put("legs", legs?.let(::encodeBounds) ?: JsonNull)
        put("head", encodeBounds(head.headFrame))
        put("face", encodeBounds(head.faceFrame))
        put("front_hair", encodeHair(head.frontHair))
        put("back_hair", encodeHair(head.backHair))
        put("pair_label", captureLabel(pairLabel))
        put("arm_label", captureLabel(armLabel))
        put("regions", buildJsonArray {
            for (region in head.regions) add(buildJsonObject {
                put("feature", region.feature.name)
                put("side", region.side.name)
                put("bounds", encodeBounds(region.bounds))
            })
        })
        put("layers", buildJsonArray {
            for (layer in layers) add(buildJsonObject {
                put("id", layer.id)
                put("name", layer.name)
                put("tag", layer.tag)
                put("side", layer.side.name)
                put("bounds", encodeBounds(layer.bounds))
                put("slot", layer.slot.name)
            })
        })
    }

    fun build(value: JsonObject, base: List<Deformer>, checkpoint: () -> Unit = {}): Result {
        checkpoint()
        val input = fields(value, "schema", "body", "legs", "head", "face", "front_hair", "back_hair", "pair_label", "arm_label",
            "regions", "layers")
        require(string(input.getValue("schema")) == SCHEMA) { "Unknown scaffold branches schema" }
        val body = readBounds(input.getValue("body"))
        val head = readBounds(input.getValue("head"))
        val face = readBounds(input.getValue("face"))
        val legs = optional(input.getValue("legs"), ::readBounds)
        val frontHair = readHair(input.getValue("front_hair"), "Front")
        val backHair = readHair(input.getValue("back_hair"), "Back")
        val regions = array(input.getValue("regions")).map { encoded ->
            checkpoint()
            val region = fields(encoded, "feature", "side", "bounds")
            Region(enum(region.getValue("feature")), enum(region.getValue("side")), readBounds(region.getValue("bounds")))
        }
        val ids = HashSet<String>()
        val layers = array(input.getValue("layers")).map { encoded ->
            checkpoint()
            val layer = fields(encoded, "id", "name", "tag", "side", "bounds", "slot")
            val id = string(layer.getValue("id"))
            require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate scaffold layer ID" }
            Layer(id, string(layer.getValue("name")), string(layer.getValue("tag")), enum(layer.getValue("side")),
                readBounds(layer.getValue("bounds")), enum(layer.getValue("slot")))
        }
        val pairLabel = readLabel(input.getValue("pair_label"))
        val armLabel = readLabel(input.getValue("arm_label"))
        val removedParents = listOfNotNull(frontHair, backHair).filter { !it.sway }.associate { it.physics to it.follow }
        val available = base.mapTo(HashSet()) { it.id }
        val branches = mutableListOf<Deformer.Warp>()
        val parents = linkedMapOf<String, ScaffoldArms.Parent>()

        fun regionFor(layer: Layer): Region? {
            val feature = featureFor(layer.tag) ?: return null
            val candidates = regions.filter { it.feature == feature }
            return candidates.firstOrNull { it.side == layer.side } ?: candidates.firstOrNull { it.side == Side.NONE }
                ?: if (layer.side == Side.NONE && candidates.isNotEmpty()) {
                    Region(feature, Side.NONE, candidates.map { it.bounds }.reduce(Bounds::union))
                } else null
        }

        fun defaultParent(layer: Layer, region: Region?): ScaffoldArms.Parent {
            val hair = when (layer.tag) {
                "FRONT_HAIR" -> frontHair
                "BACK_HAIR" -> backHair
                else -> null
            }
            return when {
                layer.tag == "FACE" -> ScaffoldArms.Parent(ScaffoldHead.contourId, face)
                region != null -> {
                    val id = if (region.feature == ScaffoldHead.Feature.IRIS) ScaffoldHead.gazeId(region.side)
                        else ScaffoldHead.featureId(region.feature, region.side)
                    ScaffoldArms.Parent(id, region.bounds)
                }
                hair != null -> ScaffoldArms.Parent(hair.physics, hair.bounds)
                layer.tag in setOf("LEGWEAR", "FOOTWEAR") && legs != null -> ScaffoldArms.Parent(ScaffoldBody.legsId, legs)
                layer.slot == ScaffoldParts.Slot.FACE -> ScaffoldArms.Parent(ScaffoldHead.faceId, face)
                layer.slot in setOf(ScaffoldParts.Slot.FRONT_HAIR, ScaffoldParts.Slot.BACK_HAIR, ScaffoldParts.Slot.ACCESSORIES) ->
                    ScaffoldArms.Parent(ScaffoldHead.headId, head)
                else -> ScaffoldArms.Parent(ScaffoldHead.breathId, body)
            }
        }

        val candidates = buildList {
            for (layer in layers) {
                checkpoint()
                val region = regionFor(layer)
                val parent = defaultParent(layer, region)
                parents[layer.id] = parent.copy(id = removedParents[parent.id] ?: parent.id)
                if (layer.side != Side.NONE && region == null) {
                    add(ScaffoldPairs.Layer(layer.id, layer.name, layer.tag, layer.side, layer.bounds,
                        parent.id, parent.frame, layer.slot.id))
                }
            }
        }
        val pairs = ScaffoldPairs.build(candidates, available.mapTo(HashSet()) { it.raw }, pairLabel, checkpoint)
        for (pair in pairs) {
            checkpoint()
            val parent = pair.warp.parent
            val warp = pair.warp.copy(parent = parent?.let { removedParents[it] ?: it })
            branches += warp
            available += warp.id
            for (id in pair.layerIds) parents[id] = ScaffoldArms.Parent(warp.id, pair.bounds)
        }
        val arms = layers.filter { it.tag == "HANDWEAR" && it.side != Side.NONE }.map {
            ScaffoldArms.Layer(it.id, it.name, it.side, it.bounds, parents.getValue(it.id))
        }
        val hanging = ScaffoldArms.build(arms, available, ScaffoldParts.Slot.BODY.id, armLabel, checkpoint)
        for (arm in hanging) {
            branches += arm.warp
            for (id in arm.layerIds) parents[id] = ScaffoldArms.Parent(arm.warp.id, arm.bounds)
        }
        checkpoint()
        return Result(branches, parents, hanging.associate { it.warp.id to it.bounds })
    }

    private data class Region(val feature: ScaffoldHead.Feature, val side: Side, val bounds: Bounds)
    private data class Hair(val bounds: Bounds, val sway: Boolean, val physics: DeformerId, val follow: DeformerId)

    private fun featureFor(tag: String): ScaffoldHead.Feature? = when (tag) {
        "IRIDES" -> ScaffoldHead.Feature.IRIS
        "EYEWHITE", "EYELASH", "EYE_CLOSE" -> ScaffoldHead.Feature.EYE
        "EYEBROW" -> ScaffoldHead.Feature.BROW
        "NOSE" -> ScaffoldHead.Feature.NOSE
        "EARS", "EARWEAR" -> ScaffoldHead.Feature.EAR
        "MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE", "TOOTH_T", "TOOTH_B", "TONGUE" -> ScaffoldHead.Feature.MOUTH
        else -> null
    }

    private fun captureLabel(label: (String) -> String): JsonArray {
        val formatted = label(LABEL_ARGUMENT)
        val first = formatted.indexOf(LABEL_ARGUMENT)
        require(first >= 0 && formatted.lastIndexOf(LABEL_ARGUMENT) == first) { "A scaffold label needs one layer-name argument" }
        return JsonArray(listOf(JsonPrimitive(formatted.substring(0, first)),
            JsonPrimitive(formatted.substring(first + LABEL_ARGUMENT.length))))
    }

    private fun readLabel(value: JsonElement): (String) -> String {
        val fields = array(value)
        require(fields.size == 2) { "Invalid scaffold branch label" }
        val prefix = string(fields[0])
        val suffix = string(fields[1])
        return { prefix + it + suffix }
    }

    private fun encodeHair(value: ScaffoldHead.Hair?): JsonElement = value?.let {
        buildJsonObject {
            put("bounds", encodeBounds(it.bounds))
            put("sway", it.sway)
        }
    } ?: JsonNull

    private fun readHair(value: JsonElement, side: String): Hair? = optional(value) {
        val hair = fields(it, "bounds", "sway")
        val sway = requireNotNull(literal(hair.getValue("sway")).booleanOrNull) { "Invalid scaffold hair sway" }
        Hair(readBounds(hair.getValue("bounds")), sway, DeformerId("DeformHair${side}Physics"), DeformerId("DeformHair${side}Follow"))
    }

    private fun encodeBounds(value: Bounds): JsonArray = validateBounds(value).let {
        JsonArray(listOf(it.left, it.top, it.right, it.bottom).map(::JsonPrimitive))
    }

    private fun readBounds(value: JsonElement): Bounds {
        val coordinates = array(value)
        require(coordinates.size == 4) { "Invalid scaffold branch coordinate count" }
        val (left, top, right, bottom) = coordinates.map {
            requireNotNull(literal(it).floatOrNull?.takeIf(Float::isFinite)) { "Invalid scaffold branch coordinate" }
        }
        return validateBounds(Bounds(left, top, right, bottom))
    }

    private fun validateBounds(value: Bounds): Bounds = value.also {
        require(it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f) {
            "Invalid scaffold branch bounds"
        }
    }

    private fun fields(value: JsonElement, vararg keys: String): JsonObject {
        require(value is JsonObject && value.keys == keys.toSet()) { "Invalid scaffold branch fields" }
        return value
    }

    private fun array(value: JsonElement) = requireNotNull(value as? JsonArray) { "Expected scaffold branch array" }
    private fun string(value: JsonElement) =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }) { "Expected scaffold branch text" }.content
    private fun literal(value: JsonElement) =
        requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }) { "Expected scaffold branch literal" }
    private inline fun <reified T : Enum<T>> enum(value: JsonElement): T = enumValueOf<T>(string(value))
    private inline fun <T> optional(value: JsonElement, read: (JsonElement) -> T): T? =
        if (value == JsonNull) null else read(value)
}
