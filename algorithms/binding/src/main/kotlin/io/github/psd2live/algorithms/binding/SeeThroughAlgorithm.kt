package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.DrawableId

/** Source-aware eyebrow/eye visibility algorithm, shared by bundled and external workers. */
class SeeThroughAlgorithm : ContextualModifierAlgorithmPlugin {
    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): RigIR {
        checkpoint()
        val source = context.values
        require(source["schema"]?.jsonPrimitive?.content == "see-through/1") { "Missing see-through author inputs" }
        val eyes = source.getValue("eyes").jsonPrimitive.boolean
        val pattern = source.getValue("name_pattern").jsonPrimitive.content
        require(pattern.split("{meshName}").size == 2) { "Invalid see-through name pattern" }
        val owners = Json.decodeFromJsonElement(ListSerializer(RigModifierInstance.serializer()), source.getValue("earlier_owners"))
        val model = PuppetIr.toPuppet(input)
        val order = model.drawables.withIndex().associate { it.value.id.raw to it.index }
        val present = order.keys
        val earlier = present.filterTo(HashSet()) { id -> owners.any { it.localOutputName(id)?.startsWith("see-through:") == true } }
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        val candidates = source.getValue("candidates").jsonArray.mapNotNull { encoded ->
            val item = encoded.jsonObject
            val original = item.getValue("original").jsonPrimitive.content
            val eye = item.getValue("eyes").jsonPrimitive.boolean
            if (eye != eyes || original !in targets || original !in present || original in earlier) return@mapNotNull null
            val bangs = item.getValue("bangs").jsonArray.map { it.jsonPrimitive.content }.filter { it in present && it !in earlier }.sortedBy { order.getValue(it) }
            if (bangs.isEmpty()) null else SeeThroughBinding.Candidate(original, eye, bangs)
        }
        val ordered = candidates.sortedBy { order.getValue(it.original) }
        val opacity = instance.config["values"]?.jsonObject?.get("opacity")?.jsonPrimitive?.float ?: 50f
        require(opacity.isFinite() && opacity in 0f..100f) { "See-through opacity must be within 0..100" }
        val result = SeeThroughBinding({ pattern.replace("{meshName}", it) }, checkpoint).apply(model, ordered,
            SeeThroughBinding.Settings(!eyes, eyes, opacity / 100f, source.getValue("draw_order_step").jsonPrimitive.int,
                source.getValue("inverted_masks").jsonPrimitive.boolean),
            idFor = { DrawableId(instance.outputId("see-through:${it.raw}")) }, earlierCopies = earlier)
        require(result.notes.none { it.reason == SeeThroughBinding.DropReason.ID_TAKEN || it.reason == SeeThroughBinding.DropReason.BANGS_CLIPPED }) {
            "${instance.id}: see-through output conflicts with existing meshes or masks"
        }
        checkpoint()
        if (result.copies.isEmpty()) return input
        val changed = result.model.copy(drawables = result.model.drawables.map {
            if (it.id.raw in result.copies) it.copy(isSelectable = true) else it
        }).withParametersSyncedFromTree()
        return PuppetIr.toIr(changed).copy(textures = input.textures, physics = input.physics, clips = input.clips,
            parameterRoles = input.parameterRoles, restPose = input.restPose, advanced = input.advanced, hitAreas = input.hitAreas)
    }
}
