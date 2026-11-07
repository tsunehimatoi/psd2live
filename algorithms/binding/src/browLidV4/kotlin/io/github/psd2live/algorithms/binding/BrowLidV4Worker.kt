package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV4Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV4Algorithm.ENTRYPOINT to BrowLidV4Algorithm.VERSION) to BrowLidV4Algorithm()))
    }
}
