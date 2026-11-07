package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.jsonPrimitive

object FittedFaceV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        ("builtin.666163652f666974746564.v3/binding" to 3) to FittedFaceV3Algorithm,
    ))
}

/** Uses the measured turn on the actual body output, retaining the existing output identities. */
object FittedFaceV3Algorithm : ChangingModifierAlgorithmPlugin {
    const val VERSION = 3

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = context.upstream.single { it.capability == "rig.scaffold" }
        val body = context.upstream.single { it.capability == "body/legacy" }
        val targets = instance.targets.toSet()
        require(scaffold.version == 2 && scaffold.instanceId != null && scaffold.targets.toSet() == targets &&
            body.version in 1..2 && body.targets.toSet() == targets &&
            body.config["scaffold"]?.jsonPrimitive?.content == scaffold.instanceId) {
            "Fitted face v3 requires a preceding legacy body on the same complete shared v2 scaffold"
        }
        return FittedFaceV2Algorithm.changes(input, instance.copy(version = FittedFaceV2Algorithm.VERSION), context, checkpoint)
    }
}
