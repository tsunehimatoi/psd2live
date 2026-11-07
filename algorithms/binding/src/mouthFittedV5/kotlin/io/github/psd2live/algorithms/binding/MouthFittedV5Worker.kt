package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

object MouthFittedV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthFittedV5Algorithm.ENTRYPOINT to MouthFittedV5Algorithm.VERSION) to MouthFittedV5Algorithm(),
    ))
}

/** Closed-drawing projection with a separate transition for an unstyled head shell. */
class MouthFittedV5Algorithm : ChangingModifierAlgorithmPlugin {
    private val fitted = MouthFittedV4Algorithm()

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val changes = fitted.changes(input, instance.copy(version = MouthFittedV4Algorithm.VERSION), context, checkpoint)
        if ("shellBefore" !in context.resources) return changes
        val output = merge(input, instance, context, changes.apply(input, checkpoint), checkpoint)
        val beforeMeshes = input.meshes.associateBy { it.id }
        val beforeDeformers = input.deformers.associateBy { it.id }
        return changes.copy(
            meshes = ModifierObjectChanges(added = changes.meshes.added, removed = changes.meshes.removed, reused = changes.meshes.reused,
                updated = output.meshes.mapNotNull { after ->
                beforeMeshes[after.id]?.takeIf { it != after }?.let { ModifierObjectUpdate(it, after) }
            }),
            deformers = ModifierObjectChanges(added = changes.deformers.added, removed = changes.deformers.removed, reused = changes.deformers.reused,
                updated = output.deformers.mapNotNull { after ->
                beforeDeformers[after.id]?.takeIf { it != after }?.let { ModifierObjectUpdate(it, after) }
            }),
        )
    }

    private fun merge(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                      fitted: RigIR, checkpoint: () -> Unit): RigIR {
        val upstream = context.upstream.single { it.type == "rig.scaffold" }
        val scaffold = RigModifierInstance(requireNotNull(upstream.instanceId), upstream.type, upstream.version, upstream.targets,
            config = upstream.config, outputAliases = upstream.outputAliases, plugin = upstream.plugin)
        val previous = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("shellBefore").read()))
        val desired = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("shellAfter").read()))
        val selected = desired.drawables.mapTo(hashSetOf()) { it.id.raw }
        val armTargets = context.values.getValue("shellArmTargets").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val canonical = instance.copy(type = "mouth/fitted")
        val previousMeshes = previous.drawables.associateBy { it.id }
        require(desired.drawables.all { previousMeshes.getValue(it.id).parentDeformerId == it.parentDeformerId }) {
            "Closed mouth shell projection requires stable source parents"
        }
        val comparison = BindingComparison()
        val adapter = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = previous to desired
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter) =
                null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                previous.deformers.associate { it.id to DeformerId(ScaffoldBinding.deformerId(scaffold, target.id, it.id.raw)) }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                error("Closed mouth shell projection cannot replace a carrier branch")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Closed mouth shell projection cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val scoped = canonical.copy(targets = canonical.targets.filter { it.id in selected })
        val merged = BindingMerge.apply(PuppetIr.toPuppet(input), scoped, adapter, checkpoint)
        val meshes = merged.drawables.associate { it.id.raw to PuppetIr.meshToIr(it) }
        val liveMeshes = input.meshes.associateBy { it.id }
        // A matching live mesh has no local residual. Keep the native grid without a frame round trip.
        val native = desired.drawables.filter { desiredMesh ->
            val original = PuppetIr.meshToIr(previousMeshes.getValue(desiredMesh.id))
            val live = liveMeshes.getValue(original.id)
            val parent = original.parent?.let { ScaffoldBinding.deformerId(scaffold, original.id, it) }
            live.parent == parent && live.geometry == original.geometry &&
                live.offsets == original.offsets && live.shapes == original.shapes
        }.associate { it.id.raw to PuppetIr.meshToIr(it) }
        val arms = desired.drawables.filter { it.id.raw in armTargets }
            .mapNotNullTo(hashSetOf()) { mesh ->
                mesh.parentDeformerId?.raw?.takeIf { it == "DeformArmHang_L" || it == "DeformArmHang_R" }
                    ?.let { ScaffoldBinding.deformerId(scaffold, mesh.id.raw, it) }
            }
        val deformers = merged.deformers.filter { it.id.raw in arms }.associate { it.id.raw to PuppetIr.deformerToIr(it) }
        return fitted.copy(meshes = fitted.meshes.map { current ->
            if (current.id !in selected || current.id in armTargets) current
            else (native[current.id] ?: meshes.getValue(current.id)).let { projected ->
                current.copy(geometry = projected.geometry, offsets = projected.offsets, shapes = projected.shapes)
            }
        }, deformers = fitted.deformers.map { deformers[it.id] ?: it })
    }

    companion object {
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6d6f7574682f666974746564.v5/binding"
    }
}
