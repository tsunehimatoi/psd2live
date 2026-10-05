package io.github.psd2live.core

import io.github.psd2live.core.quality.*

import kotlinx.serialization.json.*

/** How a parameter moves a pendulum's root, or how a pendulum vertex writes a parameter. */
enum class PhysicsSourceType(val jsonName: String) {
    /** Sideways travel of the root; for an output, the vertex's sideways offset from the one above. */
    X("X"),
    /** Tilt of gravity about the root; for an output, the segment's angle against the one above. */
    ANGLE("Angle");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown physics type: $text (x or angle)")
    }
}

/** One parameter feeding a pendulum. [weight] is a percentage of the normalized range. */
data class PhysicsInput(
    val parameter: String,
    val weight: Float = 100f,
    val type: PhysicsSourceType = PhysicsSourceType.ANGLE,
    val reflect: Boolean = false,
) {
    init {
        require(parameter.isNotBlank() && parameter.none(Char::isISOControl)) { "Physics input parameter is required" }
        require(weight.isFinite() && weight in 0f..100f) { "Physics input weight must be within 0..100" }
    }

    fun toJson() = buildJsonObject {
        put("parameter", parameter); put("weight", weight); put("type", type.name.lowercase())
        if (reflect) put("reflect", true)
    }

    companion object {
        fun fromJson(o: JsonObject) = PhysicsInput(o.text("parameter"), o.number("weight", 100f),
            o.string("type")?.let(PhysicsSourceType::parse) ?: PhysicsSourceType.ANGLE, o.flag("reflect"))
    }
}

/** A parameter written from vertex [vertex] (1 = the first segment's tip) of a pendulum. */
data class PhysicsOutput(
    val parameter: String,
    val vertex: Int = 1,
    val scale: Float = 1f,
    val weight: Float = 100f,
    val type: PhysicsSourceType = PhysicsSourceType.ANGLE,
    val reflect: Boolean = false,
) {
    init {
        require(parameter.isNotBlank() && parameter.none(Char::isISOControl)) { "Physics output parameter is required" }
        require(vertex >= 1) { "Physics output vertex starts at 1" }
        require(scale.isFinite() && weight.isFinite() && weight in 0f..100f) { "Physics output scale/weight out of range" }
    }

    fun toJson() = buildJsonObject {
        put("parameter", parameter); put("vertex", vertex); put("scale", scale)
        if (weight != 100f) put("weight", weight)
        if (type != PhysicsSourceType.ANGLE) put("type", type.name.lowercase())
        if (reflect) put("reflect", true)
    }

    companion object {
        fun fromJson(o: JsonObject) = PhysicsOutput(o.text("parameter"), o["vertex"]?.jsonPrimitive?.int ?: 1,
            o.number("scale", 1f), o.number("weight", 100f),
            o.string("type")?.let(PhysicsSourceType::parse) ?: PhysicsSourceType.ANGLE, o.flag("reflect"))
    }
}

/**
 * One rod of the strand and the particle at its end. Cubism calls [mobility] "shakiness", [delay] the
 * reaction speed and [acceleration] how fast it settles.
 */
data class PhysicsSegment(
    val length: Float = 10f,
    val mobility: Float = 0.9f,
    val delay: Float = 0.9f,
    val acceleration: Float = 1.2f,
) {
    init {
        require(listOf(length, mobility, delay, acceleration).all(Float::isFinite)) { "Physics segment values must be finite" }
        require(length > 0f && mobility in 0f..1f && delay > 0f && acceleration >= 0f) {
            "Physics segment out of range: length > 0, mobility 0..1, delay > 0, acceleration >= 0"
        }
    }

    fun toJson() = buildJsonObject {
        put("length", length); put("mobility", mobility); put("delay", delay); put("acceleration", acceleration)
    }

    companion object {
        fun fromJson(o: JsonObject, base: PhysicsSegment = PhysicsSegment()) = PhysicsSegment(o.number("length", base.length),
            o.number("mobility", base.mobility), o.number("delay", base.delay), o.number("acceleration", base.acceleration))
    }
}

