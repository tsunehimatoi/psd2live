package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthDepthV5Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthDepthV5Algorithm.ENTRYPOINT to MouthDepthV5Algorithm.VERSION) to MouthDepthV5Algorithm()))
    }
}
