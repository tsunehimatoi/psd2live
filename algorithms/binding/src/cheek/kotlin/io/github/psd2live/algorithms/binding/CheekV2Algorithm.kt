package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR

class CheekV2Algorithm : ChangingModifierAlgorithmPlugin {
    private val binding = CheekAlgorithm()

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(input.parameters.none { it.id == "ParamCheek" || it.id == "ParamGloom" }) {
            "Use cheek v1 when a mood parameter already exists"
        }
        val changes = binding.changes(input, instance, context, checkpoint)
        for (update in changes.meshes.updated) {
            checkpoint()
            val mesh = update.before
            require(Channel.MULTIPLY_COLOR !in mesh.channels && withinLoweringBudget(mesh)) {
                "Use cheek v1 when the mesh's color or keyform budget affects blend shape lowering: ${mesh.id}"
            }
        }
        return changes
    }

    // Cubism lowers geometry and channels against the same grid budget. Include the keys already
    // folded into mesh offsets and account for the two keys of the new cheek axis.
    private fun withinLoweringBudget(mesh: Mesh): Boolean {
        val axes = mesh.offsets?.axes.orEmpty() + mesh.channels.values.flatMap { it.axes }
        var cells = 2L
        for (grids in axes.groupBy { it.parameter }.values) {
            cells *= grids.flatMap { it.keys.toArray().asList() }.distinct().size
            if (cells > 256L) return false
        }
        return cells * (mesh.geometry?.positions?.size ?: 0) <= 120_000L
    }

    companion object {
        const val TYPE = "expression.cheek"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e636865656b.v2/color"
    }
}
