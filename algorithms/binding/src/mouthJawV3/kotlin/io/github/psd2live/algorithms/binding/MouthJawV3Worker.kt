package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthJawV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthJawV3Algorithm.ENTRYPOINT to MouthJawV3Algorithm.VERSION) to MouthJawV3Algorithm()))
    }
}
