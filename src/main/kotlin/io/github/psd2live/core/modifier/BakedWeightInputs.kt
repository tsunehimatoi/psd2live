package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.VertexGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimBake
import kotlinx.serialization.Serializable

/** Original values of painted groups, sufficient to replay the last bake with its exact input.
 * Order matters to older input fingerprints. An absent group has index -1. */
@Serializable
internal data class BakedWeightInput(val mesh: String, val name: String, val index: Int,
    val kind: String? = null, val weights: List<Float> = emptyList()) {

    companion object {
        fun capture(groups: List<VertexGroup>, mesh: String, name: String): BakedWeightInput {
            val index = groups.indexOfFirst { it.mesh == mesh && it.name == name }
            val original = groups.getOrNull(index)
            return BakedWeightInput(mesh, name, index, original?.kind, original?.weights?.shared()?.toList().orEmpty())
        }

        /** Other kinds of upstream change must still invalidate the bake. */
        fun replayInput(rig: RigIR, instance: RigModifierInstance, inputs: List<BakedWeightInput>): RigIR? {
            val sim = RigSimEdit.fromJson(instance.config)
            if (sim.bake == null) return null
            val restored = restore(rig.authoring.vertexGroups, inputs) ?: return null
            val previous = rig.copy(authoring = rig.authoring.copy(vertexGroups = restored))
            if (!RigModifierInput.simulationMatches(previous, sim, instance.inputFingerprint)) return null
            val weighted = RigSimulationWeights.apply(PuppetIr.toPuppet(previous), instance.config)
            return previous.takeUnless { SimBake.stale(weighted, sim) }
        }

        fun restore(groups: List<VertexGroup>, inputs: List<BakedWeightInput>): List<VertexGroup>? {
            val keys = inputs.mapTo(HashSet()) { it.mesh to it.name }
            val result = groups.filterNot { (it.mesh to it.name) in keys }.toMutableList()
            for (input in inputs.filter { it.index >= 0 }.sortedBy { it.index }) {
                if (input.index > result.size) return null
                result.add(input.index, VertexGroup(input.name, input.mesh, requireNotNull(input.kind), Floats.wrap(input.weights.toFloatArray())))
            }
            return result
        }
    }
}
