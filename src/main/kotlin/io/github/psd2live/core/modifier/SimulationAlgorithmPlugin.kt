package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit

/** Bundled in-process algorithm entrypoint. */
internal object SimulationAlgorithmPlugin {
    private val implementation = io.github.psd2live.algorithms.simulation.SimulationAlgorithm()
    val definition = RigModifierDefinition("simulation", 1,
        motionInputs = { RigSimEdit.fromJson(it.config).trainingClips.keys }, validate = { _, instance ->
        require(instance.targets.all { it.kind == "mesh" }) { "A simulation modifier targets meshes" }
    }, composableKinds = setOf("mesh", "hierarchy"), evaluate = implementation::evaluate, plugin = implementation)
}
