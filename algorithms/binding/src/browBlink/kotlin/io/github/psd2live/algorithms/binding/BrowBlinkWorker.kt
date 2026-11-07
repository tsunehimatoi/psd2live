package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowBlinkWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowBlinkAlgorithm.ENTRYPOINT to BrowBlinkAlgorithm.VERSION) to BrowBlinkAlgorithm()))
    }
}
