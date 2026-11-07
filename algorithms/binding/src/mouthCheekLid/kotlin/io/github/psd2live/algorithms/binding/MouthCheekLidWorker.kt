package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthCheekLidWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthCheekLidAlgorithm.ENTRYPOINT to MouthCheekLidAlgorithm.VERSION) to MouthCheekLidAlgorithm()))
    }
}
