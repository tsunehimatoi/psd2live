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

	/** An output on a pendulum vertex; past the first vertex the angle is relative to the segment above. */
	internal data class OutputRule(val parameter: String, val vertexIndex: Int, val scale: Float)

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
		val extraOutputs: List<OutputRule> = emptyList(),
	) {
		val outputs: List<OutputRule> get() = listOf(OutputRule(outputParameter, outputVertexIndex, outputScale)) + extraOutputs
	}

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

	/**
	 * Follow-through on the loose appendages: every tail segment after the first trails the one it hangs
	 * from, and wings trail the body. Arms and legs stay off physics - they are posed and animated
	 * directly, and a simulation would overwrite both. Each output is unique and a user edit overrides it.
	 */
	internal fun skeletonRules(spec: SkeletonSpec?, available: Set<String>): List<PhysicsRule> {
		if (spec?.enabled != true) return emptyList()
		return spec.bones.filter { it.role == BoneRole.WING || (it.role == BoneRole.TAIL && it.chainIndex > 1) }
			.mapNotNull { bone ->
				val input = when (bone.role) {
					BoneRole.TAIL -> spec.bone(bone.parentId ?: "")?.takeIf { it.role == BoneRole.TAIL }?.parameterId
						?: "ParamBodyAngleZ"
					else -> "ParamBodyAngleZ"
				}
				if (input !in available || bone.parameterId !in available || input == bone.parameterId) return@mapNotNull null
				RigPhysicsEdit("PhysicsSkel_${bone.id}", bone.name, input, bone.parameterId,
					length = (bone.length / 30f).coerceIn(3f, 16f), mobility = 0.55f,
					delay = if (bone.role == BoneRole.TAIL) 0.65f else 0.9f,
					acceleration = 0.8f, outputScale = if (bone.role == BoneRole.TAIL) 0.75f else 0.4f).rule()
			}
	}

	/**
	 * One pendulum per regenerating swing, with a vertex per segment. Left/right swings take the hair
	 * inputs. A rigid pendulum hanging straight down ignores vertical travel, so up/down swings feed head
	 * and body pitch in as sideways travel, and the resulting angle drives the up/down forms.
	 */
	internal fun swingRules(swings: List<RigSwingEdit>, available: Set<String>): List<PhysicsRule> = swings.mapNotNull { swing ->
		val physics = swing.physics ?: return@mapNotNull null
		if (swing.parameterIds.any { it !in available }) return@mapNotNull null
		val inputs = when (swing.kind) {
			SwingKind.LATERAL -> listOf(
				InputRule("ParamAngleX", 60f, InputType.X),
				InputRule("ParamAngleZ", 60f, InputType.ANGLE),
				InputRule("ParamBodyAngleX", 40f, InputType.X),
				InputRule("ParamBodyAngleZ", 40f, InputType.ANGLE),
			)
			SwingKind.VERTICAL -> listOf(
				InputRule("ParamAngleY", 60f, InputType.X),
				InputRule("ParamBodyAngleY", 40f, InputType.X),
				InputRule("ParamAngleZ", 20f, InputType.ANGLE),
			)
		}.filter { it.parameter in available && it.parameter !in swing.parameterIds }
		if (inputs.isEmpty()) return@mapNotNull null
		val segment = physics.length / swing.segments
		PhysicsRule(
			id = swingPhysicsId(swing),
			name = swing.name,
			outputParameter = swing.parameterIds.first(),
			outputScale = physics.outputScale,
			outputVertexIndex = 1,
			inputs = inputs,
			vertices = listOf(VertexRule(0f, 1f, 1f, 1f, 0f)) + (1..swing.segments).map { k ->
				VertexRule(segment * k, physics.mobility, physics.delay, physics.acceleration, segment)
			},
			positionMinimum = -10f,
			positionDefault = 0f,
			positionMaximum = 10f,
			angleMinimum = -30f,
			angleDefault = 0f,
			angleMaximum = 30f,
			extraOutputs = swing.parameterIds.drop(1).mapIndexed { k, id -> OutputRule(id, k + 2, physics.outputScale) },
		)
	}

	internal fun swingPhysicsId(swing: RigSwingEdit) = "PhysicsSwing_${swing.id}"

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
		skeleton: SkeletonSpec? = null,
		swings: List<RigSwingEdit> = emptyList(),
	): String? {
		val presets = if (availableParameterIds == null) {
			rules(hasFrontHair, hasBackHair, hasEyeJelly)
		} else {
			validRules(hasFrontHair, hasBackHair, hasEyeJelly, availableParameterIds) + skeletonRules(skeleton, availableParameterIds) +
				swingRules(swings, availableParameterIds)
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
		    "TotalOutputCount": ${rules.sumOf { it.outputs.size }},
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
        return presets.filterNot { p -> custom.any { c -> c.id == p.id || p.outputs.any { it.parameter == c.outputParameter } } } + custom.map { it.rule() }
    }

	private fun settingJson(rule: PhysicsRule): String {
		val inputs = rule.inputs.joinToString(",\n") { input ->
			"""    { "Source": { "Target": "Parameter", "Id": ${JsonPrimitive(input.parameter)} }, "Weight": ${input.weight}, "Type": "${input.type.jsonName}", "Reflect": ${input.reflect} }"""
		}
		val vertices = rule.vertices.joinToString(",\n") { vertex ->
			"""    { "Position": { "X": 0, "Y": ${vertex.y} }, "Mobility": ${vertex.mobility}, "Delay": ${vertex.delay}, "Acceleration": ${vertex.acceleration}, "Radius": ${vertex.radius} }"""
		}
		val outputs = rule.outputs.joinToString(",\n") { output ->
			"""    { "Destination": { "Target": "Parameter", "Id": ${JsonPrimitive(output.parameter)} }, "VertexIndex": ${output.vertexIndex}, "Scale": ${output.scale}, "Weight": 100, "Type": "Angle", "Reflect": false }"""
		}
		return """
		{
		  "Id": ${JsonPrimitive(rule.id)},
		  "Input": [
		$inputs
		  ],
		  "Output": [
		$outputs
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
