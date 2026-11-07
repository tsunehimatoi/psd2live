package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowSpringWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowSpringAlgorithm.ENTRYPOINT to BrowSpringAlgorithm.VERSION) to BrowSpringAlgorithm()))
    }
}
