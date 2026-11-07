package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

object NeckV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (NeckV3Algorithm.ENTRYPOINT to NeckV3Algorithm.VERSION) to NeckV3Algorithm(),
    ))
}

/** Applies the locally projected neck tilt to the live source mesh. */
class NeckV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold")
        val capabilities = input.upstream
        val owners = capabilities.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { id ->
                RigModifierInstance(id, provider.type, provider.version, provider.targets,
                    config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
            }
        }
        val scaffold = owners.single {
            it.type == "rig.scaffold" && it.id == scaffoldId.jsonPrimitive.content && it.version == 2 &&
                it.targets.containsAll(instance.targets)
        }
        require(capabilities.any { it.capability == "face/legacy" && it.targets.containsAll(instance.targets) })
        val context = input.context
        val before = RigIrBinary.decode(context.resources.getValue("before").read())
        val after = RigIrBinary.decode(context.resources.getValue("after").read())
        val beforeMeshes = before.meshes.associateBy { it.id }
        val afterMeshes = after.meshes.associateBy { it.id }
        val masks = context.values.getValue("masks").jsonArray.map { it.jsonPrimitive.content }
        val shadows = context.values.getValue("shadows").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        require(beforeMeshes.keys == afterMeshes.keys && beforeMeshes.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val parameters = input.parameters
        val meshes = beforeMeshes.mapNotNull { (id, source) ->
            checkpoint()
            val target = ModifierTarget("mesh", id)
            val live = requireNotNull(input.mesh(id))
            val ancestry = input.parentsOfMesh(id)
            val actualDeformers = ancestry.associateBy { it.id }
            fun local(phase: RigIR, mesh: Mesh) = PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                meshes = listOf(mesh), deformers = phase.deformers))
            val prior = local(before, source)
            val next = local(after, afterMeshes.getValue(id))
            val aliases = linkedMapOf<DeformerId, DeformerId>()
            for (deformer in prior.deformers) {
                val actualId = owners.asSequence().filter { target in it.targets }
                    .map { ScaffoldBinding.deformerId(it, id, deformer.id.raw, shared = true) }.distinct()
                    .singleOrNull { it in actualDeformers }
                if (actualId != null) aliases[deformer.id] = DeformerId(actualId)
            }
            val priorBranch = generateSequence(prior.drawables.single().parentDeformerId) { parent ->
                prior.deformers.single { it.id == parent }.parent
            }.toList()
            require(priorBranch.all { it in aliases }) { "Cannot resolve preceding neck frame: ${live.id}" }
            require(next.drawables.single().parentDeformerId == prior.drawables.single().parentDeformerId)
            val parts = prior.deformers.mapNotNull { deformer ->
                val actual = aliases[deformer.id]?.raw?.let(actualDeformers::get)?.part ?: return@mapNotNull null
                deformer.partId?.let { it to PartId(actual) }
            }.toMap()
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(live), deformers = ancestry,
                parts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }, textures = input.textures, authoring = input.authoring))
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = scaffold
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> =
                    BodyVertexSources.match(to, original, target, checkpoint)
            }
            val local = instance.copy(targets = listOf(target), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, local, transfer, checkpoint)
            val projected = PuppetIr.meshToIr(merged.drawables.single())
            val clipping = if (id in shadows && live.maskedBy.isEmpty() && !live.invertMask && masks.isNotEmpty()) {
                require(masks.all { input.mesh(it) != null }) { "Missing captured neck mask source" }
                masks
            } else live.maskedBy
            val result = live.copy(geometry = projected.geometry, offsets = projected.offsets, shapes = projected.shapes, maskedBy = clipping)
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = meshes))
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.666163652e6e65636b.v3/tilt"
    }
}
