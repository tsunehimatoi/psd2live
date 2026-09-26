package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

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
	val interpolation: MotionInterpolation = MotionInterpolation.LINEAR,
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
 * An authored motion. A [builtin] clip overrides the generated motion of that name (see
 * [MotionClips.BUILTIN_NAMES]); without it the clip is the user's own and exports under [name].
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
) {
	init {
		require(id.isNotBlank() && name.isNotBlank() && name.none(Char::isISOControl)) { "Motion needs an id and a name" }
		require(duration.isFinite() && duration > 0f) { "Motion duration must be positive" }
		require(fps.isFinite() && fps in 1f..120f) { "Motion FPS must be within 1..120" }
		require(fadeIn.isFinite() && fadeIn >= 0f && fadeOut.isFinite() && fadeOut >= 0f) { "Motion fades must not be negative" }
		require(curves.map { it.parameterId }.distinct().size == curves.size) { "A motion drives each parameter once" }
	}

	fun curve(parameterId: String): MotionCurve? = curves.firstOrNull { it.parameterId == parameterId }
}

object MotionClips {
	/** The generated motions, in the order the panel lists them. */
	val BUILTIN_NAMES: List<String> = listOf("Idle", "Blink", "Nod", "Shake") + SkeletonMotions.presets.map { it.name }

	/** Keys closer than this are one key. */
	const val TIME_EPSILON = 1e-4f

	fun isLoopBuiltin(name: String): Boolean = name.equals("Idle", ignoreCase = true) ||
		SkeletonMotions.presets.any { it.loop && it.name.equals(name, ignoreCase = true) }

	/**
	 * The tracks a generated motion plays, the same the export writes. A looping one blinks like the idle.
	 * [exclude] keeps physics-driven parameters out of the idle.
	 */
	fun builtinTracks(name: String, skeleton: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> =
		when (name.lowercase()) {
			"idle" -> SkeletonMotions.idle(skeleton, exclude) + MotionGenerator.idleBlinkTracks
			"blink" -> MotionGenerator.blinkTracks
			"nod" -> MotionGenerator.nodTracks
			"shake" -> MotionGenerator.shakeTracks
			else -> SkeletonMotions.presets.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { preset ->
				val tracks = preset.tracks(skeleton)
				if (preset.loop && tracks.isNotEmpty()) tracks + MotionGenerator.idleBlinkTracks else tracks
			}.orEmpty()
		}

	fun builtinDuration(name: String, tracks: List<MotionTrack>): Float =
		if (name.equals("Idle", ignoreCase = true)) SkeletonMotions.IDLE_DURATION
		else tracks.maxOfOrNull { it.second.last().first } ?: 0f

	/** The override of [builtin] in [clips], if the user edited it. */
	fun overrideOf(clips: List<MotionClip>, builtin: String): MotionClip? =
		clips.firstOrNull { it.builtin.equals(builtin, ignoreCase = true) }

	/** Linear keys through [tracks]; how a generated motion becomes an editable clip. */
	fun fromTracks(
		id: String,
		name: String,
		builtin: String?,
		loop: Boolean,
		tracks: List<MotionTrack>,
		duration: Float = tracks.maxOfOrNull { it.second.last().first } ?: 2f,
	): MotionClip = MotionClip(
		id = id,
		name = name,
		builtin = builtin,
		loop = loop,
		duration = duration.takeIf { it > 0f } ?: 2f,
		curves = tracks.distinctBy { it.first }.map { (parameter, points) ->
			MotionCurve(parameter, normalized(points.map { (time, value) -> MotionKey(time, value) }))
		},
	)

	/** Keys sorted by time, a later key replacing an earlier one at the same time. */
	fun normalized(keys: List<MotionKey>): List<MotionKey> {
		val sorted = keys.withIndex().sortedWith(compareBy({ it.value.time }, { it.index })).map { it.value }
		val result = ArrayList<MotionKey>(sorted.size)
		for (key in sorted) {
			if (result.isNotEmpty() && abs(result.last().time - key.time) < TIME_EPSILON) result[result.lastIndex] = key
			else result += key
		}
		return result
	}

	/** The value [curve] holds at [time]; before its first key and after its last it holds them. */
	fun sample(curve: MotionCurve, time: Float): Float {
		val keys = curve.keys
		if (time <= keys.first().time) return keys.first().value
		if (time >= keys.last().time) return keys.last().value
		val next = keys.indexOfFirst { it.time > time }
		val a = keys[next - 1]
		val b = keys[next]
		return sampleSegment(a, b, time)
	}

	fun sampleSegment(a: MotionKey, b: MotionKey, time: Float): Float {
		val span = b.time - a.time
		if (span < TIME_EPSILON) return b.value
		return when (a.interpolation) {
			MotionInterpolation.LINEAR -> a.value + (b.value - a.value) * ((time - a.time) / span)
			MotionInterpolation.STEPPED -> a.value
			MotionInterpolation.INVERSE_STEPPED -> b.value
			MotionInterpolation.BEZIER -> {
				val (c1, c2) = controlPoints(a, b)
				// Time is monotonic along a restricted Bezier, so bisect for the parameter that reaches it.
				var lo = 0f
				var hi = 1f
				repeat(28) {
					val mid = (lo + hi) / 2f
					if (cubic(a.time, c1.first, c2.first, b.time, mid) < time) lo = mid else hi = mid
				}
				cubic(a.value, c1.second, c2.second, b.value, (lo + hi) / 2f)
			}
		}
	}

	/** The two Bezier control points between [a] and [b], as (time, value). */
	fun controlPoints(a: MotionKey, b: MotionKey): Pair<Pair<Float, Float>, Pair<Float, Float>> {
		val span = b.time - a.time
		val out = a.outHandle.clamped()
		val inn = b.inHandle.clamped()
		return (a.time + out.x * span to a.value + out.y) to (b.time - inn.x * span to b.value + inn.y)
	}

	private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
		val u = 1f - t
		return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
	}

