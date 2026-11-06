package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

object MotionClips {
	/**
	 * Parameter deletion removes tracks; range changes constrain keys and Bezier control values. A repeating parameter's
	 * keys may run turns past its range ([MotionLoops.keyLimits]): the runtime wraps them.
	 */
	fun reconcileParameters(clips: List<MotionClip>, parameters: List<org.umamo.runtime.model.Parameter>): List<MotionClip> {
		val definitions = parameters.associateBy { it.id.raw }
		return clips.map { clip -> clip.copy(curves = clip.curves.mapNotNull { curve ->
			val parameter = definitions[curve.parameterId] ?: return@mapNotNull null
            if (clip.capturedRanges[curve.parameterId] == MotionParameterRange(parameter.min, parameter.max, parameter.repeat)) curve
            else {
                val limits = MotionLoops.keyLimits(parameter)
                clamped(curve, limits.start, limits.endInclusive)
            }
		}, capturedRanges = clip.capturedRanges.filterKeys { it in definitions }.mapValues { (id, _) ->
            definitions.getValue(id).let { MotionParameterRange(it.min, it.max, it.repeat) }
        }, parameterRoles = clip.parameterRoles.mapValues { (_, ids) -> ids.filter { it in definitions } }.filterValues { it.isNotEmpty() }) }
	}

	/**
	 * [clips] once parameter [parameterId] spans [min]..[max]: its keys and their handles are held inside the range
	 * (or the turns a [repeat] parameter allows), as the export plays them, so the clips stay valid to edit.
	 */
	fun withParameterRange(clips: List<MotionClip>, parameterId: String, min: Float, max: Float, repeat: Boolean = false): List<MotionClip> {
		val limits = MotionLoops.keyLimits(min, max, repeat)
		return clips.map { clip -> clip.copy(curves = clip.curves.map {
			if (it.parameterId == parameterId && clip.capturedRanges[parameterId] != MotionParameterRange(min, max, repeat))
                clamped(it, limits.start, limits.endInclusive) else it
		}, capturedRanges = if (parameterId in clip.capturedRanges) clip.capturedRanges + (parameterId to MotionParameterRange(min, max, repeat)) else clip.capturedRanges) }
	}

	/** [clips] without the curves of parameter [parameterId], once it is deleted. */
	fun withoutParameter(clips: List<MotionClip>, parameterId: String): List<MotionClip> =
		clips.map { clip -> clip.copy(curves = clip.curves.filterNot { it.parameterId == parameterId }, capturedRanges = clip.capturedRanges - parameterId,
            parameterRoles = clip.parameterRoles.mapValues { (_, ids) -> ids.filterNot { it == parameterId } }.filterValues { it.isNotEmpty() }) }

	private fun clamped(curve: MotionCurve, min: Float, max: Float): MotionCurve = curve.copy(keys = curve.keys.map { key ->
		val value = key.value.coerceIn(min, max)
		fun constrain(handle: MotionHandle): MotionHandle {
			val control = key.value + handle.y
			return if (value == key.value && control in min..max) handle
			else handle.copy(y = control.coerceIn(min, max) - value)
		}
		key.copy(value = value, inHandle = constrain(key.inHandle), outHandle = constrain(key.outHandle))
	})

	/** The basic generated motions, which every rig can play; the model presets switch them as one group. */
	val BASIC_NAMES: List<String> = listOf("Idle", "Blink", "Nod", "Shake", "Talk")

	/** The generated motions, in the order the panel lists them. */
	val BUILTIN_NAMES: List<String> = BASIC_NAMES + SkeletonMotions.presets.map { it.name }

	/** Whether [name] is a skeleton preset rather than a basic motion. */
	fun isSkeletonPreset(name: String): Boolean = SkeletonMotions.presets.any { it.name.equals(name, ignoreCase = true) }

	/** Keys closer than this are one key. */
	const val TIME_EPSILON = 1e-4f

	fun isLoopBuiltin(name: String): Boolean = MotionPresets.loops(name)

	/**
	 * The tracks a generated motion plays as [settings] tune it, the same the export writes. A looping one
	 * blinks like the idle. [exclude] keeps physics-driven parameters out of the idle.
	 */
	fun builtinTracks(
		name: String,
		skeleton: SkeletonSpec?,
		exclude: Set<String> = emptySet(),
		settings: MotionPresetSettings = MotionPresetSettings(),
	): List<MotionTrack> = MotionPresets.tracks(name, skeleton, settings, exclude)

	fun builtinDuration(name: String, tracks: List<MotionTrack>, settings: MotionPresetSettings = MotionPresetSettings()): Float =
		MotionPresets.duration(name, tracks, settings)

	/** The override of [builtin] in [clips], if the user edited it. */
	fun overrideOf(clips: List<MotionClip>, builtin: String): MotionClip? =
		clips.firstOrNull { it.builtin.equals(builtin, ignoreCase = true) }

	/** The keys of [tracks] as they are; how a generated motion becomes an editable clip. */
	fun fromTracks(
		id: String,
		name: String,
		builtin: String?,
		loop: Boolean,
		tracks: List<MotionTrack>,
		duration: Float = tracks.maxOfOrNull { it.keys.last().time } ?: 2f,
	): MotionClip = MotionClip(
		id = id,
		name = name,
		builtin = builtin,
		loop = loop,
		duration = duration.takeIf { it > 0f } ?: 2f,
		curves = tracks.distinctBy { it.parameterId }.map { it.copy(keys = normalized(it.keys)) },
	)

	/** Keys sorted by time, a later key replacing an earlier one at the same time. */
	fun normalized(keys: List<MotionKey>): List<MotionKey> = MotionData.normalized(keys)

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
	fun controlPoints(a: MotionKey, b: MotionKey): Pair<Pair<Float, Float>, Pair<Float, Float>> = MotionData.controlPoints(a, b)

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
	/**
	 * An unused motion ID. [reserved] are IDs to skip as well - a deleted motion's, which its timeline aids still
	 * hold for an undo to find, so a new motion does not inherit them.
	 */
	fun newId(clips: List<MotionClip>, reserved: Set<String> = emptySet()): String {
		var index = clips.size + 1
		while (clips.any { it.id == "motion_$index" } || "motion_$index" in reserved) index++
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
	fun exportStems(clips: List<MotionClip>, generatedFiles: Set<String> = emptySet()): Map<String, String> {
		val used = BUILTIN_NAMES.mapTo(HashSet()) { it.lowercase() }
        val fixed = clips.filter { it.builtin == null }.mapNotNull { it.fileStem }
        val occupied = generatedFiles.mapTo(HashSet()) { it.lowercase() }
        require(fixed.all { occupied.add(it.lowercase()) }) { "Motion file stems must be unique and must not replace generated files" }
        used += occupied
		return clips.filter { it.builtin == null }.associate { clip ->
			clip.fileStem?.let { return@associate clip.id to it }
			val slug = slug(clip.name)
			var stem = slug
			var index = 2
			while (stem.lowercase() in used) stem = "$slug${index++}"
			used += stem.lowercase()
			clip.id to stem
		}
	}

	fun toJson(clip: MotionClip): JsonObject = MotionData.toJson(clip)
	fun fromJson(o: JsonObject): MotionClip = MotionData.fromJson(o)
	fun normalizedEvents(events: List<MotionEvent>): List<MotionEvent> = MotionData.normalizedEvents(events)
}
