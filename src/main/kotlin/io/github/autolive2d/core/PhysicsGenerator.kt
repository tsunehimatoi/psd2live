package io.github.autolive2d.core

/**
 * Live2D physics3 hair presets derived from StretchyStudio's Hiyori-backed rules.
 *
 * The old generator used an output scale of 0.025 and only two Angle-type inputs, which made the
 * nominal physics file practically inert. These rules keep the standard ParamHairFront/Back
 * outputs and use the same short-front / long-back pendulum distinction as the reference models.
 */
object PhysicsGenerator {
	internal data class HairRule(
		val id: String,
		val name: String,
		val outputParameter: String,
		val outputScale: Float,
		val length: Float,
		val delay: Float,
		val angleMinimum: Float,
		val angleMaximum: Float,
	)

	internal fun rules(hasFrontHair: Boolean, hasBackHair: Boolean): List<HairRule> = buildList {
		if (hasFrontHair) {
			add(HairRule("PhysicsHairFront", "前发摆动", "ParamHairFront", 1.522f, 3f, 0.9f, -10f, 10f))
		}
		if (hasBackHair) {
			add(HairRule("PhysicsHairBack", "后发摆动", "ParamHairBack", 2.061f, 15f, 0.8f, -30f, 30f))
		}
	}

	fun generate(hasFrontHair: Boolean, hasBackHair: Boolean): String? {
		val rules = rules(hasFrontHair, hasBackHair)
		if (rules.isEmpty()) return null
		val settings = rules.map(::settingJson)
		val dictionary = rules.map { rule -> "{ \"Id\": \"${rule.id}\", \"Name\": \"${rule.name}\" }" }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "PhysicsSettingCount": ${rules.size},
		    "TotalInputCount": ${rules.size * 4},
		    "TotalOutputCount": ${rules.size},
		    "VertexCount": ${rules.size * 2},
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${settings.joinToString(",")}]
		}
		""".trimIndent()
	}

	private fun settingJson(rule: HairRule): String = """
		{
		  "Id": "${rule.id}",
		  "Input": [
		    { "Source": { "Target": "Parameter", "Id": "ParamAngleX" }, "Weight": 60, "Type": "X", "Reflect": false },
		    { "Source": { "Target": "Parameter", "Id": "ParamAngleZ" }, "Weight": 60, "Type": "Angle", "Reflect": false },
		    { "Source": { "Target": "Parameter", "Id": "ParamBodyAngleX" }, "Weight": 40, "Type": "X", "Reflect": false },
		    { "Source": { "Target": "Parameter", "Id": "ParamBodyAngleZ" }, "Weight": 40, "Type": "Angle", "Reflect": false }
		  ],
		  "Output": [
		    { "Destination": { "Target": "Parameter", "Id": "${rule.outputParameter}" }, "VertexIndex": 1, "Scale": ${rule.outputScale}, "Weight": 100, "Type": "Angle", "Reflect": false }
		  ],
		  "Vertices": [
		    { "Position": { "X": 0, "Y": 0 }, "Mobility": 1, "Delay": 1, "Acceleration": 1, "Radius": 0 },
		    { "Position": { "X": 0, "Y": ${rule.length} }, "Mobility": 0.95, "Delay": ${rule.delay}, "Acceleration": 1.5, "Radius": ${rule.length} }
		  ],
		  "Normalization": {
		    "Position": { "Minimum": -10, "Default": 0, "Maximum": 10 },
		    "Angle": { "Minimum": ${rule.angleMinimum}, "Default": 0, "Maximum": ${rule.angleMaximum} }
		  }
		}
	""".trimIndent()
}
