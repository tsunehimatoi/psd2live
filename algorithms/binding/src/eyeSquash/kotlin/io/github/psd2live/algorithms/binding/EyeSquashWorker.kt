package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeSquashWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeSquashAlgorithm.ENTRYPOINT to EyeSquashAlgorithm.VERSION) to EyeSquashAlgorithm()))
    }
}
