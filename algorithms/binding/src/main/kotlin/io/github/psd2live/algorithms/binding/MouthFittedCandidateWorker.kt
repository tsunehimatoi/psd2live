package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR

object MouthFittedCandidateWorker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        ("psd2live.mouth-fitted-source/source" to 1) to MouthFittedCandidateAlgorithm(),
    ))
}

/** The package requires an explicit provider for independently sampled head turn. */
internal class MouthFittedCandidateAlgorithm : ChangingModifierAlgorithmPlugin {
    private val source = MouthFittedSourceAlgorithm()

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        val head = MouthFittedInputs.decode(context).head
        // The shared decoder checks provider identity when an upstream list is supplied.
        require(head == null || context.upstream.isNotEmpty()) { "Missing fitted mouth head-turn provider" }
        return source.changes(input, instance, context, checkpoint)
    }
}
