package io.github.psd2live.algorithms.motion

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MotionWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.6d6f74696f6e.v1/clip" to 1) to MotionAlgorithm()))
    }
}
