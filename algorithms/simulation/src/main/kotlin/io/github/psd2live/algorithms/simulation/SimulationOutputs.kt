package io.github.psd2live.algorithms.simulation

import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.sim.*

object SimulationOutputs {
    fun outputs(instance: RigModifierInstance, source: RigSimEdit): RigSimEdit =
        source.copy(outputs = requireNotNull(source.bake).parameters.associateWith { parameter ->
            val previous = source.outputs[parameter]
            (previous ?: io.github.psd2live.core.sim.SimOutput()).copy(id = instance.outputId("simulation:$parameter"))
        })

    fun physics(instance: RigModifierInstance, edit: RigSimEdit, available: Set<String>): Map<String, RigPhysicsEdit> {
        val valid = SimGenerator.physicsRules(listOf(edit), available).associateBy { it.id }
        return SimGenerator.writtenPendulums(edit).mapIndexedNotNull { index, group ->
            valid[group.id]?.let { group.id to it.copy(id = instance.outputId("physics:$index")) }
        }.toMap()
    }

}
