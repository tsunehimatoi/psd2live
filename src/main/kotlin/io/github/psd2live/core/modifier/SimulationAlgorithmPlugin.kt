package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.targets.cubism.PuppetIr

/** Bundled in-process algorithm entrypoint. */
internal object SimulationAlgorithmPlugin {
    val definition = RigModifierDefinition("simulation", 1,
        motionInputs = { RigSimEdit.fromJson(it.config).trainingClips.keys }, validate = { _, instance ->
        require(instance.targets.all { it.kind == "mesh" }) { "A simulation modifier targets meshes" }
    }, composableKinds = setOf("mesh", "hierarchy"), evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = RigSimEdit.fromJson(instance.config)
        require(source.enabled && source.bake != null) { "A simulation modifier needs an enabled, valid bake" }
        require(source.targets == instance.targets.map { it.id }) { "Simulation targets changed; bake again before adding the modifier" }
        val weighted = RigSimulationWeights.apply(PuppetIr.toPuppet(rig), instance.config)
        require(!io.github.psd2live.core.sim.SimBake.stale(weighted, source)) { "Simulation inputs changed; bake again before adding the modifier" }
        require(RigModifierInput.simulationMatches(rig, io.github.psd2live.core.sim.RigSimEdit.fromJson(instance.config), instance.inputFingerprint)) {
            "${instance.id}: upstream modifier input changed; bake the simulation again"
        }
        val edit = RigModifierAlgorithms.simulationOutputs(instance, source)
        val (model, issues) = SimGenerator.applyOne(weighted, edit)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        checkpoint()
        val groups = RigModifierAlgorithms.simulationPhysics(instance, edit, model.parameters.mapTo(HashSet()) { it.id.raw })
        RigModifierAlgorithms.addPhysics(rig.withPuppet(model), groups.values.map(Physics3Json::group))
    })

}
