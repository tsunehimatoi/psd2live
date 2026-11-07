package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object LidFadeWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((LidFadeAlgorithm.ENTRYPOINT to LidFadeAlgorithm.VERSION) to LidFadeAlgorithm()))
    }
}
