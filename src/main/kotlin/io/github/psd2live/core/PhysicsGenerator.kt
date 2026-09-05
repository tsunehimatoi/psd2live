package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonPrimitive
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
			add(
				hairRule(
					id = "PhysicsHairFront",
					name = tr("model.physics.frontHair"),
					outputParameter = "ParamHairFront",
					outputScale = 1.522f,
					length = 7.9f,
					mobility = 0.77f,
					delay = 1.45f,
					acceleration = 0.8f,
					angleMinimum = -10f,
					angleMaximum = 10f,
				),
			)
		}
		if (hasBackHair) {
			add(
				hairRule(
					id = "PhysicsHairBack",
					name = tr("model.physics.backHair"),
					outputParameter = "ParamHairBack",
					outputScale = 2.061f,
					length = 15f,
					mobility = 0.95f,
					delay = 0.8f,
					acceleration = 1.5f,
					angleMinimum = -30f,
					angleMaximum = 30f,
				),
			)
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

	internal fun validRules(
		hasFrontHair: Boolean,
		hasBackHair: Boolean,
		hasEyeJelly: Boolean,
		availableParameterIds: Set<String>,
	): List<PhysicsRule> = rules(hasFrontHair, hasBackHair, hasEyeJelly).filter { rule ->
		rule.outputParameter in availableParameterIds && rule.inputs.all { it.parameter in availableParameterIds }
	}

	private fun hairRule(
		id: String,
		name: String,
		outputParameter: String,
		outputScale: Float,
		length: Float,
		mobility: Float = 0.95f,
		delay: Float,
		acceleration: Float = 1.5f,
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
			VertexRule(length, mobility, delay, acceleration, length),
		),
		positionMinimum = -10f,
		positionDefault = 0f,
		positionMaximum = 10f,
		angleMinimum = angleMinimum,
		angleDefault = 0f,
		angleMaximum = angleMaximum,
	)

	fun generate(hasFrontHair: Boolean, hasBackHair: Boolean, hasEyeJelly: Boolean = false): String? {
		return generate(hasFrontHair, hasBackHair, hasEyeJelly, null)
	}

	fun generate(
		hasFrontHair: Boolean,
		hasBackHair: Boolean,
		hasEyeJelly: Boolean,
		availableParameterIds: Set<String>?,
        custom: List<RigPhysicsEdit> = emptyList(),
	): String? {
		val presets = if (availableParameterIds == null) {
			rules(hasFrontHair, hasBackHair, hasEyeJelly)
		} else {
			validRules(hasFrontHair, hasBackHair, hasEyeJelly, availableParameterIds)
		}
		val rules = mergeCustomRules(presets, custom, availableParameterIds)
		if (rules.isEmpty()) return null
		val settings = rules.map(::settingJson)
		val dictionary = rules.map { rule -> "{ \"Id\": ${JsonPrimitive(rule.id)}, \"Name\": ${JsonPrimitive(rule.name)} }" }
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

    internal fun mergeCustomRules(presets: List<PhysicsRule>, custom: List<RigPhysicsEdit>, available: Set<String>?): List<PhysicsRule> {
        if (available != null) custom.forEach { it.validate(available) }
        require(custom.map { it.id }.distinct().size == custom.size) { "Duplicate physics IDs" }
        require(custom.map { it.outputParameter }.distinct().size == custom.size) { "Independent physics must use distinct outputs" }
        return presets.filterNot { p -> custom.any { it.id == p.id || it.outputParameter == p.outputParameter } } + custom.map { it.rule() }
    }

	private fun settingJson(rule: PhysicsRule): String {
		val inputs = rule.inputs.joinToString(",\n") { input ->
			"""    { "Source": { "Target": "Parameter", "Id": ${JsonPrimitive(input.parameter)} }, "Weight": ${input.weight}, "Type": "${input.type.jsonName}", "Reflect": ${input.reflect} }"""
		}
		val vertices = rule.vertices.joinToString(",\n") { vertex ->
			"""    { "Position": { "X": 0, "Y": ${vertex.y} }, "Mobility": ${vertex.mobility}, "Delay": ${vertex.delay}, "Acceleration": ${vertex.acceleration}, "Radius": ${vertex.radius} }"""
		}
		return """
		{
		  "Id": ${JsonPrimitive(rule.id)},
		  "Input": [
		$inputs
		  ],
		  "Output": [
		    { "Destination": { "Target": "Parameter", "Id": ${JsonPrimitive(rule.outputParameter)} }, "VertexIndex": ${rule.outputVertexIndex}, "Scale": ${rule.outputScale}, "Weight": 100, "Type": "Angle", "Reflect": false }
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
