package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object ExpressionWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.65787072657373696f6e2e746f67676c6573.v1/toggles" to 1) to ExpressionAlgorithm()))
    }
}
