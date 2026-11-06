package io.github.psd2live.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import io.github.psd2live.format.model.Curve as IrCurve
import io.github.psd2live.format.model.CurveSegment

/** How a key moves to the next one; the order matches the motion3 segment types 0..3. */
enum class MotionInterpolation { LINEAR, BEZIER, STEPPED, INVERSE_STEPPED }

/**
 * A Bezier handle: [x] is a fraction of the segment's duration, so a handle never leaves its segment
 * (motion3's `AreBeziersRestricted`), and [y] is a value offset from the key it belongs to.
 */
data class MotionHandle(val x: Float = 1f / 3f, val y: Float = 0f) {
	init {
		require(x.isFinite() && y.isFinite())
	}

	fun clamped() = copy(x = x.coerceIn(0f, 1f))
}

/**
 * One key of a curve. [interpolation] and [outHandle] shape the segment that starts here, [inHandle]
 * the one that ends here.
 */
data class MotionKey(
	val time: Float,
	val value: Float,
	val interpolation: MotionInterpolation = MotionInterpolation.BEZIER,
	val outHandle: MotionHandle = MotionHandle(),
	val inHandle: MotionHandle = MotionHandle(),
) {
	init {
		require(time.isFinite() && value.isFinite()) { "Motion keys must be finite" }
	}
}

/** The keys one parameter moves through, in time order. */
data class MotionCurve(val parameterId: String, val keys: List<MotionKey>) {
	init {
		require(parameterId.isNotBlank()) { "Motion curve needs a parameter" }
		require(keys.isNotEmpty()) { "Motion curve needs a key" }
		require(keys.zipWithNext().all { (a, b) -> a.time <= b.time }) { "Motion keys must be in time order" }
	}
}

/**
 * A point a runtime reports to its host as the motion passes it (Cubism's motion user data, the animation view's
 * Event): the host receives [value] at [time] seconds.
 */
data class MotionEvent(val time: Float, val value: String) {
	init {
		require(time.isFinite() && time >= 0f) { "Event time must be finite and not negative" }
		require(value.length <= MAX_VALUE_LENGTH) { "An event value is at most $MAX_VALUE_LENGTH characters" }
	}

	companion object {
		const val MAX_VALUE_LENGTH = 1024
		const val MAX_PER_CLIP = 256
	}
}

data class MotionParameterRange(val min: Float, val max: Float, val repeat: Boolean = false) {
    init { require(min.isFinite() && max.isFinite() && min < max) { "Invalid captured motion parameter range" } }
}

/**
 * An authored motion. A [builtin] clip overrides the generated motion of that name (see
 * the application preset catalog); without it the clip is the user's own and exports under [name].
 */
