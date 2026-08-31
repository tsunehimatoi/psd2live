package io.github.autolive2d.core

object PhysicsGenerator {
	fun generate(hasFrontHair: Boolean, hasBackHair: Boolean): String? {
		val outputs = buildList {
			if (hasFrontHair) add("ParamHairFront" to "前发")
			if (hasBackHair) add("ParamHairBack" to "后发")
		}
		if (outputs.isEmpty()) return null
		val settings = outputs.mapIndexed { index, (parameter, name) ->
			"""
			{
			  "Id": "PhysicsHair${index + 1}",
			  "Input": [
			    { "Source": { "Target": "Parameter", "Id": "ParamAngleX" }, "Weight": 55, "Type": "Angle", "Reflect": false },
			    { "Source": { "Target": "Parameter", "Id": "ParamBodyAngleX" }, "Weight": 25, "Type": "Angle", "Reflect": false }
			  ],
			  "Output": [
			    { "Destination": { "Target": "Parameter", "Id": "$parameter" }, "VertexIndex": 2, "Scale": 0.025, "Weight": 100, "Type": "Angle", "Reflect": ${index % 2 == 1} }
			  ],
			  "Vertices": [
			    { "Position": { "X": 0, "Y": 0 }, "Mobility": 0.85, "Delay": 0.2, "Acceleration": 1, "Radius": 0 },
			    { "Position": { "X": 0, "Y": 12 }, "Mobility": 0.8, "Delay": 0.35, "Acceleration": 0.9, "Radius": 12 },
			    { "Position": { "X": 0, "Y": 24 }, "Mobility": 0.72, "Delay": 0.5, "Acceleration": 0.8, "Radius": 12 }
			  ],
			  "Normalization": {
			    "Position": { "Minimum": -10, "Default": 0, "Maximum": 10 },
			    "Angle": { "Minimum": -30, "Default": 0, "Maximum": 30 }
			  }
			}
			""".trimIndent()
		}
		val dictionary = outputs.mapIndexed { index, (_, name) -> "{ \"Id\": \"PhysicsHair${index + 1}\", \"Name\": \"$name 摆动\" }" }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "PhysicsSettingCount": ${settings.size},
		    "TotalInputCount": ${settings.size * 2},
		    "TotalOutputCount": ${settings.size},
		    "VertexCount": ${settings.size * 3},
		    "Fps": 60,
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${settings.joinToString(",")}]
		}
		""".trimIndent()
	}
}

