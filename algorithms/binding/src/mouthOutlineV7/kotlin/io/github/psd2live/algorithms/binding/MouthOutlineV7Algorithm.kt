package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonObject

object MouthOutlineV7Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV7Algorithm.ENTRYPOINT to MouthOutlineV7Algorithm.VERSION) to MouthOutlineV7Algorithm(),
    ))
}

class MouthOutlineV7Algorithm : ReadingModifierAlgorithmPlugin {
    private val fitted = MouthOutlineV3Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val original = instance.copy(version = MouthOutlineV3Algorithm.VERSION)
        val context = input.context
        val resources = setOf("outlineHairBefore", "outlineHairAfter")
        if (resources.none { it in context.resources }) return fitted.changes(input, original, checkpoint)
        require(resources.all { it in context.resources })
        val model = RigIR(input.canvas, input.parameters, parts = input.parts, deformers = input.deformers,
            meshes = input.meshes, glues = input.glues, textures = input.textures, authoring = input.authoring)
        val ordinary = JsonObject(context.values + ("hair" to JsonObject(emptyMap())))
        val outline = fitted.changes(ModifierEvaluationInput(model, ModifierAlgorithmContext(
            ordinary, context.resources - resources, input.upstream)), original, checkpoint)
        return MouthOutlineHairOrder.apply(model, outline, instance, context, checkpoint)
    }

    companion object {
        const val VERSION = 7
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v7/outline"
    }
}
