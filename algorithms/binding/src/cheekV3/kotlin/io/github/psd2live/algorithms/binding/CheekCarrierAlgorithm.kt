package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId

/** Adds warmth and replays skin lowering when the extra key axis changes carrier allocation. */
class CheekCarrierAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val scaffold = input.providers("rig.scaffold").single {
            it.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content
        }
        require(scaffold.version == 2) { "Cheek v3 requires a shared v2 scaffold" }
        val parameters = input.parameters
        require(parameters.none { it.id == "ParamCheek" || it.id == "ParamGloom" }) {
            "Cheek v3 requires unused mood parameters"
        }
        val targets = instance.targets.map { requireNotNull(input.mesh(it.id)) }
        val binding = CheekAlgorithm().changes(
            RigIR(input.canvas, parameters, meshes = targets, parameterTree = input.parameterTree),
            instance, context, checkpoint,
        )
        val skin by lazy {
            RigIrBinary.decode(context.resources.getValue("skin_forms").read()).meshes.associateBy { it.id }
        }
        val deformers = ArrayList<Deformer>()
        val updates = binding.meshes.updated.map { change ->
            checkpoint()
            require(Channel.MULTIPLY_COLOR !in change.before.channels) {
                "Cheek v3 requires an unedited multiply-color track on ${change.before.id}"
            }
            if (exceedsBudget(change.after)) {
                val source = requireNotNull(skin[change.before.id]) { "Cheek v3 has no source skin forms for ${change.before.id}" }
                lower(input, instance, change, source, parameters + binding.parameters.added, deformers, checkpoint)
            } else change
        }
        return binding.copy(meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(added = deformers))
    }

    private fun exceedsBudget(mesh: Mesh): Boolean {
        val axes = mesh.offsets?.axes.orEmpty() + mesh.channels.values.flatMap { it.axes }
        var cells = 1L
        for (grids in axes.groupBy { it.parameter }.values) {
            cells *= grids.flatMap { it.keys.toArray().asList() }.distinct().size
            if (cells > 256L) return true
        }
        return cells * (mesh.geometry?.positions?.size ?: 0) > 120_000L
    }

    private fun lower(input: ModifierEvaluationInput, instance: RigModifierInstance, update: ModifierObjectUpdate<Mesh>,
                      source: Mesh, parameters: List<Parameter>, deformers: MutableList<Deformer>,
                      checkpoint: () -> Unit): ModifierObjectUpdate<Mesh> {
        val before = update.before
        val context = input.context
        val id = before.id
        val ownership = context.values.getValue("skin").jsonObject.getValue(id).jsonObject
        require(ownership.getValue("providers").jsonArray.all { owner ->
            input.upstream.any { it.instanceId == owner.jsonPrimitive.content && ModifierTarget("mesh", id) in it.targets }
        }) { "Cheek v3 requires the active skin providers for $id" }
        val parents = ownership.getValue("parents").jsonArray.map { it.jsonPrimitive.content }
        val parent = requireNotNull(before.parent?.takeIf { it in parents }?.let(input::deformer)) {
            "Cheek v3 requires the original skin parent for $id (found ${before.parent}, expected one of $parents)"
        }
        val frame = when (parent) {
            is Deformer.Warp -> parent.copy(shapes = emptyList())
            is Deformer.Rotation -> parent.copy(shapes = emptyList())
        }
        val witnesses = input.meshes.filter { it.id != id }.map { it.copy(parent = null, geometry = null, shapes = emptyList()) }
        val lowering = BlendShapeLowering({ head, name ->
            context.values.getValue(if (head) "turn_name" else "shape_name").jsonPrimitive.content.replace("{meshName}", name)
        }, checkpoint)
        fun lowered(channels: Map<Channel, KeyGrid<ChannelValue>>) = lowering.lawful(PuppetIr.toPuppet(RigIR(
            input.canvas, parameters,
            meshes = listOf(source.copy(parent = parent.id, channels = channels, drawOrder = before.drawOrder)) + witnesses,
            deformers = listOf(frame),
        )))
        val original = lowered(before.channels)
        val witness = PuppetIr.meshToIr(original.drawables.first())
        require(original.deformers.size == 1 && witness.parent == before.parent && witness.geometry == before.geometry &&
            witness.offsets == before.offsets && witness.shapes == before.shapes && witness.channels == before.channels) {
            "Cheek v3 skin source differs from the preceding binding for $id " +
                "(geometry=${witness.geometry == before.geometry}, offsets=${witness.offsets == before.offsets}, " +
                "shapes=${witness.shapes == before.shapes}, channels=${witness.channels == before.channels})"
        }
        val result = lowered(update.after.channels)
        val carriers = result.deformers.drop(1)
        val remap = ModelIdRemap(deformers = carriers.associate { carrier ->
            carrier.id to DeformerId(ScaffoldBinding.deformerId(instance, id, carrier.id.raw, shared = true))
        })
        deformers += carriers.map { PuppetIr.deformerToIr(remap.deformer(it)) }
        val mesh = PuppetIr.meshToIr(remap.drawable(result.drawables.first()))
        return ModifierObjectUpdate(before, update.after.copy(parent = mesh.parent, geometry = mesh.geometry,
            offsets = mesh.offsets, shapes = mesh.shapes, channels = mesh.channels))
    }

    companion object {
        const val TYPE = "expression.cheek"
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e636865656b.v3/color"
        val WRITES = setOf("mesh", "parameter", "deformer", "hierarchy")
    }
}
