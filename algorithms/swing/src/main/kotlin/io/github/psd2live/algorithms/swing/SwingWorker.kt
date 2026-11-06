package io.github.psd2live.algorithms.swing

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object SwingWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.7377696e67.v1/sway" to 1) to SwingAlgorithm()))
    }
}
