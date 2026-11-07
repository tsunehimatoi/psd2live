package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowBlinkV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowBlinkV3Algorithm.ENTRYPOINT to BrowBlinkV3Algorithm.VERSION) to BrowBlinkV3Algorithm()))
    }
}
