package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object NeckWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((NeckAlgorithm.ENTRYPOINT to NeckAlgorithm.VERSION) to NeckAlgorithm()))
    }
}
