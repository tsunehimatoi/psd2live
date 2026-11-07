package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowSpringV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowSpringV3Algorithm.ENTRYPOINT to BrowSpringV3Algorithm.VERSION) to BrowSpringV3Algorithm()))
    }
}
