package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.motion.MotionAlgorithm

/** Bundled registration for the standalone motion implementation. */
internal object MotionAlgorithmPlugin {
    private val implementation = MotionAlgorithm()
    val definition = RigModifierDefinition("motion", 1, motionOutputs = { setOf(it.outputId("motion")) },
        evaluate = implementation::evaluate, plugin = implementation, changeWrites = setOf("motion", "relations"))
}
