package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthCheekLidV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthCheekLidV3Algorithm.ENTRYPOINT to MouthCheekLidV3Algorithm.VERSION) to MouthCheekLidV3Algorithm()))
    }
}
