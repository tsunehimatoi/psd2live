package io.github.psd2live.core.fit

import io.github.psd2live.core.Side

/** The eyes of the `eye/fitted` base: what the fit read ([fitting], with the reasons an eye was left out) and each read eye's rig. */
class FittedEyes(val fitting: EyeFitting.Result, val rigs: Map<Side, EyeRig>)
