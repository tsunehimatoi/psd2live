package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object RibbonsWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((RibbonsAlgorithm.ENTRYPOINT to RibbonsAlgorithm.VERSION) to RibbonsAlgorithm()))
    }
}
