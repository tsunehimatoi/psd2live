package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeSmileWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeSmileAlgorithm.ENTRYPOINT to EyeSmileAlgorithm.VERSION) to EyeSmileAlgorithm()))
    }
}
