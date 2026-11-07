package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthCaptureWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthCaptureAlgorithm.ENTRYPOINT to MouthCaptureAlgorithm.VERSION) to MouthCaptureAlgorithm()))
    }
}
