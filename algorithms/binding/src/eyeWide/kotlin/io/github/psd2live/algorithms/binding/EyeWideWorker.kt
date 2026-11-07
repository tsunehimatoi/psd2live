package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeWideWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeWideAlgorithm.ENTRYPOINT to EyeWideAlgorithm.VERSION) to EyeWideAlgorithm()))
    }
}
