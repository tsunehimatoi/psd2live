package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV3Algorithm.ENTRYPOINT to BrowLidV3Algorithm.VERSION) to BrowLidV3Algorithm()))
    }
}
