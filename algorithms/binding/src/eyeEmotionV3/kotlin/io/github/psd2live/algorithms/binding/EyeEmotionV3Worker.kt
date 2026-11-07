package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeEmotionV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeEmotionV3Algorithm.ENTRYPOINT to EyeEmotionV3Algorithm.VERSION) to EyeEmotionV3Algorithm()))
    }
}
