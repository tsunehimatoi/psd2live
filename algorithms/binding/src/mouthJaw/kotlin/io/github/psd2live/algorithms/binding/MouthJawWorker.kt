package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthJawWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthJawAlgorithm.ENTRYPOINT to MouthJawAlgorithm.VERSION) to MouthJawAlgorithm()))
    }
}
