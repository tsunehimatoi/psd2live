package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowLidV9Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowLidV9Algorithm.ENTRYPOINT to BrowLidV9Algorithm.VERSION) to BrowLidV9Algorithm()))
    }
}
