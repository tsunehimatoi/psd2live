package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object CheekV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((CheekCarrierAlgorithm.ENTRYPOINT to CheekCarrierAlgorithm.VERSION) to CheekCarrierAlgorithm()))
    }
}
