package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MouthOutlineV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV6Algorithm.ENTRYPOINT to MouthOutlineV6Algorithm.VERSION) to MouthOutlineV6Algorithm(),
    ))
}

/** Also projects the separate closed drawing through the fitted outline's source frames. */
class MouthOutlineV6Algorithm : ReadingModifierAlgorithmPlugin {
    private val fitted = MouthOutlineV3Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        require(input.providers("mouth/fitted").any {
            it.type == "mouth/fitted" && it.version == 5 && it.config["scaffold"] == JsonPrimitive(scaffold) &&
                it.targets.containsAll(instance.targets)
        }) { "Closed-drawing outline requires fitted mouth v5 on the same scaffold" }
        val layers = input.context.values.getValue("before").jsonObject.getValue("layers").jsonObject
        require(layers.values.any { it.jsonObject["tag"] == JsonPrimitive("MOUTH_CLOSE") }) {
            "Closed-drawing outline requires a separate closed drawing"
        }
        val shellKeys = setOf("outlineShellBefore", "outlineShellAfter")
        val original = instance.copy(version = MouthOutlineV3Algorithm.VERSION)
        val context = input.context
        if (shellKeys.none { it in context.resources }) return fitted.changes(input, original, checkpoint)
        require(shellKeys.all { it in context.resources })
        val model = RigIR(input.canvas, input.parameters, parts = input.parts, deformers = input.deformers,
            meshes = input.meshes, glues = input.glues, textures = input.textures, authoring = input.authoring)
        val outline = fitted.changes(ModifierEvaluationInput(model, ModifierAlgorithmContext(
            context.values, context.resources - shellKeys, input.upstream)), original, checkpoint)
        return MouthOutlineShellResidual.apply(model, outline, instance, context, checkpoint)
    }

    companion object {
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v6/outline"
    }
}
