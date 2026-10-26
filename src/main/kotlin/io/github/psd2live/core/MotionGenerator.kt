package io.github.psd2live.core

import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.targets.cubism.Cubism3Json
import io.github.psd2live.format.model.Curve as IrCurve

/** Demonstration motions that exercise the generated rig without audio assets. */
object MotionGenerator {
	fun idle(): String = idle(ALL_PARAMETERS)!!

	/**
	 * The looping idle: the body tracks of [SkeletonMotions.idle], with a skeleton's pose tracks among them,
	 * and a blink. Parameters in [skeletonExclude] are driven by exported physics instead and stay out of it.
	 */
	fun idle(
		availableParameterIds: Set<String>,
		skeleton: SkeletonSpec? = null,
		skeletonExclude: Set<String> = emptySet(),
	): String? = buildMotionJson(
		duration = SkeletonMotions.IDLE_DURATION,
		loop = true,
		curves = SkeletonMotions.idle(skeleton, skeletonExclude).map(::curve) + idleBlink(),
		availableParameterIds = availableParameterIds,
	)

	/**
	 * A skeleton preset ([SkeletonMotions.presets]) as motion3 JSON. A [loop] stands in for the idle, so it
	 * blinks like the idle does.
	 */
	fun skeleton(tracks: List<MotionTrack>, availableParameterIds: Set<String>, loop: Boolean = false): String? {
		if (tracks.isEmpty()) return null
		return buildMotionJson(
			duration = tracks.maxOf { it.keys.last().time },
			loop = loop,
			curves = tracks.map(::curve) + (if (loop) idleBlink() else emptyList()),
			availableParameterIds = availableParameterIds,
		)
	}

	/** The idle's blinks: uneven gaps, one of them a double blink, as eyes left to themselves blink. */
	private fun idleBlink(): List<IrCurve> = idleBlinkTracks.map(::curve)

	val idleBlinkTracks: List<MotionTrack> = listOf("ParamEyeLOpen", "ParamEyeROpen").map { id ->
		val points = mutableListOf(0f to 1f)
		for (start in listOf(2.7f, 6.9f, 7.25f, 10.6f)) points += listOf(start to 1f, start + 0.08f to 0f, start + 0.18f to 1f)
		MotionCurveMath.linear(id, points + (SkeletonMotions.IDLE_DURATION to 1f))
	}

