package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object HairLegacyWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.686169722f6c6567616379.v2/legacy" to 2) to HairLegacyAlgorithm()))
    }
}
