package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV5Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV5Algorithm.ENTRYPOINT to BrowLidV5Algorithm.VERSION) to BrowLidV5Algorithm()))
    }
}
