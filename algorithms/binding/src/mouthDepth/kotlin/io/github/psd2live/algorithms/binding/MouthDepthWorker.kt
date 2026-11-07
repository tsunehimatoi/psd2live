package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthDepthWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthDepthAlgorithm.ENTRYPOINT to MouthDepthAlgorithm.VERSION) to MouthDepthAlgorithm()))
    }
}
