package io.github.psd2live.core

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
		curves = SkeletonMotions.idle(skeleton, skeletonExclude).map { (id, points) -> curve(id, points) } + idleBlink(),
		availableParameterIds = availableParameterIds,
	)

	/**
	 * A skeleton preset ([SkeletonMotions.presets]) as motion3 JSON. A [loop] stands in for the idle, so it
	 * blinks like the idle does.
	 */
	fun skeleton(tracks: List<MotionTrack>, availableParameterIds: Set<String>, loop: Boolean = false): String? {
		if (tracks.isEmpty()) return null
		return buildMotionJson(
			duration = tracks.maxOf { it.second.last().first },
			loop = loop,
			curves = tracks.map { (id, points) -> curve(id, points) } + (if (loop) idleBlink() else emptyList()),
			availableParameterIds = availableParameterIds,
		)
	}

	/** One blink partway through the idle's cycle. */
	private fun idleBlink(): List<Curve> = idleBlinkTracks.map { (id, points) -> curve(id, points) }

	val idleBlinkTracks: List<MotionTrack> = listOf("ParamEyeLOpen", "ParamEyeROpen").map {
		it to listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, SkeletonMotions.IDLE_DURATION to 1f)
	}

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
	val blinkTracks: List<MotionTrack> = listOf("ParamEyeLOpen", "ParamEyeROpen").map {
		it to listOf(0f to 1f, 0.35f to 1f, 0.45f to 0f, 0.58f to 1f, 1.2f to 1f)
	}

	fun nod(): String = nod(ALL_PARAMETERS)!!

	fun nod(availableParameterIds: Set<String>): String? = oneShot(nodTracks, availableParameterIds)

	val nodTracks: List<MotionTrack> = listOf(
		"ParamAngleY" to listOf(0f to 0f, 0.55f to -18f, 1.25f to 6f, 2.0f to 0f),
		"ParamBodyAngleY" to listOf(0f to 0f, 0.55f to -4f, 1.25f to 1.5f, 2.0f to 0f),
		"ParamEyeLOpen" to listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f),
		"ParamEyeROpen" to listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f),
	)

	fun shake(): String = shake(ALL_PARAMETERS)!!

	fun shake(availableParameterIds: Set<String>): String? = oneShot(shakeTracks, availableParameterIds)

	val shakeTracks: List<MotionTrack> = listOf(
		"ParamAngleX" to listOf(0f to 0f, 0.4f to -20f, 0.9f to 20f, 1.4f to -8f, 2.0f to 0f),
		"ParamBodyAngleX" to listOf(0f to 0f, 0.4f to -3f, 0.9f to 3f, 1.4f to -1.2f, 2.0f to 0f),
		"ParamAngleZ" to listOf(0f to 0f, 0.4f to 2f, 0.9f to -2f, 1.4f to 1f, 2.0f to 0f),
	)

	private fun oneShot(tracks: List<MotionTrack>, availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = tracks.maxOf { it.second.last().first },
		loop = false,
		curves = tracks.map { (id, points) -> curve(id, points) },
		availableParameterIds = availableParameterIds,
	)

	private fun buildMotionJson(
		duration: Float,
		loop: Boolean,
		curves: List<Curve>,
		availableParameterIds: Set<String>,
		fps: Float = 30f,
		fadeIn: Float? = null,
		fadeOut: Float? = null,
	): String? {
		val retainedCurves = curves.filter { it.parameter in availableParameterIds }
		if (retainedCurves.isEmpty()) return null
		val segmentCount = retainedCurves.sumOf { it.segmentCount }
		val pointCount = retainedCurves.sumOf { it.pointCount }
		// Same line as Fps: trimIndent runs after interpolation.
		val fades = buildString {
			fadeIn?.let { append(" \"FadeInTime\": $it,") }
			fadeOut?.let { append(" \"FadeOutTime\": $it,") }
		}
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": $duration,
		    "Fps": ${fps.toDouble()},$fades
		    "Loop": $loop,
		    "AreBeziersRestricted": true,
		    "CurveCount": ${retainedCurves.size},
		    "TotalSegmentCount": $segmentCount,
		    "TotalPointCount": $pointCount,
		    "UserDataCount": 0,
		    "TotalUserDataSize": 0
		  },
		  "Curves": [${retainedCurves.joinToString(",") { it.json }}]
		}
		""".trimIndent()
	}

	private data class Curve(val parameter: String, val json: String, val pointCount: Int, val segmentCount: Int)

	private fun curve(parameter: String, points: List<Pair<Float, Float>>): Curve {
		require(points.size >= 2)
		return curve(MotionCurve(parameter, points.map { (time, value) -> MotionKey(time, value) }))
	}

	private fun curve(source: MotionCurve): Curve {
		val first = source.keys.first()
		// A curve needs a segment; a lone key, or one after zero, holds from zero.
		val keys = buildList {
			if (first.time > MotionClips.TIME_EPSILON || source.keys.size == 1) add(first.copy(time = 0f, interpolation = MotionInterpolation.LINEAR))
			addAll(source.keys)
			if (size == 1) add(first.copy(time = maxOf(first.time, MotionClips.TIME_EPSILON)))
		}
		var points = 1
		val segments = buildList<Number> {
			add(keys.first().time)
			add(keys.first().value)
			for ((a, b) in keys.zipWithNext()) {
				add(a.interpolation.ordinal)
				if (a.interpolation == MotionInterpolation.BEZIER) {
					val (c1, c2) = MotionClips.controlPoints(a, b)
					add(c1.first); add(c1.second); add(c2.first); add(c2.second)
					points += 2
				}
				add(b.time)
				add(b.value)
				points += 1
			}
		}.joinToString(",") { number ->
			if (number is Int) number.toString() else number.toFloat().toString()
		}
		return Curve(source.parameterId, """{"Target":"Parameter","Id":"${source.parameterId}","Segments":[$segments]}""", points, keys.size - 1)
	}

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
