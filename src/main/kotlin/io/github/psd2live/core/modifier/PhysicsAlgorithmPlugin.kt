package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.physics.PendulumAlgorithm

/** Bundled in-process algorithm entrypoint. */
internal object PhysicsAlgorithmPlugin {
    private val implementation = PendulumAlgorithm()
    val definition = RigModifierDefinition("physics", 1, evaluate = implementation::evaluate, plugin = implementation)

}
