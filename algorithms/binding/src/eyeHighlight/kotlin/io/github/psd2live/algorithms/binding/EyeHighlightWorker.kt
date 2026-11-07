package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeHighlightWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeHighlightAlgorithm.ENTRYPOINT to EyeHighlightAlgorithm.VERSION) to EyeHighlightAlgorithm()))
    }
}
