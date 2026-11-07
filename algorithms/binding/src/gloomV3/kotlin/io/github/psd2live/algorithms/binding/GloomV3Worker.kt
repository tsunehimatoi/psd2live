package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object GloomV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((GloomCarrierAlgorithm.ENTRYPOINT to GloomCarrierAlgorithm.VERSION) to GloomCarrierAlgorithm()))
    }
}
