package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthSpringWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthSpringAlgorithm.ENTRYPOINT to MouthSpringAlgorithm.VERSION) to MouthSpringAlgorithm()))
    }
}
