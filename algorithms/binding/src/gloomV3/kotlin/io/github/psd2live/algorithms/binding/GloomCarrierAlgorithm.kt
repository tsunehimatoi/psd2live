package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId

/** Replays keyform allocation on witnessed, unedited skin bindings. */
class GloomCarrierAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val source = GloomInputs.decode(context)
        val channels = GloomAlgorithm().changes(input, instance.copy(version = 1), checkpoint)
        if (channels.meshes.updated.isEmpty()) return channels
        val skin = RigIrBinary.decode(context.resources.getValue("skin_forms").read()).meshes.associateBy { it.id }
        val ownership = context.values.getValue("skin").jsonObject
        val highlights = context.values["eye_highlights"]?.jsonArray.orEmpty().mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val scaffold = input.providers("rig.scaffold").single { it.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content }
        require(scaffold.version == 2)
        val deformerUpdates = mutableListOf<Deformer>()
        val lowering = BlendShapeLowering({ head, name ->
            context.values.getValue(if (head) "turn_name" else "shape_name").jsonPrimitive.content.replace("{meshName}", name)
        }, checkpoint)
        val updates = channels.meshes.updated.mapNotNull { change ->
            checkpoint()
            val before = change.before
            val id = before.id
            val layer = source.layers.getValue(id)
            val neutral = source.neutral.getValue(id)
            val eyeOwnsOpacity = id in highlights &&
                before.channels[Channel.OPACITY]?.axes.orEmpty().any { it.parameter in eyeOpen }
            val owned = buildSet {
                if (layer.skin && Channel.MULTIPLY_COLOR !in neutral.channels) add(Channel.MULTIPLY_COLOR)
                if (layer.highlight && layer.precedingGloomStrength == null && Channel.OPACITY !in neutral.channels && !eyeOwnsOpacity)
                    add(Channel.OPACITY)
            }
            val generated = change.after.channels.filterKeys { it in owned }.mapValues { (channel, grid) ->
                if (channel == Channel.MULTIPLY_COLOR && grid.axes.map { it.parameter } == moodAxes &&
                    before.channels[channel]?.axes?.map { it.parameter } == moodAxes.take(1))
                    grid.copy(cells = grid.cells.sortedBy { cell -> cell.coordinate[0].toLong() * grid.axes[1].keys.size + cell.coordinate[1] })
                else grid
            }
            val after = before.copy(channels = before.channels - owned + generated)
            if (before == after) return@mapNotNull null
            val native = skin[id]
            if (!exceedsBudget(before, after)) return@mapNotNull ModifierObjectUpdate(before, after)
            requireNotNull(native) { "Gloom v3 cannot replay lowering for $id without skin forms" }
            val owners = ownership.getValue(id).jsonObject
            require(owners.getValue("providers").jsonArray.all { owner ->
                input.upstream.any { it.instanceId == owner.jsonPrimitive.content && ModifierTarget("mesh", id) in it.targets }
            }) { "Gloom v3 requires the active skin providers for $id" }
            val possibleParents = owners.getValue("parents").jsonArray.map { it.jsonPrimitive.content }
            val liveParent = requireNotNull(before.parent?.takeIf { it in possibleParents }) {
                "Gloom v3 requires the original skin parent for $id (found ${before.parent}, expected one of $possibleParents)"
            }
            val parent = requireNotNull(input.deformer(liveParent))
            val params = input.parameters + channels.parameters.added
            val witnesses = input.meshes.filter { it.id != id }.map { it.copy(parent = null, geometry = null, shapes = emptyList()) }
            fun lower(tracks: Map<Channel, KeyGrid<ChannelValue>>) = lowering.lawful(PuppetIr.toPuppet(RigIR(
                input.canvas, params,
                meshes = listOf(native.copy(parent = liveParent, channels = tracks, drawOrder = before.drawOrder)) + witnesses,
                deformers = listOf(stripShapes(parent)),
            )))
            val original = lower(before.channels)
            val witness = PuppetIr.meshToIr(original.drawables.first())
            require(original.deformers.size == 1 && witness.parent == before.parent && witness.geometry == before.geometry &&
                witness.offsets == before.offsets && witness.shapes == before.shapes && witness.channels == before.channels) {
                "Gloom v3 skin source differs from the preceding binding for $id " +
                    "(geometry=${witness.geometry == before.geometry}, offsets=${witness.offsets == before.offsets}, " +
                    "shapes=${witness.shapes == before.shapes}, channels=${witness.channels == before.channels})"
            }
            val result = lower(after.channels)
            val carriers = result.deformers.drop(1)
            val remap = ModelIdRemap(deformers = carriers.associate { carrier ->
                carrier.id to DeformerId(ScaffoldBinding.deformerId(instance, id, carrier.id.raw, shared = true))
            })
            deformerUpdates += carriers.map { PuppetIr.deformerToIr(remap.deformer(it)) }
            val mesh = PuppetIr.meshToIr(remap.drawable(result.drawables.single { it.id == DrawableId(id) }))
            ModifierObjectUpdate(before, after.copy(parent = mesh.parent, geometry = mesh.geometry, offsets = mesh.offsets,
                channels = mesh.channels, shapes = mesh.shapes))
        }
        return channels.copy(meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(added = deformerUpdates))
    }

    private fun stripShapes(parent: Deformer): Deformer = when (parent) {
        is Deformer.Warp -> parent.copy(shapes = emptyList())
        is Deformer.Rotation -> parent.copy(shapes = emptyList())
    }

    private fun exceedsBudget(before: Mesh, after: Mesh): Boolean {
        val size = before.geometry?.positions?.size ?: return false
        val geometry = before.offsets?.axes.orEmpty()
        if (geometry.isEmpty()) return false
        fun axes(mesh: Mesh) = (geometry + mesh.channels.values.flatMap { it.axes }).distinctBy { it.parameter }.associateBy { it.parameter }
        val changed = axes(after)
        if (axes(before) == changed) return false
        val count = changed.values.fold(1L) { n, axis -> (n * axis.keys.size).coerceAtMost(257) }
        return count > minOf(256L, 120_000L / size.coerceAtLeast(1))
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e676c6f6f6d.v3/gloom"
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val moodAxes = listOf("ParamCheek", GloomAlgorithm.PARAMETER)
        val WRITES = setOf("mesh", "parameter", "deformer", "hierarchy")
    }
}
