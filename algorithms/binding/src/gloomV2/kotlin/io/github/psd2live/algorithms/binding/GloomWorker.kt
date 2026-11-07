package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object GloomWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((GloomBindingAlgorithm.ENTRYPOINT to GloomBindingAlgorithm.VERSION) to GloomBindingAlgorithm()))
    }
}