data class MotionClip(
	val id: String,
	val name: String,
	val builtin: String? = null,
	val loop: Boolean = false,
	val duration: Float = 2f,
	val fps: Float = 30f,
	val fadeIn: Float = 1f,
	val fadeOut: Float = 1f,
	val enabled: Boolean = true,
	val curves: List<MotionCurve> = emptyList(),
	/** Events in time order, one per time. */
	val events: List<MotionEvent> = emptyList(),
    /** Stable asset name retained when a generated clip becomes ordinary authored content. */
    val fileStem: String? = null,
    val exportGroup: String? = null,
    val runtimeDefaultFades: Boolean = false,
    /** Runtime parameter groups contributed by this clip, independent of its display name. */
    val parameterRoles: Map<String, List<String>> = emptyMap(),
    /** Applied curves retain their exact controls until the corresponding parameter range changes. */
    val capturedRanges: Map<String, MotionParameterRange> = emptyMap(),
) {
	init {
        require(capturedRanges.keys.all { it.isNotBlank() }) { "Captured motion ranges need parameter IDs" }
        require(parameterRoles.all { (role, parameters) -> role.isNotBlank() && parameters.isNotEmpty() &&
            parameters.all { it.isNotBlank() } && parameters.distinct().size == parameters.size }) { "Invalid motion parameter role" }
        require(fileStem == null || fileStem.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid motion file stem" }
		require(id.isNotBlank() && name.isNotBlank() && name.none(Char::isISOControl)) { "Motion needs an id and a name" }
		require(duration.isFinite() && duration > 0f) { "Motion duration must be positive" }
		require(fps.isFinite() && fps in 1f..120f) { "Motion FPS must be within 1..120" }
		require(fadeIn.isFinite() && fadeIn >= 0f && fadeOut.isFinite() && fadeOut >= 0f) { "Motion fades must not be negative" }
		require(curves.map { it.parameterId }.distinct().size == curves.size) { "A motion drives each parameter once" }
		require(events.size <= MotionEvent.MAX_PER_CLIP) { "A motion has at most ${MotionEvent.MAX_PER_CLIP} events" }
		require(events.zipWithNext().all { (a, b) -> a.time < b.time }) { "Motion events must be in time order, one per time" }
	}

	fun curve(parameterId: String): MotionCurve? = curves.firstOrNull { it.parameterId == parameterId }
}

/** Shared motion data codec and curve conversion, independent of the editor. */
object MotionData {
	const val TIME_EPSILON = 1e-4f
	fun normalized(keys: List<MotionKey>): List<MotionKey> {
		val sorted = keys.withIndex().sortedWith(compareBy({ it.value.time }, { it.index })).map { it.value }
		val result = ArrayList<MotionKey>(sorted.size)
		for (key in sorted) {
			if (result.isNotEmpty() && abs(result.last().time - key.time) < TIME_EPSILON) result[result.lastIndex] = key
			else result += key
		}
		return result
	}

	fun controlPoints(a: MotionKey, b: MotionKey): Pair<Pair<Float, Float>, Pair<Float, Float>> {
		val span = b.time - a.time
		val out = a.outHandle.clamped()
		val inn = b.inHandle.clamped()
		return (a.time + out.x * span to a.value + out.y) to (b.time - inn.x * span to b.value + inn.y)
	}

	fun curve(source: MotionCurve): IrCurve {
		val first = source.keys.first()
		// A curve needs a segment; a lone key, or one after zero, holds from zero.
		val keys = buildList {
			if (first.time > MotionData.TIME_EPSILON || source.keys.size == 1) add(first.copy(time = 0f, interpolation = MotionInterpolation.LINEAR))
			addAll(source.keys)
			if (size == 1) add(first.copy(time = maxOf(first.time, MotionData.TIME_EPSILON)))
		}
		return IrCurve(source.parameterId, keys.first().time, keys.first().value, keys.zipWithNext { a, b ->
			when (a.interpolation) {
				MotionInterpolation.LINEAR -> CurveSegment.Linear(b.time, b.value)
				MotionInterpolation.BEZIER -> {
					val (c1, c2) = MotionData.controlPoints(a, b)
					CurveSegment.Bezier(c1.first, c1.second, c2.first, c2.second, b.time, b.value)
				}
				MotionInterpolation.STEPPED -> CurveSegment.Stepped(b.time, b.value)
				MotionInterpolation.INVERSE_STEPPED -> CurveSegment.InverseStepped(b.time, b.value)
			}
		})
	}

	fun toJson(clip: MotionClip): JsonObject = buildJsonObject {
		put("id", clip.id)
        clip.fileStem?.let { put("file", it) }
        clip.exportGroup?.let { put("export_group", it) }
        if (clip.runtimeDefaultFades) put("runtime_default_fades", true)
        if (clip.capturedRanges.isNotEmpty()) putJsonObject("captured_ranges") {
            clip.capturedRanges.forEach { (id, range) -> putJsonObject(id) {
                put("min", range.min); put("max", range.max); put("repeat", range.repeat)
            } }
        }
        if (clip.parameterRoles.isNotEmpty()) putJsonObject("parameter_roles") {
            clip.parameterRoles.forEach { (role, parameters) -> put(role, JsonArray(parameters.map(::JsonPrimitive))) }
        }
		put("name", clip.name)
		clip.builtin?.let { put("builtin", it) }
		put("loop", clip.loop)
		put("duration", clip.duration)
		put("fps", clip.fps)
		put("fade_in", clip.fadeIn)
		put("fade_out", clip.fadeOut)
		put("enabled", clip.enabled)
		putJsonArray("curves") {
			for (curve in clip.curves) add(buildJsonObject {
				put("parameter", curve.parameterId)
				putJsonArray("keys") {
					for (key in curve.keys) add(buildJsonObject {
						put("time", key.time)
						put("value", key.value)
						if (key.interpolation != MotionInterpolation.BEZIER) put("interpolation", key.interpolation.name)
						if (key.outHandle != MotionHandle()) put("out", JsonArray(listOf(JsonPrimitive(key.outHandle.x), JsonPrimitive(key.outHandle.y))))
						if (key.inHandle != MotionHandle()) put("in", JsonArray(listOf(JsonPrimitive(key.inHandle.x), JsonPrimitive(key.inHandle.y))))
					})
				}
			})
		}
		// Only a clip with events writes the key, so clips without them keep their stored form.
		if (clip.events.isNotEmpty()) putJsonArray("events") {
			for (event in clip.events) add(buildJsonObject { put("time", event.time); put("value", event.value) })
		}
	}

	/** [events] by time, a later event replacing an earlier one at the same time. */
	fun normalizedEvents(events: List<MotionEvent>): List<MotionEvent> {
		val result = ArrayList<MotionEvent>(events.size)
		for (event in events.withIndex().sortedWith(compareBy({ it.value.time }, { it.index })).map { it.value }) {
			if (result.isNotEmpty() && abs(result.last().time - event.time) < TIME_EPSILON) result[result.lastIndex] = event
			else result += event
		}
		return result
	}

	fun fromJson(o: JsonObject): MotionClip = MotionClip(
		id = o.getValue("id").jsonPrimitive.content,
		name = o.getValue("name").jsonPrimitive.content,
		builtin = o["builtin"]?.jsonPrimitive?.contentOrNull,
        fileStem = o["file"]?.jsonPrimitive?.contentOrNull,
        exportGroup = o["export_group"]?.jsonPrimitive?.contentOrNull,
        runtimeDefaultFades = o["runtime_default_fades"]?.jsonPrimitive?.booleanOrNull ?: false,
        capturedRanges = o["captured_ranges"]?.jsonObject?.mapValues { (_, value) -> value.jsonObject.let {
            MotionParameterRange(it.getValue("min").jsonPrimitive.float, it.getValue("max").jsonPrimitive.float,
                it["repeat"]?.jsonPrimitive?.boolean ?: false)
        } }.orEmpty(),
        parameterRoles = o["parameter_roles"]?.jsonObject?.mapValues { (_, ids) -> ids.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
		loop = o["loop"]?.jsonPrimitive?.booleanOrNull ?: false,
		duration = o["duration"]?.jsonPrimitive?.floatOrNull ?: 2f,
		fps = o["fps"]?.jsonPrimitive?.floatOrNull ?: 30f,
		fadeIn = o["fade_in"]?.jsonPrimitive?.floatOrNull ?: 1f,
		fadeOut = o["fade_out"]?.jsonPrimitive?.floatOrNull ?: 1f,
		enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
		curves = o["curves"]?.jsonArray.orEmpty().map { element ->
			val curve = element.jsonObject
			MotionCurve(
				parameterId = curve.getValue("parameter").jsonPrimitive.content,
				keys = curve["keys"]?.jsonArray.orEmpty().map { keyElement ->
					val key = keyElement.jsonObject
					fun handle(name: String) = key[name]?.jsonArray?.let {
						MotionHandle(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float)
					} ?: MotionHandle()
					MotionKey(
						time = key.getValue("time").jsonPrimitive.float,
						value = key.getValue("value").jsonPrimitive.float,
						interpolation = key["interpolation"]?.jsonPrimitive?.contentOrNull
							?.let { name -> MotionInterpolation.entries.firstOrNull { it.name == name } }
							?: MotionInterpolation.BEZIER,
						outHandle = handle("out"),
						inHandle = handle("in"),
					)
                }.let { keys ->
                    // Captured generated curves can contain an intentional jump at the same timestamp.
                    if (curve.getValue("parameter").jsonPrimitive.content in o["captured_ranges"]?.jsonObject.orEmpty()) keys.sortedBy { it.time }
                    else normalized(keys)
                },
			)
		},
		events = normalizedEvents(o["events"]?.jsonArray.orEmpty().map { element ->
			val event = element.jsonObject
			MotionEvent(event.getValue("time").jsonPrimitive.float, event.getValue("value").jsonPrimitive.content)
		}),
	)
}
