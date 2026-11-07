package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object EyeGazeLidV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((EyeGazeLidV3Algorithm.ENTRYPOINT to EyeGazeLidV3Algorithm.VERSION) to EyeGazeLidV3Algorithm()))
    }
}
