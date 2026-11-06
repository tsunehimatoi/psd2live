package io.github.psd2live.algorithms.physics

import io.github.psd2live.algorithm.worker.AlgorithmWorker

/** Runs the same implementation used in process, without loading the application. */
object PhysicsWorker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf(("builtin.70687973696373.v1/pendulum" to 1) to PendulumAlgorithm()))
    }
}
