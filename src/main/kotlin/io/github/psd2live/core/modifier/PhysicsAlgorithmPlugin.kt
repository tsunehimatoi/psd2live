package io.github.psd2live.core.modifier

import io.github.psd2live.core.*

/** Bundled in-process algorithm entrypoint. */
internal object PhysicsAlgorithmPlugin {
    val definition = RigModifierDefinition("physics", 1, evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val edit = RigPhysicsEdit.fromJson(instance.config).copy(id = instance.outputId("physics"))
        val available = rig.parameters.mapTo(HashSet()) { it.id }
        require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available }) {
            "${instance.id}: physics inputs and outputs must reference existing parameters"
        }
        RigModifierAlgorithms.addPhysics(rig, listOf(Physics3Json.group(edit)))
    })

}