/** The span a full-range input maps to: root travel for X inputs, degrees of gravity tilt for Angle ones. */
data class PhysicsNormalization(
    val positionMin: Float = -10f,
    val positionDefault: Float = 0f,
    val positionMax: Float = 10f,
    val angleMin: Float = -10f,
    val angleDefault: Float = 0f,
    val angleMax: Float = 10f,
) {
    init {
        require(listOf(positionMin, positionDefault, positionMax, angleMin, angleDefault, angleMax).all(Float::isFinite))
        require(positionMin < positionMax && positionDefault in positionMin..positionMax) { "Position normalization needs min < default < max" }
        require(angleMin < angleMax && angleDefault in angleMin..angleMax) { "Angle normalization needs min < default < max" }
    }

    fun toJson() = buildJsonObject {
        putJsonObject("position") { put("min", positionMin); put("default", positionDefault); put("max", positionMax) }
        putJsonObject("angle") { put("min", angleMin); put("default", angleDefault); put("max", angleMax) }
    }

    companion object {
        fun fromJson(o: JsonObject, base: PhysicsNormalization = PhysicsNormalization()): PhysicsNormalization {
            val p = o["position"]?.jsonObject ?: JsonObject(emptyMap())
            val a = o["angle"]?.jsonObject ?: JsonObject(emptyMap())
            return PhysicsNormalization(p.number("min", base.positionMin), p.number("default", base.positionDefault), p.number("max", base.positionMax),
                a.number("min", base.angleMin), a.number("default", base.angleDefault), a.number("max", base.angleMax))
        }
    }
}

/**
 * A Cubism physics setting: a strand of [segments] hanging from a root that [inputs] push and tilt,
 * with [outputs] reading its vertices. Generated groups and user groups share this shape; a user group
 * with a generated group's ID replaces that group.
 */
