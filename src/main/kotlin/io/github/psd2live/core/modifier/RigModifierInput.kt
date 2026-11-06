package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR
import io.github.psd2live.core.sim.RigSimEdit

internal object RigModifierInput {
    fun fingerprint(rig: RigIR): String = SimulationInput.fingerprint(rig)
    fun simulationFingerprint(rig: RigIR, edit: RigSimEdit): String = SimulationInput.simulationFingerprint(rig, edit)
    fun simulationMatches(rig: RigIR, edit: RigSimEdit, expected: String?): Boolean = SimulationInput.simulationMatches(rig, edit, expected)
}
