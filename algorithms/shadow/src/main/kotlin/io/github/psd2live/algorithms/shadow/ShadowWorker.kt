package io.github.psd2live.algorithms.shadow

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object ShadowWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        AlgorithmWorker.run(
            mapOf(
                ("builtin.736861646f77.v1/world" to 1) to ShadowWorldAlgorithm(),
                ("builtin.736861646f77.v1/cast" to 1) to CastShadowAlgorithm(),
                ("builtin.736861646f77.v1/form" to 1) to FormShadowAlgorithm(),
            )
        )
    }
}