data class RigPhysicsEdit(
    val id: String,
    val name: String,
    val inputs: List<PhysicsInput> = emptyList(),
    val outputs: List<PhysicsOutput> = emptyList(),
    val segments: List<PhysicsSegment> = listOf(PhysicsSegment()),
    val normalization: PhysicsNormalization = PhysicsNormalization(),
) {
    init {
        require(listOf(id, name).all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Physics ID and name are required" }
        require(segments.size in 1..MAX_SEGMENTS) { "A pendulum has 1..$MAX_SEGMENTS segments" }
        require(inputs.size <= MAX_LINKS && outputs.size <= MAX_LINKS) { "At most $MAX_LINKS inputs and outputs" }
        require(outputs.all { it.vertex <= segments.size }) { "Physics output vertex exceeds the segment count" }
        require(inputs.map { it.parameter }.distinct().size == inputs.size) { "A parameter feeds a pendulum once" }
        require(outputs.map { it.parameter }.distinct().size == outputs.size) { "A pendulum writes a parameter once" }
    }

    val totalLength: Float get() = segments.sumOf { it.length.toDouble() }.toFloat()
    val outputParameters: List<String> get() = outputs.map { it.parameter }

    /** Every parameter this group reads or writes. */
    val parameters: List<String> get() = inputs.map { it.parameter } + outputs.map { it.parameter }

    /** Fewer or more segments; new ones copy the last, and outputs past the tip move to it. */
    fun withSegmentCount(count: Int): RigPhysicsEdit {
        val n = count.coerceIn(1, MAX_SEGMENTS)
        if (n == segments.size) return this
        val next = if (n < segments.size) segments.take(n) else segments + List(n - segments.size) { segments.last() }
        val moved = outputs.map { if (it.vertex > n) it.copy(vertex = n) else it }
        return copy(segments = next, outputs = moved.distinctBy { it.parameter })
    }

    /**
     * Without segment [index] (0-based). Outputs on its tip move to the tip above it (or the new first
     * tip), and deeper outputs follow their segment up by one.
     */
    fun withoutSegment(index: Int): RigPhysicsEdit {
        require(segments.size > 1 && index in segments.indices) { "A pendulum keeps at least one segment" }
        val removed = index + 1
        val moved = outputs.map { o ->
            when {
                o.vertex > removed -> o.copy(vertex = o.vertex - 1)
                o.vertex == removed -> o.copy(vertex = (removed - 1).coerceAtLeast(1))
                else -> o
            }
        }
        return copy(segments = segments.filterIndexed { i, _ -> i != index }, outputs = moved)
    }

    /**
     * A copy of segment [after] (0-based) inserted below it. Outputs keep reading the same segment: those
     * deeper than [after] move down by one.
     */
    fun withSegmentInserted(after: Int): RigPhysicsEdit {
        require(segments.size < MAX_SEGMENTS && after in segments.indices) { "A pendulum has 1..$MAX_SEGMENTS segments" }
        val next = segments.toMutableList().also { it.add(after + 1, segments[after]) }
        return copy(segments = next, outputs = outputs.map { if (it.vertex > after + 1) it.copy(vertex = it.vertex + 1) else it })
    }

    /** Segment [from] moved to [to] (0-based); each output follows the segment it reads. */
    fun withSegmentMoved(from: Int, to: Int): RigPhysicsEdit {
        require(from in segments.indices && to in segments.indices) { "No segment ${from + 1} or ${to + 1}" }
        if (from == to) return this
        val order = segments.indices.toMutableList().also { it.add(to, it.removeAt(from)) }
        val placeOf = order.withIndex().associate { (place, old) -> old to place }
        return copy(segments = order.map { segments[it] }, outputs = outputs.map { it.copy(vertex = placeOf.getValue(it.vertex - 1) + 1) })
    }

    /** Stretches every segment so the strand is [total] long. */
    fun withTotalLength(total: Float): RigPhysicsEdit {
        require(total.isFinite() && total > 0f) { "Physics length must be positive" }
        val k = total / totalLength
        return copy(segments = segments.map { it.copy(length = it.length * k) })
    }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name)
        putJsonArray("inputs") { inputs.forEach { add(it.toJson()) } }
        putJsonArray("outputs") { outputs.forEach { add(it.toJson()) } }
        putJsonArray("segments") { segments.forEach { add(it.toJson()) } }
        if (normalization != PhysicsNormalization()) put("normalization", normalization.toJson())
    }

    /**
     * [o] laid over this group: arrays replace, `length`/`mobility`/`delay`/`acceleration` apply to every
     * segment (length as the whole strand), `output_scale` to every output, and the single-link fields
     * of the first version (`input_parameter`, `output_parameter`) stand for one Angle input or output.
     */
    fun patched(o: JsonObject): RigPhysicsEdit {
        var next = copy(name = o.string("name") ?: name)
        o["segments"]?.let { value ->
            next = if (value is JsonPrimitive) next.withSegmentCount(value.int)
            else next.copy(segments = value.jsonArray.mapIndexed { i, s -> PhysicsSegment.fromJson(s.jsonObject, next.segments.getOrElse(i) { next.segments.last() }) },
                outputs = next.outputs.filter { it.vertex <= value.jsonArray.size })
        }
        o["segment_count"]?.jsonPrimitive?.int?.let { next = next.withSegmentCount(it) }
        o.number("length")?.let { next = next.withTotalLength(it) }
        val shared = listOf("mobility", "delay", "acceleration").filter { o.number(it) != null }
        if (shared.isNotEmpty()) next = next.copy(segments = next.segments.map { s ->
            PhysicsSegment(s.length, o.number("mobility") ?: s.mobility, o.number("delay") ?: s.delay, o.number("acceleration") ?: s.acceleration)
        })
        o["inputs"]?.let { next = next.copy(inputs = it.jsonArray.map { i -> PhysicsInput.fromJson(i.jsonObject) }) }
            ?: o.string("input_parameter")?.let { next = next.copy(inputs = listOf(PhysicsInput(it))) }
        o["outputs"]?.let { value ->
            val outputs = value.jsonArray.map { PhysicsOutput.fromJson(it.jsonObject) }
            val deepest = outputs.maxOfOrNull { it.vertex } ?: 1
            if (deepest > next.segments.size) next = next.withSegmentCount(deepest)
            next = next.copy(outputs = outputs)
        } ?: o.string("output_parameter")?.let { next = next.copy(outputs = listOf(PhysicsOutput(it, 1, next.outputs.firstOrNull()?.scale ?: 1f))) }
        o.number("output_scale")?.let { scale -> next = next.copy(outputs = next.outputs.map { it.copy(scale = scale) }) }
        o["normalization"]?.let { next = next.copy(normalization = PhysicsNormalization.fromJson(it.jsonObject, next.normalization)) }
        return next
    }

    companion object {
        const val MAX_SEGMENTS = 16
        const val MAX_LINKS = 16

        /** Reads both the current shape and the first version's single input/output pendulum. */
        fun fromJson(o: JsonObject): RigPhysicsEdit {
            val id = o.text("id")
            // The first version always tilted gravity through ±30 degrees.
            val legacy = "inputs" !in o && "segments" !in o
            val blank = RigPhysicsEdit(id, o.string("name") ?: id, segments = listOf(PhysicsSegment(10f, .8f, .8f, 1f)),
                normalization = if (legacy) PhysicsNormalization(angleMin = -30f, angleMax = 30f) else PhysicsNormalization())
            return blank.patched(o)
        }
    }
}

