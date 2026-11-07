package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*

object MouthVowelV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthVowelV3Algorithm.ENTRYPOINT to MouthVowelV3Algorithm.VERSION) to MouthVowelV3Algorithm(),
    ))
}

/** Applies vowel tracks to the source contours owned by a preceding fitted outline. */
class MouthVowelV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val outlines = input.providers("mouth.outline")
        require(outlines.isEmpty() || outlines.singleOrNull()?.let {
            it.version == 3 && it.targets.containsAll(instance.targets) && it.config["scaffold"] == instance.config["scaffold"]
        } == true) { "Mouth vowel v3 requires the fitted outline on the same scaffold" }
        return MouthVowelAlgorithm().changes(input, instance.copy(version = MouthVowelAlgorithm.VERSION), checkpoint)
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6d6f7574682e766f77656c.v3/vowel"
        val optionalUpstream = MouthVowelAlgorithm.optionalUpstream + "mouth.outline"
    }
}
