package io.github.psd2live.core

/** Demonstration motions that exercise the generated rig without audio assets. */
object MotionGenerator {
	fun idle(): String = buildMotionJson(
		duration = 6.0f,
		loop = true,
		curves = listOf(
			curve("ParamBreath", listOf(0f to 0f, 1.5f to 1f, 3f to 0f, 4.5f to 1f, 6f to 0f)),
			curve("ParamAngleZ", listOf(0f to -2f, 1.5f to 2f, 3f to -2f, 4.5f to 2f, 6f to -2f)),
			curve("ParamBodyAngleX", listOf(0f to -1.2f, 3f to 1.2f, 6f to -1.2f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
		),
	)

	fun blink(): String = buildMotionJson(
		duration = 1.2f,
		loop = false,
		curves = listOf(
			curve("ParamEyeLOpen", listOf(0f to 1f, 0.35f to 1f, 0.45f to 0f, 0.58f to 1f, 1.2f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 0.35f to 1f, 0.45f to 0f, 0.58f to 1f, 1.2f to 1f)),
		),
	)

	fun nod(): String = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleY", listOf(0f to 0f, 0.55f to -18f, 1.25f to 6f, 2.0f to 0f)),
			curve("ParamBodyAngleY", listOf(0f to 0f, 0.55f to -4f, 1.25f to 1.5f, 2.0f to 0f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 0.55f to 0.75f, 1.25f to 1f, 2.0f to 1f)),
		),
	)

	fun shake(): String = buildMotionJson(
		duration = 2.0f,
		loop = false,
		curves = listOf(
			curve("ParamAngleX", listOf(0f to 0f, 0.4f to -20f, 0.9f to 20f, 1.4f to -8f, 2.0f to 0f)),
			curve("ParamBodyAngleX", listOf(0f to 0f, 0.4f to -3f, 0.9f to 3f, 1.4f to -1.2f, 2.0f to 0f)),
			curve("ParamAngleZ", listOf(0f to 0f, 0.4f to 2f, 0.9f to -2f, 1.4f to 1f, 2.0f to 0f)),
		),
	)

	private fun buildMotionJson(duration: Float, loop: Boolean, curves: List<Curve>): String {
		val segmentCount = curves.sumOf { it.pointCount - 1 }
		val pointCount = curves.sumOf { it.pointCount }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": $duration,
		    "Fps": 30.0,
		    "Loop": $loop,
		    "AreBeziersRestricted": true,
		    "CurveCount": ${curves.size},
		    "TotalSegmentCount": $segmentCount,
		    "TotalPointCount": $pointCount,
		    "UserDataCount": 0,
		    "TotalUserDataSize": 0
		  },
		  "Curves": [${curves.joinToString(",") { it.json }}]
		}
		""".trimIndent()
	}

	private data class Curve(val json: String, val pointCount: Int)

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
		return Curve("""{"Target":"Parameter","Id":"$parameter","Segments":[$segments]}""", points.size)
	}
}
