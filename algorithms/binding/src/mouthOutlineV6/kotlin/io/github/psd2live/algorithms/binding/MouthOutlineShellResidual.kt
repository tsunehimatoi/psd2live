package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*

/** Transports a fallback head shell's changes through the live eye, brow and body frames. */
internal object MouthOutlineShellResidual {
    fun apply(input: RigIR, outline: ModifierModelChanges, instance: RigModifierInstance,
              context: ModifierAlgorithmContext, checkpoint: () -> Unit): ModifierModelChanges {
        val previous = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("outlineShellBefore").read()))
        val desired = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("outlineShellAfter").read()))
        val sourceMeshes = previous.drawables.associateBy { it.id }
        val desiredMeshes = desired.drawables.associateBy { it.id }
        val liveMeshes = input.meshes.associateBy { it.id }
        val sourceNodes = previous.deformers.associateBy { it.id }
        val desiredNodes = desired.deformers.associateBy { it.id }
        val liveNodes = input.deformers.associateBy { it.id }
        val providers = context.upstream.distinctBy { it.instanceId }.map {
            RigModifierInstance(requireNotNull(it.instanceId), it.type, it.version, it.targets,
                config = it.config, outputAliases = it.outputAliases, plugin = it.plugin)
        }
        val scaffold = providers.single { it.type == "rig.scaffold" }
        fun alias(target: ModifierTarget, id: DeformerId): DeformerId? =
            providers.firstNotNullOfOrNull { provider ->
                val mapped = ScaffoldBinding.deformerId(provider, target.id, id.raw, shared = true)
                DeformerId(mapped).takeIf { mapped in liveNodes }
            }
        val targets = instance.targets.filter { target ->
            checkpoint()
            val old = sourceMeshes[DrawableId(target.id)] ?: return@filter false
            val next = desiredMeshes.getValue(old.id)
            val branch = generateSequence(old.parentDeformerId) { sourceNodes.getValue(it).parent }.toList()
            old.parentDeformerId == next.parentDeformerId &&
                old.parentDeformerId?.let { alias(target, it)?.raw } == liveMeshes.getValue(target.id).parent &&
                branch.all { alias(target, it) != null && it in desiredNodes } &&
                (PuppetIr.meshToIr(old) != PuppetIr.meshToIr(next) ||
                    branch.any { PuppetIr.deformerToIr(sourceNodes.getValue(it)) != PuppetIr.deformerToIr(desiredNodes.getValue(it)) })
        }
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = previous to desired
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter) = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                sourceNodes.keys.mapNotNull { id -> alias(target, id)?.let { id to it } }.toMap()
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                error("Fitted outline shell projection cannot replace a carrier branch")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Fitted outline shell projection cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val merged = BindingMerge.apply(PuppetIr.toPuppet(input), instance.copy(targets = targets), source, checkpoint)
        // Use the exact source grid when the live mesh has no local edits to transport.
        val native = targets.mapNotNull { target ->
            val old = PuppetIr.meshToIr(sourceMeshes.getValue(DrawableId(target.id)))
            val live = liveMeshes.getValue(target.id)
            if (live.geometry != old.geometry || live.offsets != old.offsets || live.shapes != old.shapes) null
            else target.id to PuppetIr.meshToIr(desiredMeshes.getValue(DrawableId(target.id)))
        }.toMap()
        val meshes = outline.meshes.updated.associateByTo(linkedMapOf()) { it.before.id }
        for (drawable in merged.drawables) {
            val next = native[drawable.id.raw] ?: PuppetIr.meshToIr(drawable)
            val live = liveMeshes.getValue(next.id)
            if (live.geometry == next.geometry && live.offsets == next.offsets && live.shapes == next.shapes) continue
            val existing = meshes[next.id]?.after ?: live
            meshes[next.id] = ModifierObjectUpdate(live,
                existing.copy(geometry = next.geometry, offsets = next.offsets, shapes = next.shapes))
        }
        // The outline already projects its shared head and body frames. Carry only the dependent
        // private eye/brow turn carriers and hanging arms from the local residual.
        val dependentWarps = sourceNodes.keys.filter { id ->
            id.raw.endsWith("_Turn") || id.raw == "DeformArmHang_L" || id.raw == "DeformArmHang_R"
        }.mapNotNullTo(hashSetOf()) { id -> targets.firstNotNullOfOrNull { alias(it, id)?.raw } }
        val deformers = outline.deformers.updated.associateByTo(linkedMapOf()) { it.before.id }
        for (deformer in merged.deformers) {
            val next = PuppetIr.deformerToIr(deformer)
            if (next.id !in dependentWarps) continue
            val live = liveNodes.getValue(next.id)
            if (next != live) deformers[next.id] = ModifierObjectUpdate(live, next)
        }
        return outline.copy(
            meshes = ModifierObjectChanges(outline.meshes.added, meshes.values.toList(), outline.meshes.removed, outline.meshes.reused),
            deformers = ModifierObjectChanges(outline.deformers.added, deformers.values.toList(), outline.deformers.removed, outline.deformers.reused),
        )
    }
}
