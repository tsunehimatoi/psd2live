package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MouthOutlineV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV5Algorithm.ENTRYPOINT to MouthOutlineV5Algorithm.VERSION) to MouthOutlineV5Algorithm(),
    ))
}

/** Also projects the separate closed drawing through the fitted outline's source frames. */
class MouthOutlineV5Algorithm : ReadingModifierAlgorithmPlugin {
    private val fitted = MouthOutlineV3Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        require(input.providers("mouth/fitted").any {
            it.type == "mouth/fitted" && it.version == 4 && it.config["scaffold"] == JsonPrimitive(scaffold) &&
                it.targets.containsAll(instance.targets)
        }) { "Closed-drawing outline requires fitted mouth v4 on the same scaffold" }
        val layers = input.context.values.getValue("before").jsonObject.getValue("layers").jsonObject
        require(layers.values.any { it.jsonObject["tag"] == JsonPrimitive("MOUTH_CLOSE") }) {
            "Closed-drawing outline requires a separate closed drawing"
        }
        return fitted.changes(input, instance.copy(version = MouthOutlineV3Algorithm.VERSION), checkpoint)
    }

    companion object {
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v5/outline"
    }
}
