package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object ArmSwingWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((ArmSwingAlgorithm.ENTRYPOINT to ArmSwingAlgorithm.VERSION) to ArmSwingAlgorithm()))
    }
}
