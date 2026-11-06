package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR

/** Reads mesh identity and order separately from the targeted mesh data used by scaffold binding. */
internal object ScaffoldReadInputs {
    fun capture(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): RigIR {
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        val meshes = input.meshIds.map { id ->
            checkpoint()
            if (id in targets) requireNotNull(input.mesh(id)) { "Missing scaffold target: $id" }
            // RenderRootDerive uses drawable IDs and their order for the missing-org fallback.
            // Draw order and composites on group nodes come from Parts, which are read in full.
            else Mesh(id, id, null, geometry = null, offsets = null)
        }
        return RigIR(
            canvas = input.canvas,
            parameters = input.parameters,
            parts = input.parts,
            deformers = input.deformers,
            meshes = meshes,
            rootChildren = input.rootChildren,
            rootPart = input.rootPart,
            renderRoot = input.renderRoot,
        )
    }
}
