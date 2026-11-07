package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthDepthV4Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthDepthV4Algorithm.ENTRYPOINT to MouthDepthV4Algorithm.VERSION) to MouthDepthV4Algorithm()))
    }
}
