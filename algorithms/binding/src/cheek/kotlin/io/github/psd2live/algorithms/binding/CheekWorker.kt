package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object CheekWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((CheekV2Algorithm.ENTRYPOINT to CheekV2Algorithm.VERSION) to CheekV2Algorithm()))
    }
}
