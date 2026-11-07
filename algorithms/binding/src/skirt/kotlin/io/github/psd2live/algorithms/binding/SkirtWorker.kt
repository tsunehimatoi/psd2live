package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object SkirtWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((SkirtAlgorithm.ENTRYPOINT to SkirtAlgorithm.VERSION) to SkirtAlgorithm()))
    }
}
