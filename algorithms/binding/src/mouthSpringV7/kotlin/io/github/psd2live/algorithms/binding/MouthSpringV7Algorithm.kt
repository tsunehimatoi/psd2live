package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*

object MouthSpringV7Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthSpringV7Algorithm.ENTRYPOINT to MouthSpringV7Algorithm.VERSION) to MouthSpringV7Algorithm(),
    ))
}

/** Uses the existing lower-face spring after an independent closed-mouth interior depth binding. */
class MouthSpringV7Algorithm : ReadingModifierAlgorithmPlugin {
    private val spring = MouthSpringV6Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val observations = context.values.getValue("providers").jsonObject
        val depth = requireNotNull(input.providers(DEPTH).singleOrNull {
            it.type == DEPTH && it.version == 4 && it.plugin?.id == DEPTH_PACKAGE &&
                it.config["scaffold"] == instance.config["scaffold"] && it.config["base"] == instance.config["base"] &&
                it.targets.any(instance.targets::contains) && it.instanceId?.let(observations::get) == JsonPrimitive(DEPTH)
        }) { "Mouth spring v7 requires interior depth v4 on the same fitted mouth" }
        val source = MouthFittedInputs.decode(ModifierAlgorithmContext(
            context.values.getValue("source").jsonObject, context.resources))
        val meshes = source.layers.keys.map { requireNotNull(input.mesh(it)) }
        val ancestors = meshes.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }

        // Depth v4 only updates the fitted interior and adds carriers there. The v6 spring
        // consumes the measured lower-face layers, their existing ancestors, parameters and physics.
        // Keep the genuine depth observation on the outer input and omit it from this local view.
        val localContext = ModifierAlgorithmContext(
            JsonObject(context.values + ("providers" to JsonObject(observations - requireNotNull(depth.instanceId)))),
            context.resources,
            input.upstream.filterNot { it == depth },
        )
        val local = RigIR(input.canvas, input.parameters, parameterTree = input.parameterTree,
            meshes = meshes, deformers = ancestors, physics = input.physics)
        return spring.changes(local, instance.copy(version = MouthSpringV6Algorithm.VERSION), localContext, checkpoint)
    }

    companion object {
        const val VERSION = 7
        const val ENTRYPOINT = "builtin.6d6f7574682e737072696e67.v7/spring"
        private const val DEPTH = "mouth.interior-depth"
        private const val DEPTH_PACKAGE = "builtin.6d6f7574682e696e746572696f722d6465707468.v4"
    }
}
