package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object BrowShapeV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowShapeV3Algorithm.ENTRYPOINT to BrowShapeV3Algorithm.VERSION) to BrowShapeV3Algorithm()))
    }
}
