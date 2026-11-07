package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthDepthV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthDepthV3Algorithm.ENTRYPOINT to MouthDepthV3Algorithm.VERSION) to MouthDepthV3Algorithm()))
    }
}
