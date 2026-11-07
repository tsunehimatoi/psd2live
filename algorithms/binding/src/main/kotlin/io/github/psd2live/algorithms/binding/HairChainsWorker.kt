package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object HairChainsWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.686169722f636861696e73.v2/chains" to 2) to HairChainsAlgorithm()))
    }
}
