package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthLegacyWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(
            ("builtin.6d6f7574682f6c6567616379.v2/binding" to MouthLegacyAlgorithm.VERSION) to MouthLegacyAlgorithm(),
        ))
    }
}
