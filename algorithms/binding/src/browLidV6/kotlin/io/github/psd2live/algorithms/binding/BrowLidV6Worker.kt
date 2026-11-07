package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV6Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV6Algorithm.ENTRYPOINT to BrowLidV6Algorithm.VERSION) to BrowLidV6Algorithm()))
    }
}
