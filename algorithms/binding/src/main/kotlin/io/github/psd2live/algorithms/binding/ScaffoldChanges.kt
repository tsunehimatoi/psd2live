package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierFieldChange
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.ChildRef
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*

internal object ScaffoldChanges {
    class Writes {
        val addedParts = HashSet<PartId>()
        val updatedParts = HashSet<PartId>()
        val addedDeformers = HashSet<DeformerId>()
        val meshes = HashSet<DrawableId>()
    }

    fun build(
        input: RigIR, instance: RigModifierInstance, checkpoint: () -> Unit,
        bind: (PuppetModel, Writes) -> PuppetModel,
    ): ModifierModelChanges {
        checkpoint()
        val targetIds = instance.targets.mapTo(HashSet()) { it.id }
        val targetMeshes = input.meshes.filter { it.id in targetIds }
        val model = PuppetIr.toPuppet(RigIR(
            input.canvas, input.parameters, parts = input.parts, rootChildren = input.rootChildren, rootPart = input.rootPart,
            deformers = input.deformers, meshes = targetMeshes,
        ))
        val targets = model.drawables.associateBy { it.id.raw }
        // RenderRootDerive uses drawable IDs in source order. Group appearance comes from Parts;
        // the original IR retains every non-target mesh and its drawing channels.
        val scaffoldInput = model.copy(drawables = input.meshes.map { mesh ->
            checkpoint()
            targets[mesh.id] ?: Drawable(DrawableId(mesh.id), mesh.name, null, BlendMode.Normal, emptyList(), null, null,
                drawOrder = mesh.drawOrder)
        })
        val writes = Writes()
        val bound = bind(scaffoldInput, writes)
        val targetById = targetMeshes.associateBy { it.id }
        val partById = input.parts.filter { PartId(it.id) in writes.updatedParts }.associateBy { it.id }
        val updatedParts = bound.parts.mapNotNull { part ->
            val before = partById[part.id.raw] ?: return@mapNotNull null
            checkpoint()
            val after = before.copy(children = part.children.map(::child))
            if (before == after) null else ModifierObjectUpdate(before, after)
        }
        val updatedMeshes = bound.drawables.mapNotNull { drawable ->
            if (drawable.id !in writes.meshes) return@mapNotNull null
            checkpoint()
            val before = targetById.getValue(drawable.id.raw)
            val geometry = PuppetIr.meshToIr(drawable)
            val after = before.copy(parent = geometry.parent, geometry = geometry.geometry, offsets = geometry.offsets, shapes = geometry.shapes)
            if (before == after) null else ModifierObjectUpdate(before, after)
        }
        // Encode only the emitted objects and derived hierarchy, without converting the bound model.
        val emitted = PuppetIr.toIr(PuppetModel(
            parameters = emptyList(),
            parts = bound.parts.filter { it.id in writes.addedParts },
            deformers = bound.deformers.filter { it.id in writes.addedDeformers },
            drawables = emptyList(),
            rootChildren = bound.rootChildren,
            rootPartId = bound.rootPartId,
            renderRoot = bound.renderRoot,
        ))
        checkpoint()
        return ModifierModelChanges(
            parts = ModifierObjectChanges(added = emitted.parts, updated = updatedParts),
            deformers = ModifierObjectChanges(added = emitted.deformers),
            meshes = ModifierObjectChanges(updated = updatedMeshes),
            rootChildren = change(input.rootChildren, emitted.rootChildren),
            renderRoot = change(input.renderRoot, emitted.renderRoot),
        )
    }

    private fun child(child: OrgChild): ChildRef = when (child) {
        is OrgChild.Drawable -> ChildRef.MeshRef(child.id.raw)
        is OrgChild.Part -> ChildRef.PartRef(child.id.raw)
    }

    private fun <T> change(before: T, after: T): ModifierFieldChange<T>? =
        if (before == after) null else ModifierFieldChange(before, after)
}
