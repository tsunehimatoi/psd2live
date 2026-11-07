package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR

object FittedFaceLatticesWorker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        ("psd2live.fitted-face-lattices/styled" to 1) to StyledLatticesAlgorithm,
        ("psd2live.fitted-face-lattices/binding" to 1) to FittedFaceBindingAlgorithm,
    ))
}

private object StyledLatticesAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == 1 && instance.targets.all { it.kind == "mesh" })
        val scaffold = requireNotNull(context.upstream.singleOrNull { it.capability == "rig.scaffold" }) {
            "Fitted face lattices require an explicit scaffold provider"
        }
        require(instance.targets.isNotEmpty() && instance.targets.toSet() == scaffold.targets.toSet()) {
            "Fitted face lattices require the complete scaffold target set"
        }
        return FittedFaceLattices.changes(input, FittedFaceLattices.Inputs.decode(context), checkpoint)
    }
}
