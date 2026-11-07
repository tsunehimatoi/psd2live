package io.github.psd2live.core.fit

import io.github.psd2live.algorithms.binding.EyeRig

import io.github.psd2live.core.Side

/**
 * The eyes of the `eye/fitted` base: what the fit read ([fitting], with the reasons an eye was left out), each read eye's
 * rig, and the eye modules built on them ([options]).
 */
class FittedEyes(val fitting: EyeFitting.Result, rigs: Map<Side, EyeRig>, options: EyeOptions = EyeOptions()) {
    val motion = io.github.psd2live.algorithms.binding.FittedEyeMotion(rigs, options)
    val rigs get() = motion.rigs
    val options get() = motion.options
    fun sideAt(layerId: String, x: Float, y: Float) = motion.sideAt(layerId, x, y)
}
