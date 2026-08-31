package io.github.autolive2d.core

/** A quiet looping demonstration motion that exercises the generated rig without audio assets. */
object MotionGenerator {
	fun idle(): String {
		val curves = listOf(
			curve("ParamBreath", listOf(0f to 0f, 1.5f to 1f, 3f to 0f, 4.5f to 1f, 6f to 0f)),
			curve("ParamAngleZ", listOf(0f to -2f, 1.5f to 2f, 3f to -2f, 4.5f to 2f, 6f to -2f)),
			curve("ParamBodyAngleX", listOf(0f to -1.2f, 3f to 1.2f, 6f to -1.2f)),
			curve("ParamEyeLOpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
			curve("ParamEyeROpen", listOf(0f to 1f, 2.7f to 1f, 2.78f to 0f, 2.88f to 1f, 6f to 1f)),
		)
		val segmentCount = curves.sumOf { it.pointCount - 1 }
		val pointCount = curves.sumOf { it.pointCount }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": 6.0,
		    "Fps": 30.0,
		    "Loop": true,
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
