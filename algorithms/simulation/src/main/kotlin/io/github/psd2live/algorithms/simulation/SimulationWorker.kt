package io.github.psd2live.algorithms.simulation

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object SimulationWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.73696d756c6174696f6e.v1/baked" to 1) to SimulationAlgorithm()))
    }
}
