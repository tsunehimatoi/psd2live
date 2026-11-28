package io.github.psd2live.algorithms.shadow

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import java.util.HexFormat

object ShadowWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        AlgorithmWorker.run(
            mapOf(
                "shadow.world" to ShadowWorldAlgorithm(),
                "shadow.cast" to CastShadowAlgorithm(),
                "shadow.form" to FormShadowAlgorithm(),
            ).mapKeys { (alias, _) ->
                val hex = HexFormat.of().formatHex(alias.encodeToByteArray())
                "builtin.$hex.v1/${alias.substringAfterLast('.')}" to 1
            },
        )
    }
}
