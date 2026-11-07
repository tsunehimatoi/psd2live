package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowShapeWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowShapeAlgorithm.ENTRYPOINT to BrowShapeAlgorithm.VERSION) to BrowShapeAlgorithm()))
    }
}
