package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidAlgorithm.ENTRYPOINT to BrowLidAlgorithm.VERSION) to BrowLidAlgorithm()))
    }
}
