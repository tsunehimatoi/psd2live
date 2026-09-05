package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsSourceType
import org.umamo.format.cmo3.model.gen.CPhysicsVertex
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import java.util.UUID

/** Writes editable Cubism physics settings into a fresh CMO3 graph. */
internal object Cmo3PhysicsInjector {
	fun inject(root: CModelSource, hasFrontHair: Boolean, hasBackHair: Boolean, hasEyeJelly: Boolean = false, custom: List<RigPhysicsEdit> = emptyList()): Int {
		val physicsSet = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet
			?: error(tr("error.cmo3MissingPhysicsSet"))
		// The pipeline only injects into its own fresh graph, so replace the known empty collection
		// with the exact carray_list type expected by the editor instead of using an unsafe cast.
		val sources = CArrayList<Any?>().also { physicsSet._sourceCubismPhysics = it }
		val parameterSet = root.parameterSourceSet as? CParameterSourceSet
			?: error(tr("error.cmo3MissingParameterSet"))
		val parameters = elements(parameterSet._sources).filterIsInstance<CParameterSource>()
		val parameterById = parameters.associateBy { ((it.id as? Id)?.idstr).orEmpty() }
		val rules = PhysicsGenerator.mergeCustomRules(PhysicsGenerator.validRules(hasFrontHair, hasBackHair, hasEyeJelly, parameterById.keys), custom, parameterById.keys)
		if (rules.isEmpty()) return 0
		for (rule in rules) {
			val output = parameterById[rule.outputParameter]
				?: error(tr("error.cmo3MissingPhysicsOutput", rule.outputParameter))
			val setting = CPhysicsSettingsSource().apply {
				name = rule.name
				guid = guid("CPhysicsSettingsGuid", rule.name)
				id = Id("CPhysicsSettingId").apply { idstr = rule.id }
				inputs = CArrayList<Any?>(
					rule.inputs.map { inputRule -> input(rule, parameterById, inputRule) },
				)
				outputs = CArrayList<Any?>(
					listOf(
						CPhysicsOutput().apply {
							guid = guid("CPhysicsDataGuid", "out_${rule.id}_${rule.outputParameter}")
							destination = output.guid
							vertexIndex = rule.outputVertexIndex
							translationScale = vector(0f, 0f)
							angleScale = rule.outputScale
							weight = 100f
							type = CPhysicsSourceType.SRC_TO_G_ANGLE
							isReverse = false
						},
					),
				)
				vertices = CArrayList<Any?>(
					rule.vertices.mapIndexed { index, vertex -> vertex(rule, index, vertex) },
				)
				normalizedPositionValueMax = rule.positionMaximum
				normalizedPositionValueMin = rule.positionMinimum
				normalizedPositionDefaultValue = rule.positionDefault
				normalizedAngleValueMax = rule.angleMaximum
				normalizedAngleValueMin = rule.angleMinimum
				normalizedAngleDefaultValue = rule.angleDefault
			}
			sources.add(setting)
		}
		physicsSet.selectedCubismPhysics = guid("CPhysicsSettingsGuid", "physics-selection")
		physicsSet.settingFPS = 120
		return rules.size
	}

	private fun input(
		rule: PhysicsGenerator.PhysicsRule,
		parameterById: Map<String, CParameterSource>,
		input: PhysicsGenerator.InputRule,
	): CPhysicsInput {
		val parameter = parameterById[input.parameter] ?: error(tr("error.cmo3MissingPhysicsInput", input.parameter))
		return CPhysicsInput().apply {
			guid = guid("CPhysicsDataGuid", "in_${rule.id}_${input.parameter}")
			source = parameter.guid
			angleScale = 0f
			translationScale = vector(0f, 0f)
			weight = input.weight
			type = when (input.type) {
				PhysicsGenerator.InputType.X -> CPhysicsSourceType.SRC_TO_X
				PhysicsGenerator.InputType.ANGLE -> CPhysicsSourceType.SRC_TO_G_ANGLE
			}
			isReverse = input.reflect
		}
	}

	private fun vertex(rule: PhysicsGenerator.PhysicsRule, index: Int, vertex: PhysicsGenerator.VertexRule): CPhysicsVertex =
		CPhysicsVertex().apply {
			guid = guid("CPhysicsDataGuid", "v${index}_${rule.id}")
			position = vector(0f, vertex.y)
			mobility = vertex.mobility
			delay = vertex.delay
			acceleration = vertex.acceleration
			radius = vertex.radius
		}

	private fun vector(x: Float, y: Float): GVector2 = GVector2().apply {
		this.x = x
		this.y = y
	}

	private fun guid(kind: String, note: String): Guid = Guid(kind).apply {
		uuid = UUID.randomUUID().toString()
		this.note = note
	}

	private fun elements(value: Any?): List<Any?> = when (value) {
		is Iterable<*> -> value.toList()
		is Array<*> -> value.toList()
		else -> emptyList()
	}
}
