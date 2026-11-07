package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeAftershockWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeAftershockAlgorithm.ENTRYPOINT to EyeAftershockAlgorithm.VERSION) to EyeAftershockAlgorithm()))
    }
}
