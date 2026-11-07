package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeGazeLidWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeGazeLidAlgorithm.ENTRYPOINT to EyeGazeLidAlgorithm.VERSION) to EyeGazeLidAlgorithm()))
    }
}