	/** Every curve of [clip] at [time]; a loop wraps, a one-shot holds its ends. */
	fun sampleAll(clip: MotionClip, time: Double, loop: Boolean = clip.loop): Map<ParameterId, Float> {
		val t = if (loop) (time % clip.duration).toFloat().let { if (it < 0f) it + clip.duration else it }
		else time.toFloat().coerceIn(0f, clip.duration)
		return clip.curves.associate { ParameterId(it.parameterId) to sample(it, t) }
	}

	/** A clip id unused by [clips]. */
	fun newId(clips: List<MotionClip>): String {
		var index = clips.size + 1
		while (clips.any { it.id == "motion_$index" }) index++
		return "motion_$index"
	}

	/** [base], or [base] with the first free number, unused as a name or builtin name. */
	fun uniqueName(clips: List<MotionClip>, base: String): String {
		val taken = (clips.map { it.name } + BUILTIN_NAMES).mapTo(HashSet()) { it.lowercase() }
		if (base.lowercase() !in taken) return base
		var index = 2
		while ("$base $index".lowercase() in taken) index++
		return "$base $index"
	}

	/** A file-name slug of [name]: ASCII letters, digits and underscores. */
	fun slug(name: String): String {
		val words = name.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
		if (words.isEmpty()) return "motion"
		return words.first().replaceFirstChar(Char::lowercase) + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
	}

	/**
	 * The file stem each user clip exports under, by clip id: its name's [slug], numbered past the generated
	 * motions' stems and the clips before it. The preview plays a motion by this stem.
	 */
	fun exportStems(clips: List<MotionClip>): Map<String, String> {
		val used = BUILTIN_NAMES.mapTo(HashSet()) { it.lowercase() }
		return clips.filter { it.builtin == null }.associate { clip ->
			val slug = slug(clip.name)
			var stem = slug
			var index = 2
			while (stem.lowercase() in used) stem = "$slug${index++}"
			used += stem.lowercase()
			clip.id to stem
		}
	}

	fun toJson(clip: MotionClip): JsonObject = buildJsonObject {
		put("id", clip.id)
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
						if (key.interpolation != MotionInterpolation.LINEAR) put("interpolation", key.interpolation.name)
						if (key.outHandle != MotionHandle()) put("out", JsonArray(listOf(JsonPrimitive(key.outHandle.x), JsonPrimitive(key.outHandle.y))))
						if (key.inHandle != MotionHandle()) put("in", JsonArray(listOf(JsonPrimitive(key.inHandle.x), JsonPrimitive(key.inHandle.y))))
					})
				}
			})
		}
	}

	fun fromJson(o: JsonObject): MotionClip = MotionClip(
		id = o.getValue("id").jsonPrimitive.content,
		name = o.getValue("name").jsonPrimitive.content,
		builtin = o["builtin"]?.jsonPrimitive?.contentOrNull,
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
				keys = normalized(curve["keys"]?.jsonArray.orEmpty().map { keyElement ->
					val key = keyElement.jsonObject
					fun handle(name: String) = key[name]?.jsonArray?.let {
						MotionHandle(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float)
					} ?: MotionHandle()
					MotionKey(
						time = key.getValue("time").jsonPrimitive.float,
						value = key.getValue("value").jsonPrimitive.float,
						interpolation = key["interpolation"]?.jsonPrimitive?.contentOrNull
							?.let { name -> MotionInterpolation.entries.firstOrNull { it.name == name } }
							?: MotionInterpolation.LINEAR,
						outHandle = handle("out"),
						inHandle = handle("in"),
					)
				}),
			)
		},
	)
}
