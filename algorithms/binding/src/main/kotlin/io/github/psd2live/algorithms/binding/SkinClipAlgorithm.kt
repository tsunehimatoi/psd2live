package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*

/** Adds face masks to source skin overlays without rebuilding geometry or converting the model. */
class SkinClipAlgorithm : ContextualModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR {
        checkpoint()
        val source = context.values
        require(source["schema"]?.jsonPrimitive?.content == "skin-clip/1") { "Missing skin clipping author inputs" }
        fun bounds(value: JsonElement): List<Float> = value.jsonArray.map { it.jsonPrimitive.float }.also {
            require(it.size == 4 && it.all(Float::isFinite)) { "Invalid skin clipping bounds" }
        }
        val faces = source.getValue("faces").jsonArray.map { it.jsonObject }
        if (faces.isEmpty()) return input
        val masks = faces.map { it.getValue("id").jsonPrimitive.content }
        val boxes = faces.map { bounds(it.getValue("bounds")) }
        val left = boxes.minOf { it[0] }; val top = boxes.minOf { it[1] }
        val right = boxes.maxOf { it[2] }; val bottom = boxes.maxOf { it[3] }
        val overlays = source.getValue("overlays").jsonObject
        val prior = source.getValue("already_active").jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        var changed = false
        val meshes = input.meshes.map { mesh ->
            checkpoint()
            val encoded = overlays[mesh.id]
            if (mesh.id !in targets || mesh.id in prior || encoded == null) return@map mesh
            val b = bounds(encoded)
            val w = (minOf(b[2], right) - maxOf(b[0], left)).coerceAtLeast(0f)
            val h = (minOf(b[3], bottom) - maxOf(b[1], top)).coerceAtLeast(0f)
            if (w * h < .85f * (b[2] - b[0]) * (b[3] - b[1])) return@map mesh
            require(!mesh.invertMask && (mesh.maskedBy.isEmpty() || mesh.maskedBy == masks)) {
                "${instance.id}: incompatible authored masks on ${mesh.id}"
            }
            require(masks.all { mask -> input.meshes.any { it.id == mask } }) { "${instance.id}: skin clipping face mesh is missing" }
            if (mesh.maskedBy == masks) mesh else mesh.copy(maskedBy = masks).also { changed = true }
        }
        return if (changed) input.copy(meshes = meshes) else input
    }
}
