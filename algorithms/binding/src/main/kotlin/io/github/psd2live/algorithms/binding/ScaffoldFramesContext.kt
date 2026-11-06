package io.github.psd2live.algorithms.binding

import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId

/** Captures source measurements for the default body and head branches. */
object ScaffoldFramesContext {
    private const val SCHEMA = "scaffold_frames/1"
    data class Input(
        val body: Bounds,
        val standing: ScaffoldBody.Standing?,
        val head: ScaffoldHead.Input,
        val labels: Map<String, String>,
    )
    private val ownedIds = setOf(
        ScaffoldBody.bodyId, ScaffoldBody.leanId, ScaffoldBody.legsId,
        ScaffoldHead.breathId, ScaffoldHead.rotationId, ScaffoldHead.headId,
        ScaffoldHead.faceId, ScaffoldHead.contourId, ScaffoldHead.displacementId,
    ) + listOf("Eyes", "Brows", "Ears", "HairFrontFollow", "HairFrontPhysics", "HairBackFollow", "HairBackPhysics")
        .map { DeformerId("Deform$it") } +
        ScaffoldHead.Feature.entries.flatMap { feature -> Side.entries.map { ScaffoldHead.featureId(feature, it) } } +
        Side.entries.map(ScaffoldHead::gazeId)

    fun owns(id: DeformerId): Boolean = id in ownedIds

    fun capture(
        body: Bounds, standing: ScaffoldBody.Standing?, head: ScaffoldHead.Input, label: (String) -> String,
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("body", bounds(body))
        put("standing", standing?.let { source ->
            buildJsonObject {
                put("frame", bounds(source.frame))
                put("knees_in_rest", number(source.kneesInRest))
                put("legs", buildJsonArray {
                    for (leg in source.legs) add(buildJsonObject {
                        put("hip", joint(leg.hip))
                        put("knee", joint(leg.knee))
                        put("ankle", joint(leg.ankle))
                        put("floor_y", number(leg.floorY))
                    })
                })
            }
        } ?: JsonNull)
        put("head", buildJsonObject {
            put("body", bounds(head.bodyFrame))
            put("head", bounds(head.headFrame))
            put("face", bounds(head.faceFrame))
            put("aligned_pivot", point(head.alignedPivot))
            put("canvas_pivot", point(head.canvasPivot))
            put("initial_angle", number(head.initialAngle))
            put("feature_displacement", head.featureDisplacement)
            put("front_hair", hair(head.frontHair))
            put("back_hair", hair(head.backHair))
            put("regions", buildJsonArray {
                for (region in head.regions) add(buildJsonObject {
                    put("feature", region.feature.name)
                    put("side", region.side.name)
                    put("bounds", bounds(region.bounds))
                    put("name", region.name)
                    put("gaze_name", region.gazeName?.let(::JsonPrimitive) ?: JsonNull)
                })
            })
        })
        put("labels", buildJsonObject {
            for (key in labelKeys(standing, head)) put(key, label(key))
        })
    }

    fun decode(value: JsonObject, checkpoint: () -> Unit = {}): Input {
        checkpoint()
        val input = fields(value, "schema", "body", "standing", "head", "labels")
        require(string(input.getValue("schema")) == SCHEMA) { "Unknown scaffold frames schema" }
        val body = readBounds(input.getValue("body"))
        val standing = optional(input.getValue("standing")) {
            val source = fields(it, "frame", "knees_in_rest", "legs")
            val legs = array(source.getValue("legs")).map { encoded ->
                checkpoint()
                val leg = fields(encoded, "hip", "knee", "ankle", "floor_y")
                ScaffoldBody.Leg(
                    readJoint(leg.getValue("hip")), readJoint(leg.getValue("knee")), readJoint(leg.getValue("ankle")),
                    double(leg.getValue("floor_y")),
                )
            }
            ScaffoldBody.Standing(readBounds(source.getValue("frame")), legs, double(source.getValue("knees_in_rest")))
        }
        val source = fields(input.getValue("head"), "body", "head", "face", "aligned_pivot", "canvas_pivot", "initial_angle",
            "feature_displacement", "front_hair", "back_hair", "regions")
        val regions = array(source.getValue("regions")).map { encoded ->
            checkpoint()
            val region = fields(encoded, "feature", "side", "bounds", "name", "gaze_name")
            ScaffoldHead.Region(
                enumValue<ScaffoldHead.Feature>(region.getValue("feature")), enumValue<Side>(region.getValue("side")),
                readBounds(region.getValue("bounds")), string(region.getValue("name")),
                optional(region.getValue("gaze_name"), ::string),
            )
        }
        val head = ScaffoldHead.Input(
            readBounds(source.getValue("body")), readBounds(source.getValue("head")), readBounds(source.getValue("face")),
            readPoint(source.getValue("aligned_pivot")), readPoint(source.getValue("canvas_pivot")),
            float(source.getValue("initial_angle")), boolean(source.getValue("feature_displacement")), regions,
            readHair(source.getValue("front_hair")), readHair(source.getValue("back_hair")),
        )
        val labels = fields(input.getValue("labels"), *labelKeys(standing, head).toTypedArray()).mapValues { string(it.value) }
        checkpoint()
        return Input(body, standing, head, labels)
    }

