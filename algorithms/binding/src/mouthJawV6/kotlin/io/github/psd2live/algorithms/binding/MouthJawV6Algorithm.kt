package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MouthJawV6Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthJawV6Algorithm.ENTRYPOINT to MouthJawV6Algorithm.VERSION) to MouthJawV6Algorithm(),
    ))
}

/** Shares the closed jaw calculation with v5 and accepts independent closed tongue tracks. */
class MouthJawV6Algorithm : ReadingModifierAlgorithmPlugin {
    private val jaw = MouthJawV5Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val delegated = instance.copy(version = MouthJawV5Algorithm.VERSION)
        val tongues = input.providers("mouth.tongue")
        if (tongues.none { it.version == 4 }) return jaw.changes(input, delegated, checkpoint)

        val context = input.context
        val providers = context.values.getValue("providers").jsonObject
        val capture = providers["mouth.capture"]
        require(tongues.singleOrNull()?.let {
            it.type == "mouth.tongue" && it.version == 4 &&
                it.instanceId == providers["mouth.tongue"]?.jsonPrimitive?.content &&
                it.config["scaffold"] == instance.config["scaffold"] && it.config["base"] == instance.config["base"] &&
                capture != null && it.config["dependencies"]?.jsonObject?.get("mouth.capture") == capture &&
                it.targets.containsAll(instance.targets)
        } == true) { "Mouth jaw v6 requires tongue v4 on the same fitted mouth and capture" }
        val tongueId = MouthRig.TONGUE_OUT.raw
        require(input.parameter(tongueId)?.let {
            it.blend && it.min == 0f && it.max == 1f && it.default == 0f && it.keys == Floats.wrap(floatArrayOf(0f, 1f))
        } == true) { "Mouth jaw v6 requires the upstream tongue-out parameter" }
        val source = MouthSourceContext.decode(ModifierAlgorithmContext(context.values.getValue("mouth").jsonObject,
            context.resources - "source_meshes"))
        require(source.options.capture && source.options.tongue && source.fit.layered == null) {
            "Mouth jaw v6 requires a closed tongue on unlayered source artwork"
        }
        val meshes = context.values.getValue("targets").jsonObject.keys.map { id ->
            requireNotNull(input.mesh(id)).also { mesh ->
                require(mesh.shapes.none { it.parameter == tongueId } &&
                    mesh.channels.values.none { track -> track.axes.any { it.parameter == tongueId } } &&
                    mesh.offsets?.axes.orEmpty().none { it.parameter == tongueId }) {
                    "Mouth jaw v6 cannot project a tongue track on a lower-face target: $id"
                }
            }
        }
        // The v5 lower-face calculation has no tongue term on unlayered skin. Project its
        // local inputs after validating the actual tongue, leaving the upstream mouth meshes intact.
        val lowerFace = source.copy(options = source.options.copy(tongue = false)).encode()
        val values = JsonObject(context.values + mapOf("mouth" to lowerFace.values, "providers" to JsonObject(providers - "mouth.tongue")))
        val projected = ModifierAlgorithmContext(values, lowerFace.resources + ("source_meshes" to context.resources.getValue("source_meshes")),
            input.upstream.filterNot { it.capability == "mouth.tongue" })
        val local = RigIR(input.canvas, input.parameters, meshes = meshes)
        return jaw.changes(local, delegated, projected, checkpoint)
    }

    companion object {
        const val VERSION = 6
        const val ENTRYPOINT = "builtin.6d6f7574682e6a6177.v6/jaw"
    }
}
