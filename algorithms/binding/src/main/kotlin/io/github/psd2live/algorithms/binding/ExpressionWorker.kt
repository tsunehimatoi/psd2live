package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker

object ExpressionWorker {
    @JvmStatic fun main(args: Array<String>) {
        val algorithms = mutableMapOf<Pair<String, Int>, io.github.psd2live.core.modifier.ModifierAlgorithmPlugin>(
            ("builtin.65787072657373696f6e2e746f67676c6573.v1/toggles" to 1) to ExpressionAlgorithm())
        for (area in listOf("eye", "brow")) {
            val id = "builtin." + java.util.HexFormat.of().formatHex("$area.see-through".encodeToByteArray()) + ".v1/visibility"
            algorithms[id to 1] = SeeThroughAlgorithm()
        }
        AlgorithmWorker.run(algorithms)
    }
}
