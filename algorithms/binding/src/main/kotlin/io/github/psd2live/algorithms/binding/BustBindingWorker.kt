package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BustBindingWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BustBindingAlgorithm.ENTRYPOINT to BustBindingAlgorithm.VERSION) to BustBindingAlgorithm()))
    }
}
