package io.github.psd2live.algorithms.simulation

import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.algorithms.physics.PhysicsGroups
import org.umamo.edit.withParametersSyncedFromTree

class SimulationAlgorithm : ModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        val rig = input
        require(instance.targets.all { it.kind == "mesh" }) { "A simulation modifier targets meshes" }
        checkpoint()
        val source = RigSimEdit.fromJson(instance.config)
        require(source.enabled) { "A simulation modifier must be enabled" }
        require(source.targets == instance.targets.map { it.id }) { "Simulation targets changed; bake again before adding the modifier" }
        val weighted = RigSimulationWeights.apply(PuppetIr.toPuppet(rig), instance.config)
        val bakedSource = if (source.bake == null || (source.autoBake && SimBake.stale(weighted, source))) {
            source.copy(bake = SimBaker.bake(weighted, source.copy(bake = null), SimBaker.Options(cancelled = { checkpoint(); false })))
        } else {
            require(!SimBake.stale(weighted, source)) { "Simulation inputs changed; bake again before adding the modifier" }
            require(SimulationInput.simulationMatches(rig, source, instance.inputFingerprint)) {
                "${instance.id}: upstream modifier input changed; bake the simulation again"
            }
            source
        }
        val edit = SimulationOutputs.outputs(instance, bakedSource)
        val (model, issues) = SimGenerator.applyOne(weighted, edit)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        checkpoint()
        val groups = SimulationOutputs.physics(instance, edit, model.parameters.mapTo(HashSet()) { it.id.raw })
        val result = PuppetIr.toIr(model.withParametersSyncedFromTree()).copy(
            textures = rig.textures, physics = rig.physics, clips = rig.clips, parameterRoles = rig.parameterRoles,
            restPose = rig.restPose, advanced = rig.advanced, hitAreas = rig.hitAreas)
        return PhysicsGroups.append(result, groups.values.map(PhysicsGroups::fromSetting))
    }
}
