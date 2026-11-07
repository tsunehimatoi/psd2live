package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeLegacyWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeLegacySourceAlgorithm.ENTRYPOINT to EyeLegacySourceAlgorithm.VERSION) to EyeLegacySourceAlgorithm()))
    }
}
