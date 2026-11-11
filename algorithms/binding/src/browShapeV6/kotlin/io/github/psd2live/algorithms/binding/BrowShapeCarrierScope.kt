package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.format.model.Deformer

/** The private brow branch is the complete permitted scope of a changed preceding carrier. */
object BrowShapeCarrierScope {
    fun check(input: ModifierEvaluationInput, updates: List<ModifierObjectUpdate<Deformer>>, brow: String,
              branch: Set<String>, checkpoint: () -> Unit) {
        val changed = updates.filter { it.before != it.after }
        if (changed.isEmpty()) return
        // Full membership is observed only for an actual update. Adding a foreign child then
        // invalidates a successful cached result, including children absent from the brow branch.
        val childDeformers = input.deformers.groupBy { it.parent }
        val childMeshes = input.meshes.groupBy { it.parent }
        for ((carrier, _) in changed) {
            val pending = ArrayDeque<String>()
            val visited = hashSetOf<String>()
            pending.add(carrier.id)
            while (pending.isNotEmpty()) {
                checkpoint()
                val parent = pending.removeFirst()
                require(visited.add(parent)) { "Cyclic brow carrier descendants: $parent" }
                for (mesh in childMeshes[parent].orEmpty()) require(mesh.id == brow) {
                    "Brow shape v6 cannot update carrier ${carrier.id} used by foreign mesh ${mesh.id}"
                }
                for (child in childDeformers[parent].orEmpty()) {
                    require(child.id in branch) {
                        "Brow shape v6 cannot update carrier ${carrier.id} used by foreign deformer ${child.id}"
                    }
                    pending.add(child.id)
                }
            }
        }
    }
}
