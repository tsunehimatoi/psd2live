package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthSpringV4Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthSpringV4Algorithm.ENTRYPOINT to MouthSpringV4Algorithm.VERSION) to MouthSpringV4Algorithm()))
    }
}
