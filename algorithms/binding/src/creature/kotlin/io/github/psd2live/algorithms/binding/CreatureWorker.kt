package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object CreatureWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((CreatureAlgorithm.ENTRYPOINT to CreatureAlgorithm.VERSION) to CreatureAlgorithm()))
    }
}
