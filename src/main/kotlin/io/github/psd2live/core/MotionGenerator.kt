package io.github.psd2live.core

/** Demonstration motions that exercise the generated rig without audio assets. */
object MotionGenerator {
	fun idle(): String = idle(ALL_PARAMETERS)!!

	fun idle(availableParameterIds: Set<String>): String? = idle(availableParameterIds, null)

	/**
	 * The idle animation. With a [skeleton], the body's single side-to-side lean is replaced by
	 * per-joint drift from [SkeletonMotions] — the limbs, tail and weight each move on their own phase
	 * instead of the whole figure swaying as one piece.
	 */
	fun idle(availableParameterIds: Set<String>, skeleton: Skeleton?): String? {
		val face = listOf(
			curve("ParamAngleZ", listOf(0f to -2f, 1.5f to 2f, 3f to -2f, 4.5f to 2f, 6f to -2f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
		)
		val body = if (skeleton == null || skeleton.isEmpty) {
			listOf(
				curve("ParamBreath", listOf(0f to 0f, 1.5f to 1f, 3f to 0f, 4.5f to 1f, 6f to 0f)),
				curve("ParamBodyAngleX", listOf(0f to -1.2f, 3f to 1.2f, 6f to -1.2f)),
			)
		} else {
			SkeletonMotions.idle(skeleton).map { (parameter, points) -> curve(parameter, points) }
		}
		return buildMotionJson(
			duration = SkeletonMotions.IDLE_DURATION,
			loop = true,
			curves = face + body,
			availableParameterIds = availableParameterIds,
		)
	}

	/** A tail swing, or null for a character whose skeleton has no tail. */
	fun tailSwing(availableParameterIds: Set<String>, skeleton: Skeleton?): String? =
		skeletonMotion(availableParameterIds, skeleton, duration = 3f, SkeletonMotions::tailSwing)

	/** A dip and recovery on the crouch joint. */
	fun crouch(availableParameterIds: Set<String>, skeleton: Skeleton?): String? =
		skeletonMotion(availableParameterIds, skeleton, duration = 1.8f, SkeletonMotions::crouch)

	/** A weight shift onto one foot and back. */
	fun weightShift(availableParameterIds: Set<String>, skeleton: Skeleton?): String? =
		skeletonMotion(availableParameterIds, skeleton, duration = 3f, SkeletonMotions::weightShift)

	private fun skeletonMotion(
		availableParameterIds: Set<String>,
		skeleton: Skeleton?,
		duration: Float,
		tracks: (Skeleton) -> List<MotionTrack>,
	): String? {
		if (skeleton == null || skeleton.isEmpty) return null
		val curves = tracks(skeleton).map { (parameter, points) -> curve(parameter, points) }
		if (curves.isEmpty()) return null
		return buildMotionJson(duration, loop = false, curves = curves, availableParameterIds = availableParameterIds)
	}

	fun blink(): String = blink(ALL_PARAMETERS)!!

	fun blink(availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = 1.2f,
		loop = false,
		curves = listOf(
			curve("ParamEyeLOpen", listOf(0f to 1f, 0.35f to 1f, 0.45f to 0f, 0.58f to 1f, 1.2f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 0.35f to 1f, 0.45f to 0f, 0.58f to 1f, 1.2f to 1f)),
		),
		availableParameterIds = availableParameterIds,
	)

	fun nod(): String = nod(ALL_PARAMETERS)!!

	fun nod(availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleY", listOf(0f to 0f, 0.55f to -18f, 1.25f to 6f, 2.0f to 0f)),
			curve("ParamBodyAngleY", listOf(0f to 0f, 0.55f to -4f, 1.25f to 1.5f, 2.0f to 0f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
		),
		availableParameterIds = availableParameterIds,
	)

	fun shake(): String = shake(ALL_PARAMETERS)!!

	fun shake(availableParameterIds: Set<String>): String? = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleX", listOf(0f to 0f, 0.4f to -20f, 0.9f to 20f, 1.4f to -8f, 2.0f to 0f)),
			curve("ParamBodyAngleX", listOf(0f to 0f, 0.4f to -3f, 0.9f to 3f, 1.4f to -1.2f, 2.0f to 0f)),
			curve("ParamAngleZ", listOf(0f to 0f, 0.4f to 2f, 0.9f to -2f, 1.4f to 1f, 2.0f to 0f)),
		),
		availableParameterIds = availableParameterIds,
	)

	private fun buildMotionJson(
		duration: Float,
		loop: Boolean,
		curves: List<Curve>,
		availableParameterIds: Set<String>,
	): String? {
		val retainedCurves = curves.filter { it.parameter in availableParameterIds }
		if (retainedCurves.isEmpty()) return null
		val segmentCount = retainedCurves.sumOf { it.pointCount - 1 }
		val pointCount = retainedCurves.sumOf { it.pointCount }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": $duration,
		    "Fps": 30.0,
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

	private data class Curve(val parameter: String, val json: String, val pointCount: Int)

	private fun curve(parameter: String, points: List<Pair<Float, Float>>): Curve {
		require(points.size >= 2)
		val segments = buildList<Number> {
			add(points.first().first)
			add(points.first().second)
			for ((time, value) in points.drop(1)) {
				add(0) // linear segment
				add(time)
				add(value)
			}
		}.joinToString(",") { number ->
			if (number is Int) number.toString() else number.toFloat().toString()
		}
		return Curve(parameter, """{"Target":"Parameter","Id":"$parameter","Segments":[$segments]}""", points.size)
	}

	private val ALL_PARAMETERS = setOf(
		"ParamBreath",
		"ParamAngleX",
		"ParamAngleY",
		"ParamAngleZ",
		"ParamBodyAngleX",
		"ParamBodyAngleY",
		"ParamEyeLOpen",
		"ParamEyeROpen",
	)
}