    fun build(value: JsonObject, checkpoint: () -> Unit = {}): List<Deformer> {
        val input = decode(value, checkpoint)
        val label: (String) -> String = { input.labels.getValue(it) }
        val deformers = ScaffoldBody.build(input.body, input.standing, label, checkpoint) +
            ScaffoldHead.build(input.head, label, checkpoint).deformers
        checkpoint()
        return deformers
    }

    private fun labelKeys(standing: ScaffoldBody.Standing?, head: ScaffoldHead.Input): List<String> = buildList {
        if (standing != null) add("legs")
        addAll(listOf("body", "bodyLean", "breath", "headRotation", "headContainer", "face", "faceContour"))
        if (head.featureDisplacement) add("featureDisplacement")
        for ((feature, key) in listOf(ScaffoldHead.Feature.EYE to "eyes", ScaffoldHead.Feature.BROW to "brows",
            ScaffoldHead.Feature.EAR to "ears")) {
            if (head.regions.any { it.feature == feature && it.side == Side.LEFT } &&
                head.regions.any { it.feature == feature && it.side == Side.RIGHT }) add(key)
        }
        for ((hair, key) in listOf(head.frontHair to "frontHair", head.backHair to "backHair")) {
            if (hair == null) continue
            add("${key}Follow")
            if (hair.sway) add("${key}Physics")
        }
    }.map { "model.deformer.$it" }

    private fun fields(value: JsonElement, vararg names: String): JsonObject {
        require(value is JsonObject && value.keys == names.toSet()) { "Invalid scaffold frames object fields" }
        return value
    }

    private fun array(value: JsonElement): JsonArray =
        requireNotNull(value as? JsonArray) { "Expected a scaffold frames array" }

    private fun tuple(value: JsonElement, size: Int): JsonArray = array(value).also {
        require(it.size == size) { "Invalid scaffold frames coordinate count" }
    }

    private inline fun <T> optional(value: JsonElement, read: (JsonElement) -> T): T? =
        if (value == JsonNull) null else read(value)

    private fun string(value: JsonElement): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }) { "Expected scaffold frames text" }.content

    private inline fun <reified T : Enum<T>> enumValue(value: JsonElement): T = enumValueOf<T>(string(value))

    private fun literal(value: JsonElement): JsonPrimitive =
        requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }) { "Expected scaffold frames literal" }

    private fun boolean(value: JsonElement): Boolean =
        requireNotNull(literal(value).booleanOrNull) { "Expected scaffold frames boolean" }

    private fun float(value: JsonElement): Float =
        requireNotNull(literal(value).floatOrNull?.takeIf(Float::isFinite)) { "Expected finite scaffold frames coordinate" }

    private fun double(value: JsonElement): Double =
        requireNotNull(literal(value).doubleOrNull?.takeIf(Double::isFinite)) { "Expected finite scaffold frames coordinate" }

    private fun number(value: Float): JsonPrimitive {
        require(value.isFinite()) { "Non-finite scaffold frames coordinate" }
        return JsonPrimitive(value)
    }

    private fun number(value: Double): JsonPrimitive {
        require(value.isFinite()) { "Non-finite scaffold frames coordinate" }
        return JsonPrimitive(value)
    }

    private fun validateBounds(value: Bounds): Bounds = value.also {
        require(it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f) { "Invalid scaffold frames bounds" }
    }

    private fun bounds(value: Bounds): JsonArray = validateBounds(value).let {
        JsonArray(listOf(number(it.left), number(it.top), number(it.right), number(it.bottom)))
    }

    private fun readBounds(value: JsonElement): Bounds = tuple(value, 4).let {
        validateBounds(Bounds(float(it[0]), float(it[1]), float(it[2]), float(it[3])))
    }

    private fun point(value: ScaffoldHead.Point) = JsonArray(listOf(number(value.x), number(value.y)))
    private fun readPoint(value: JsonElement) = tuple(value, 2).let { ScaffoldHead.Point(float(it[0]), float(it[1])) }
    private fun joint(value: ScaffoldBody.Joint) = JsonArray(listOf(number(value.x), number(value.y)))
    private fun readJoint(value: JsonElement) = tuple(value, 2).let { ScaffoldBody.Joint(double(it[0]), double(it[1])) }

    private fun hair(value: ScaffoldHead.Hair?): JsonElement = value?.let {
        buildJsonObject {
            put("bounds", bounds(it.bounds))
            put("sway", it.sway)
        }
    } ?: JsonNull

    private fun readHair(value: JsonElement): ScaffoldHead.Hair? = optional(value) {
        val hair = fields(it, "bounds", "sway")
        ScaffoldHead.Hair(readBounds(hair.getValue("bounds")), boolean(hair.getValue("sway")))
    }
}