/** Where a physics group comes from. Every generated group can be replaced or turned off. */
enum class PhysicsOrigin { PRESET, SKELETON, SWING, SIMULATION, CUSTOM }

/** Why a group does not reach the model. */
data class PhysicsIssue(val code: Code, val parameter: String? = null, val group: String? = null) {
    enum class Code { MISSING_PARAMETER, NO_INPUT, NO_OUTPUT, FEEDBACK, OUTPUT_TAKEN }

    val message: String get() = when (code) {
        Code.MISSING_PARAMETER -> "Parameter $parameter does not exist"
        Code.NO_INPUT -> "No input parameter"
        Code.NO_OUTPUT -> "No output parameter"
        Code.FEEDBACK -> "Parameter $parameter is both input and output"
        Code.OUTPUT_TAKEN -> "Output $parameter is already driven by $group"
    }
}

/**
 * One row of the physics catalog. [setting] is what exports: the user's replacement when there is one,
 * else [generated]. [shadowedBy] names the user group that drives this generated group's outputs instead.
 */
data class PhysicsGroup(
    val setting: RigPhysicsEdit,
    val origin: PhysicsOrigin,
    val generated: RigPhysicsEdit?,
    val enabled: Boolean,
    val issue: PhysicsIssue? = null,
    val shadowedBy: String? = null,
) {
    val id: String get() = setting.id
    /** A generated group the user replaced. */
    val overridden: Boolean get() = origin != PhysicsOrigin.CUSTOM && generated != null && generated != setting
    val active: Boolean get() = enabled && issue == null && shadowedBy == null

    fun toJson() = buildJsonObject {
        put("origin", origin.name.lowercase())
        put("enabled", enabled)
        if (overridden) put("overridden", true)
        put("active", active)
        put("quality", QualityInspection.combine(QualityFence.OBSERVATION, listOf(QualityCheckResult("physics:$id", "Catalog activation and output ownership", buildList {
            issue?.let { add(QualityFinding(QualityRule.PHYSICS_INACTIVE_GROUP, "physics:$id", QualityEvidence.metrics(emptyMap(),
                mapOf("issue" to it.code.name), it.message))) }
            shadowedBy?.let { add(QualityFinding(QualityRule.PHYSICS_SHADOWED_GROUP, "physics:$id", QualityEvidence.metrics(emptyMap(), mapOf("owner" to it)))) }
        }))).toJson())
        issue?.let { put("issue", it.message) }
        shadowedBy?.let { put("replaced_by", it) }
        setting.toJson().forEach { (k, v) -> put(k, v) }
    }
}

private fun JsonObject.text(key: String) = requireNotNull(string(key)) { "$key is required" }
private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.floatOrNull
private fun JsonObject.number(key: String, fallback: Float) = number(key) ?: fallback
private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull ?: false
