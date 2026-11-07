package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowFittedWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowFittedBinding.ENTRYPOINT to BrowFittedBinding.VERSION) to BrowFittedBinding()))
    }
}
