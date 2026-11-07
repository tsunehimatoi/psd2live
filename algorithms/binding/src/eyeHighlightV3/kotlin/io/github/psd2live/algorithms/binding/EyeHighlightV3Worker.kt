package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeHighlightV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeHighlightV3Algorithm.ENTRYPOINT to EyeHighlightV3Algorithm.VERSION) to EyeHighlightV3Algorithm()))
    }
}