	/**
	 * The idle's blinks the `motion/natural` way, over its 12 seconds: shutting in about two frames, a breath shut,
	 * opening over about five frames with an easing out, at uneven gaps of two to five seconds, one of them doubled;
	 * the right eye a frame behind the left.
	 */
	val naturalBlinkTracks: List<MotionTrack> = listOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 1f / 60f).map { (id, lag) ->
		val points = mutableListOf(0f to 1f)
		for ((start, depth) in listOf(1.9f to 1f, 5.4f to 1f, 5.78f to 0.8f, 9.1f to 1f)) {
			val s = start + lag
			points += listOf(s to 1f, s + 0.07f to 1f - depth, s + 0.11f to 1f - depth, s + 0.2f to 1f - 0.8f * depth * 0.25f, s + 0.3f to 1f)
		}
		MotionCurveMath.linear(id, points + (SkeletonMotions.IDLE_DURATION to 1f))
	}

	/**
	 * Speech the `motion/natural` way, over about six seconds: syllables mostly small - lip sync spends most of its
	 * time at a small opening - now and then a wide one, short pauses between phrases; each syllable leans on a vowel
	 * (A I U E O) as the opening suggests, the mouth's form wanders a little, and the head nods with the stresses.
	 */
	val talkTracks: List<MotionTrack> = run {
		// Opening, vowel and length of each syllable; zeros are pauses.
		val syllables = listOf(0.35f to 'A', 0.2f to 'I', 0.45f to 'O', 0f to ' ', 0.25f to 'E', 0.3f to 'A', 0.15f to 'U', 0.75f to 'A', 0f to ' ',
			0.2f to 'I', 0.4f to 'E', 0.25f to 'O', 0.35f to 'A', 0.15f to 'U', 0f to ' ', 0.3f to 'E', 0.55f to 'A', 0.2f to 'I', 0f to ' ')
		val open = mutableListOf(0f to 0f)
		val vowels = "AIUEO".associateWith { mutableListOf(0f to 0f) }
		var t = 0.2f
		for ((k, entry) in syllables.withIndex()) {
			val (amount, vowel) = entry
			val length = if (amount == 0f) 0.45f else 0.2f + 0.06f * (k % 3)
			val peak = t + length * 0.45f
			open += listOf(t to 0f, peak to amount, t + length to 0.02f)
			vowels[vowel]?.let { it += listOf(t to 0f, peak to (0.4f + amount).coerceAtMost(1f), t + length to 0f) }
			t += length
		}
		open += (t + 0.2f) to 0f
		val end = t + 0.2f
		listOf(MotionCurveMath.linear("ParamMouthOpenY", open)) +
			vowels.map { (v, points) -> MotionCurveMath.linear("Param$v", points + (end to 0f)) } +
			MotionCurveMath.eased("ParamMouthForm", listOf(0f to 0.1f, end * 0.3f to 0.3f, end * 0.6f to 0f, end to 0.1f)) +
			MotionCurveMath.eased("ParamAngleY", listOf(0f to 0f, 1.3f to -2.5f, 1.8f to 0.5f, 3.4f to -2f, 4.0f to 0.5f, end to 0f)) +
			MotionCurveMath.eased("ParamBrowLY", listOf(0f to 0f, 1.3f to 0.25f, 2f to 0f, 3.4f to 0.2f, 4.2f to 0f, end to 0f)) +
			MotionCurveMath.eased("ParamBrowRY", listOf(0f to 0f, 1.3f to 0.25f, 2f to 0f, 3.4f to 0.2f, 4.2f to 0f, end to 0f))
	}

	/** One blink the `motion/natural` way: shut fast, open slowly, the right eye a frame late; twice when [double]. */
	fun naturalBlink(closure: Float = 1f, double: Boolean = false): List<MotionTrack> {
		val depth = closure.coerceIn(0f, 1f)
		return listOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 1f / 60f).map { (id, lag) ->
			fun blink(s: Float, d: Float) = listOf(s to 1f, s + 0.07f to 1f - d, s + 0.11f to 1f - d, s + 0.2f to 1f - 0.2f * d, s + 0.3f to 1f)
			val points = listOf(0f to 1f) + blink(0.35f + lag, depth) + (if (double) blink(0.73f + lag, depth * 0.8f) else emptyList()) +
				((if (double) 1.4f else 1.2f) to 1f)
			MotionCurveMath.linear(id, points)
		}
	}

	/**
	 * The idle's glances: twice in its 12 seconds the eyes slip aside - once to one side, once down and to the other -
	 * hold a moment and come back, eased.
	 */
	val glanceTracks: List<MotionTrack> = listOf(
		MotionCurveMath.eased("ParamEyeBallX", listOf(0f to 0f, 3.2f to 0f, 3.45f to 0.45f, 4.3f to 0.45f, 4.6f to 0f,
			7.4f to 0f, 7.65f to -0.35f, 8.4f to -0.35f, 8.75f to 0f, SkeletonMotions.IDLE_DURATION to 0f)),
		MotionCurveMath.eased("ParamEyeBallY", listOf(0f to 0f, 7.4f to 0f, 7.65f to -0.3f, 8.4f to -0.3f, 8.75f to 0f, SkeletonMotions.IDLE_DURATION to 0f)),
	)

	/**
	 * An authored clip as motion3 JSON, each key's interpolation written as its segment type. A curve that
	 * starts after zero holds its first value from zero, as the preview samples it.
	 */
	fun clip(clip: MotionClip, availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = clip.duration,
		loop = clip.loop,
		curves = clip.curves.map(::curve),
		availableParameterIds = availableParameterIds,
		fps = clip.fps,
		fadeIn = clip.fadeIn,
		fadeOut = clip.fadeOut,
	)

	fun blink(): String = blink(ALL_PARAMETERS)!!

	fun blink(availableParameterIds: Set<String>): String? = oneShot(blinkTracks, availableParameterIds)

	/** The blink's tracks; the preview samples the same points the export writes. */
	val blinkTracks: List<MotionTrack> = blinkTracks()

	/** A blink closing the eyes [closure] of the way, and a second, lighter one right after when [double]. */
	fun blinkTracks(closure: Float = 1f, double: Boolean = false): List<MotionTrack> {
		val shut = 1f - closure.coerceIn(0f, 1f)
		val points = listOf(0f to 1f, 0.35f to 1f, 0.45f to shut, 0.58f to 1f) +
			(if (double) listOf(0.68f to 1f - closure * 0.85f, 0.8f to 1f, 1.4f to 1f) else listOf(1.2f to 1f))
		return listOf("ParamEyeLOpen", "ParamEyeROpen").map { MotionCurveMath.linear(it, points) }
	}

	fun nod(): String = nod(ALL_PARAMETERS)!!

	fun nod(availableParameterIds: Set<String>): String? = oneShot(nodTracks, availableParameterIds)

	val nodTracks: List<MotionTrack> = nodTracks()

	/**
	 * [count] nods: the head dips, comes partly back up between dips, each a little shallower, and after the
	 * last overshoots upward before it settles. The body and the eyelids go with the head.
	 */
	fun nodTracks(count: Int = 1): List<MotionTrack> {
		val head = mutableListOf(0f to 0f)
		val eyes = mutableListOf(0f to 1f)
		var time = 0.55f
		for (index in 0 until count.coerceAtLeast(1)) {
			head += time to -18f * (1f - 0.15f * index)
			eyes += time to 0.75f
			if (index < count - 1) {
				head += time + 0.35f to -3f
				eyes += time + 0.35f to 0.95f
				time += 0.7f
			}
		}
		head += listOf(time + 0.7f to 6f, time + 1.45f to 0f)
		eyes += listOf(time + 0.7f to 1f, time + 1.45f to 1f)
		val body = head.map { (t, v) -> t to v * if (v < 0f) 4f / 18f else 0.25f }
		return listOf(
			MotionCurveMath.linear("ParamAngleY", head),
			MotionCurveMath.linear("ParamBodyAngleY", body),
			MotionCurveMath.linear("ParamEyeLOpen", eyes),
			MotionCurveMath.linear("ParamEyeROpen", eyes),
		)
	}

	fun shake(): String = shake(ALL_PARAMETERS)!!

	fun shake(availableParameterIds: Set<String>): String? = oneShot(shakeTracks, availableParameterIds)

	val shakeTracks: List<MotionTrack> = shakeTracks()

	/** [count] shakes of the head, each a little narrower, then a small settle; the body and the tilt follow. */
	fun shakeTracks(count: Int = 1): List<MotionTrack> {
		val head = mutableListOf(0f to 0f)
		var time = 0.4f
		for (index in 0 until count.coerceAtLeast(1)) {
			val reach = 20f * (1f - 0.12f * index)
			head += listOf(time to -reach, time + 0.5f to reach)
			time += 1f
		}
		head += listOf(time to -8f, time + 0.6f to 0f)
		return listOf(
			MotionCurveMath.linear("ParamAngleX", head),
			MotionCurveMath.linear("ParamBodyAngleX", head.map { (t, v) -> t to v * 0.15f }),
			MotionCurveMath.linear("ParamAngleZ", head.map { (t, v) -> t to if (v == -8f) 1f else -v * 0.1f }),
		)
	}

	/** Generated [tracks] as motion3 JSON; a [loop] lasts [duration], the cycle the tracks were made for. */
	fun tracks(tracks: List<MotionTrack>, availableParameterIds: Set<String>, loop: Boolean, duration: Float): String? {
		if (tracks.isEmpty()) return null
		return buildMotionJson(
			duration = duration,
			loop = loop,
			curves = tracks.map(::curve),
			availableParameterIds = availableParameterIds,
		)
	}

	private fun oneShot(tracks: List<MotionTrack>, availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = tracks.maxOf { it.keys.last().time },
		loop = false,
		curves = tracks.map(::curve),
		availableParameterIds = availableParameterIds,
	)

	private fun buildMotionJson(
		duration: Float,
		loop: Boolean,
		curves: List<IrCurve>,
		availableParameterIds: Set<String>,
		fps: Float = 30f,
		fadeIn: Float? = null,
		fadeOut: Float? = null,
	): String? = Cubism3Json.motion3(Clip("", "", "", "", duration, fps, loop, fadeIn, fadeOut, curves), availableParameterIds)

	/** A generated or authored track as a compiled IR curve, its keys' interpolations as segment types. */
	fun curve(source: MotionCurve): IrCurve {
		val first = source.keys.first()
		// A curve needs a segment; a lone key, or one after zero, holds from zero.
		val keys = buildList {
			if (first.time > MotionClips.TIME_EPSILON || source.keys.size == 1) add(first.copy(time = 0f, interpolation = MotionInterpolation.LINEAR))
			addAll(source.keys)
			if (size == 1) add(first.copy(time = maxOf(first.time, MotionClips.TIME_EPSILON)))
		}
		return IrCurve(source.parameterId, keys.first().time, keys.first().value, keys.zipWithNext { a, b ->
			when (a.interpolation) {
				MotionInterpolation.LINEAR -> CurveSegment.Linear(b.time, b.value)
				MotionInterpolation.BEZIER -> {
					val (c1, c2) = MotionClips.controlPoints(a, b)
					CurveSegment.Bezier(c1.first, c1.second, c2.first, c2.second, b.time, b.value)
				}
				MotionInterpolation.STEPPED -> CurveSegment.Stepped(b.time, b.value)
				MotionInterpolation.INVERSE_STEPPED -> CurveSegment.InverseStepped(b.time, b.value)
			}
		})
	}

	/** The IR clip of generated [tracks], or null when there are none. */
	fun tracksClip(tracks: List<MotionTrack>, loop: Boolean, duration: Float): List<IrCurve>? =
		tracks.takeIf { it.isNotEmpty() }?.map(::curve)

	private val ALL_PARAMETERS = setOf(
		"ParamBreath",
		"ParamAngleX",
		"ParamAngleY",
		"ParamAngleZ",
		"ParamBodyAngleX",
		"ParamBodyAngleY",
		"ParamBodyAngleZ",
		"ParamEyeLOpen",
		"ParamEyeROpen",
	)
}
