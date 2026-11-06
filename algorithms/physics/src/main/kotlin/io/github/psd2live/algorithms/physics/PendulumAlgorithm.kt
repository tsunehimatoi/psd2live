package io.github.psd2live.algorithms.physics

import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.*

/** Complete physics-group implementation; no application services or mutable workspace access. */
class PendulumAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        val edit = RigPhysicsEdit.fromJson(instance.config).copy(id = instance.outputId("physics"))
        require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { input.parameter(it) != null }) {
            "${instance.id}: physics inputs and outputs must reference existing parameters"
        }
        val groups = listOf(PhysicsGroups.fromSetting(edit))
        PhysicsGroups.validateAppend(input.physics, groups)
        return ModifierModelChanges(physicsGroups = ModifierObjectChanges(added = groups))
    }
}

/** Shared neutral conversion and conflict validation for generated and authored physics groups. */
object PhysicsGroups {
    fun fromSetting(setting: RigPhysicsEdit): PhysicsGroup = PhysicsGroup(setting.id, setting.name,
        setting.inputs.map { PhysicsInput(it.parameter, it.weight, source(it.type), it.reflect) },
        setting.outputs.map { PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, source(it.type), it.reflect) },
        setting.segments.map { PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
        setting.normalization.let { PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax,
            it.angleMin, it.angleDefault, it.angleMax) })

    private fun source(type: PhysicsSourceType) = if (type == PhysicsSourceType.ANGLE) PhysicsSource.ANGLE else PhysicsSource.X

    fun validateAppend(input: RigIR, groups: List<PhysicsGroup>) = validateAppend(input.physics, groups)

    fun validateAppend(input: Physics, groups: List<PhysicsGroup>) {
        val ids = input.groups.mapTo(HashSet()) { it.id }
        require(groups.map { it.id }.distinct().size == groups.size && groups.none { it.id in ids }) { "Physics group ID collision" }
        val claimed = input.groups.flatMapTo(HashSet()) { group -> group.outputs.map { it.parameter } }
        for (group in groups) for (output in group.outputs) require(claimed.add(output.parameter)) {
            "Multiple physics groups drive ${output.parameter}"
        }
    }

    fun append(input: RigIR, groups: List<PhysicsGroup>): RigIR {
        validateAppend(input, groups)
        return input.copy(physics = input.physics.copy(groups = input.physics.groups + groups))
    }
}
