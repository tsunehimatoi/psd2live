package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthVowelWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthVowelAlgorithm.ENTRYPOINT to MouthVowelAlgorithm.VERSION) to MouthVowelAlgorithm()))
    }
}
