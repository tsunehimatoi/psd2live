package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV8Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV8Algorithm.ENTRYPOINT to BrowLidV8Algorithm.VERSION) to BrowLidV8Algorithm()))
    }
}
