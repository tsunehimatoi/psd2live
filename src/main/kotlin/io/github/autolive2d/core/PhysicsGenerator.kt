package io.github.autolive2d.core

import io.github.autolive2d.i18n.tr
/** Live2D physics3 presets for hair pendulums and blink-driven pupil squash/stretch. */
object PhysicsGenerator {
	internal enum class InputType(val jsonName: String) { X("X"), ANGLE("Angle") }

	internal data class InputRule(
		val parameter: String,
		val weight: Float,
		val type: InputType,
		val reflect: Boolean = false,
	)

	internal data class VertexRule(
		val y: Float,
		val mobility: Float,
		val delay: Float,
		val acceleration: Float,
		val radius: Float,
	)

	internal data class PhysicsRule(
		val id: String,
		val name: String,
		val outputParameter: String,
		val outputScale: Float,
		val outputVertexIndex: Int,
		val inputs: List<InputRule>,
		val vertices: List<VertexRule>,
		val positionMinimum: Float,
		val positionDefault: Float,
		val positionMaximum: Float,
		val angleMinimum: Float,
		val angleDefault: Float,
		val angleMaximum: Float,
	)

	internal fun rules(hasFrontHair: Boolean, hasBackHair: Boolean, hasEyeJelly: Boolean): List<PhysicsRule> = buildList {
		if (hasFrontHair) {
			add(hairRule("PhysicsHairFront", tr("model.physics.frontHair"), "ParamHairFront", 1.522f, 3f, 0.9f, -10f, 10f))
		}
		if (hasBackHair) {
			add(hairRule("PhysicsHairBack", tr("model.physics.backHair"), "ParamHairBack", 2.061f, 15f, 0.8f, -30f, 30f))
		}
		if (hasEyeJelly) {
			add(
				PhysicsRule(
					id = "PhysicsEyeJelly",
					name = tr("model.physics.eyeJelly"),
					outputParameter = "ParamEyeBallForm",
					outputScale = 0.32f,
					outputVertexIndex = 2,
					inputs = listOf(
						InputRule("ParamEyeLOpen", 50f, InputType.X),
						InputRule("ParamEyeROpen", 50f, InputType.X),
					),
					vertices = listOf(
						VertexRule(0f, 1f, 1f, 1f, 0f),
						VertexRule(1f, 0.88f, 0.18f, 1.9f, 1f),
						VertexRule(2f, 0.80f, 0.32f, 2.2f, 1f),
					),
					positionMinimum = -1f,
					positionDefault = 0f,
					positionMaximum = 1f,
					angleMinimum = -10f,
					angleDefault = 0f,
					angleMaximum = 10f,
				),
			)
		}
	}

	private fun hairRule(
		id: String,
		name: String,
		outputParameter: String,
		outputScale: Float,
		length: Float,
		delay: Float,
		angleMinimum: Float,
		angleMaximum: Float,
	): PhysicsRule = PhysicsRule(
		id = id,
		name = name,
		outputParameter = outputParameter,
		outputScale = outputScale,
		outputVertexIndex = 1,
		inputs = listOf(
			InputRule("ParamAngleX", 60f, InputType.X),
			InputRule("ParamAngleZ", 60f, InputType.ANGLE),
			InputRule("ParamBodyAngleX", 40f, InputType.X),
			InputRule("ParamBodyAngleZ", 40f, InputType.ANGLE),
		),
		vertices = listOf(
			VertexRule(0f, 1f, 1f, 1f, 0f),
			VertexRule(length, 0.95f, delay, 1.5f, length),
		),
		positionMinimum = -10f,
		positionDefault = 0f,
		positionMaximum = 10f,
		angleMinimum = angleMinimum,
		angleDefault = 0f,
		angleMaximum = angleMaximum,
	)

	fun generate(hasFrontHair: Boolean, hasBackHair: Boolean, hasEyeJelly: Boolean = false): String? {
		val rules = rules(hasFrontHair, hasBackHair, hasEyeJelly)
		if (rules.isEmpty()) return null
		val settings = rules.map(::settingJson)
		val dictionary = rules.map { rule -> "{ \"Id\": \"${rule.id}\", \"Name\": \"${rule.name}\" }" }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "PhysicsSettingCount": ${rules.size},
		    "TotalInputCount": ${rules.sumOf { it.inputs.size }},
		    "TotalOutputCount": ${rules.size},
		    "VertexCount": ${rules.sumOf { it.vertices.size }},
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${settings.joinToString(",")}]
		}
		""".trimIndent()
	}

	private fun settingJson(rule: PhysicsRule): String {
		val inputs = rule.inputs.joinToString(",\n") { input ->
			"""    { "Source": { "Target": "Parameter", "Id": "${input.parameter}" }, "Weight": ${input.weight}, "Type": "${input.type.jsonName}", "Reflect": ${input.reflect} }"""
		}
		val vertices = rule.vertices.joinToString(",\n") { vertex ->
			"""    { "Position": { "X": 0, "Y": ${vertex.y} }, "Mobility": ${vertex.mobility}, "Delay": ${vertex.delay}, "Acceleration": ${vertex.acceleration}, "Radius": ${vertex.radius} }"""
		}
		return """
		{
		  "Id": "${rule.id}",
		  "Input": [
		$inputs
		  ],
		  "Output": [
		    { "Destination": { "Target": "Parameter", "Id": "${rule.outputParameter}" }, "VertexIndex": ${rule.outputVertexIndex}, "Scale": ${rule.outputScale}, "Weight": 100, "Type": "Angle", "Reflect": false }
		  ],
		  "Vertices": [
		$vertices
		  ],
		  "Normalization": {
		    "Position": { "Minimum": ${rule.positionMinimum}, "Default": ${rule.positionDefault}, "Maximum": ${rule.positionMaximum} },
		    "Angle": { "Minimum": ${rule.angleMinimum}, "Default": ${rule.angleDefault}, "Maximum": ${rule.angleMaximum} }
		  }
		}
		""".trimIndent()
	}
}
