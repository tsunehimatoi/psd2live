package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowSpringV4Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowSpringV4Algorithm.ENTRYPOINT to BrowSpringV4Algorithm.VERSION) to BrowSpringV4Algorithm()))
    }
}
