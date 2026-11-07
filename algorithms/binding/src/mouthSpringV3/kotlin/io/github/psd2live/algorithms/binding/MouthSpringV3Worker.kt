package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object MouthSpringV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((MouthSpringV3Algorithm.ENTRYPOINT to MouthSpringV3Algorithm.VERSION) to MouthSpringV3Algorithm()))
    }
}
