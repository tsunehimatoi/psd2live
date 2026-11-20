package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.source.ModifierParameterContext
import io.github.psd2live.core.sim.RigSimEdit
import kotlinx.serialization.json.buildJsonObject

/** Bundled in-process algorithm entrypoint. */
internal object SimulationAlgorithmPlugin {
    private val implementation = io.github.psd2live.algorithms.simulation.SimulationAlgorithm()
    val definition = RigModifierDefinition("simulation", 1,
        motionInputs = { RigSimEdit.fromJson(it.config).trainingClips.keys }, validate = { _, instance ->
        require(instance.targets.all { it.kind == "mesh" }) { "A simulation modifier targets meshes" }
    }, composableKinds = setOf("mesh", "hierarchy"), authorContext = {
        ModifierAlgorithmContext(buildJsonObject {
            put(ModifierParameterI18n.CONTEXT_KEY, ModifierParameterContext.i18n().toJsonElement())
        })
    }, plugin = implementation)
}
