package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeEmotionWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeEmotionAlgorithm.ENTRYPOINT to EyeEmotionAlgorithm.VERSION) to EyeEmotionAlgorithm()))
    }
}
