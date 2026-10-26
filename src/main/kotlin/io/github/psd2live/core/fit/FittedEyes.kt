package io.github.psd2live.core.fit

import io.github.psd2live.core.Side

/**
 * The eyes of the `eye/fitted` base: what the fit read ([fitting], with the reasons an eye was left out), each read eye's
 * rig, and the eye modules built on them ([options]).
 */
class FittedEyes(val fitting: EyeFitting.Result, val rigs: Map<Side, EyeRig>, val options: EyeOptions = EyeOptions())
