package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthTongueWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthTongueAlgorithm.ENTRYPOINT to MouthTongueAlgorithm.VERSION) to MouthTongueAlgorithm()))
    }
}
