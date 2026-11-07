package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeFittedWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeFittedSourceAlgorithm.ENTRYPOINT to EyeFittedAlgorithm.VERSION) to EyeFittedSourceAlgorithm()))
    }
}
