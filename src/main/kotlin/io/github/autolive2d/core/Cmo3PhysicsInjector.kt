package io.github.autolive2d.core

import io.github.autolive2d.i18n.tr
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
	fun inject(root: CModelSource, hasFrontHair: Boolean, hasBackHair: Boolean): Int {
		val rules = PhysicsGenerator.rules(hasFrontHair, hasBackHair)
		val physicsSet = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet
			?: error(tr("error.cmo3MissingPhysicsSet"))
		// The pipeline only injects into its own fresh graph, so replace the known empty collection
		// with the exact carray_list type expected by the editor instead of using an unsafe cast.
		val sources = CArrayList<Any?>().also { physicsSet._sourceCubismPhysics = it }
		if (rules.isEmpty()) return 0

		val parameterSet = root.parameterSourceSet as? CParameterSourceSet
			?: error(tr("error.cmo3MissingParameterSet"))
		val parameters = elements(parameterSet._sources).filterIsInstance<CParameterSource>()
		val parameterById = parameters.associateBy { ((it.id as? Id)?.idstr).orEmpty() }
		for (rule in rules) {
			val output = parameterById[rule.outputParameter]
				?: error(tr("error.cmo3MissingPhysicsOutput", rule.outputParameter))
			val setting = CPhysicsSettingsSource().apply {
				name = rule.name
				guid = guid("CPhysicsSettingsGuid", rule.name)
				id = Id("CPhysicsSettingId").apply { idstr = rule.id }
				inputs = CArrayList<Any?>(
					listOf(
						input(rule, parameterById, "ParamAngleX", CPhysicsSourceType.SRC_TO_X, 60f),
						input(rule, parameterById, "ParamAngleZ", CPhysicsSourceType.SRC_TO_G_ANGLE, 60f),
						input(rule, parameterById, "ParamBodyAngleX", CPhysicsSourceType.SRC_TO_X, 40f),
						input(rule, parameterById, "ParamBodyAngleZ", CPhysicsSourceType.SRC_TO_G_ANGLE, 40f),
					),
				)
				outputs = CArrayList<Any?>(
					listOf(
						CPhysicsOutput().apply {
							guid = guid("CPhysicsDataGuid", "out_${rule.id}_${rule.outputParameter}")
							destination = output.guid
							vertexIndex = 1
							translationScale = vector(0f, 0f)
							angleScale = rule.outputScale
							weight = 100f
							type = CPhysicsSourceType.SRC_TO_G_ANGLE
							isReverse = false
						},
					),
				)
				vertices = CArrayList<Any?>(
					listOf(
						vertex(rule, 0, 0f, 1f),
						vertex(rule, 1, rule.length, rule.delay),
					),
				)
				normalizedPositionValueMax = 10f
				normalizedPositionValueMin = -10f
				normalizedPositionDefaultValue = 0f
				normalizedAngleValueMax = rule.angleMaximum
				normalizedAngleValueMin = rule.angleMinimum
				normalizedAngleDefaultValue = 0f
			}
			sources.add(setting)
		}
		physicsSet.selectedCubismPhysics = guid("CPhysicsSettingsGuid", "physics-selection")
		physicsSet.settingFPS = 120
		return rules.size
	}

	private fun input(
		rule: PhysicsGenerator.HairRule,
		parameterById: Map<String, CParameterSource>,
		parameterId: String,
		type: CPhysicsSourceType,
		weight: Float,
	): CPhysicsInput {
		val parameter = parameterById[parameterId] ?: error(tr("error.cmo3MissingPhysicsInput", parameterId))
		return CPhysicsInput().apply {
			guid = guid("CPhysicsDataGuid", "in_${rule.id}_$parameterId")
			source = parameter.guid
			angleScale = 0f
			translationScale = vector(0f, 0f)
			this.weight = weight
			this.type = type
			isReverse = false
		}
	}

	private fun vertex(rule: PhysicsGenerator.HairRule, index: Int, y: Float, delay: Float): CPhysicsVertex =
		CPhysicsVertex().apply {
			guid = guid("CPhysicsDataGuid", "v${index}_${rule.id}")
			position = vector(0f, y)
			mobility = if (index == 0) 1f else 0.95f
			this.delay = delay
			acceleration = if (index == 0) 1f else 1.5f
			radius = y
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
