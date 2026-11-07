package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*

/** Reuses the measured brow projection with independently evaluated head and eye providers. */
object BrowFittedV3Binding : ChangingModifierAlgorithmPlugin {
    const val VERSION = 3
    const val ENTRYPOINT = "builtin.62726f772f666974746564.v3/binding"

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = context.values.getValue("scaffold").jsonPrimitive.content
        val face = context.values.getValue("fitted_face_provider").jsonPrimitive.contentOrNull
        if (face != null) {
            require(context.upstream.any {
                it.type == "face/fitted" && it.version in setOf(1, 3) && it.instanceId == face &&
                    it.config["scaffold"] == JsonPrimitive(scaffold) && it.targets.containsAll(instance.targets)
            }) { "Fitted brow requires its captured fitted face provider" }
        }
        val eyes = context.values.getValue("eyes").jsonObject.values
            .mapTo(hashSetOf()) { it.jsonObject.getValue("provider").jsonPrimitive.content }
        for (eye in eyes) require(context.upstream.any {
            it.type == "eye/fitted" && it.version in setOf(2, 5) && it.instanceId == eye &&
                it.config["scaffold"] == JsonPrimitive(scaffold) && it.targets.any(instance.targets::contains)
        }) { "Fitted brow requires its captured fitted eye provider" }

        // The v2 kernel consumes opening and width from the captured source. Its mesh and frame
        // checks still run against the live IR. Translate the version guard only within this call.
        val projectionContext = context.withUpstream(context.upstream.map {
            if (it.type == "eye/fitted" && it.instanceId in eyes && it.version == 5) it.copy(version = 2) else it
        })
        return BrowFittedBinding().changes(input, instance.copy(version = 2), projectionContext, checkpoint)
    }
}

object BrowFittedV3Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((BrowFittedV3Binding.ENTRYPOINT to BrowFittedV3Binding.VERSION) to BrowFittedV3Binding))
    }
}
