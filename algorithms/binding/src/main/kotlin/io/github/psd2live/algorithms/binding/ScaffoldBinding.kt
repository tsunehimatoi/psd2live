package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import org.umamo.edit.withOrgChildMoved
import org.umamo.runtime.model.*

/** Builds deterministic binding hierarchy from captured static source frames. */
object ScaffoldBinding {
    interface Source {
        fun scaffoldFor(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): PuppetModel
        fun originalMaterial(actual: Drawable, desired: Drawable): Boolean
    }

    /** Read-only source adapter for callers that captured their templates and ordinary material meshes. */
    class CapturedSource(templates: Map<String, PuppetModel>, materials: List<Drawable>) : Source {
        private val templates = templates.toMap()
        private val materials = materials.associateBy { it.id }
        override fun scaffoldFor(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit): PuppetModel {
            checkpoint()
            return templates[target.id] ?: error("Missing captured scaffold for ${target.id}")
        }
        override fun originalMaterial(actual: Drawable, desired: Drawable) =
            isOriginalMaterial(actual, desired, materials[actual.id])
    }

    /** Exact untouched material can use the source frame without inverse coordinate transport. */
    fun isOriginalMaterial(actual: Drawable, desired: Drawable, original: Drawable?): Boolean {
        val mesh = actual.mesh ?: return false
        val base = original?.mesh ?: return false
        val native = desired.mesh ?: return false
        return actual.parentDeformerId == null && actual.geometryGrid == null && actual.blendShapes.isEmpty() &&
            mesh.positions.contentEquals(base.positions) && mesh.uvs.contentEquals(base.uvs) && mesh.indices.contentEquals(base.indices) &&
            mesh.uvs.contentEquals(native.uvs) && mesh.indices.contentEquals(native.indices)
    }

    fun deformerId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == "rig.scaffold" && instance.version >= 2) =
        instance.outputAliases["shared/deformer:$canonical"] ?: instance.outputId(if (shared) "shared/deformer:$canonical" else "$mesh/deformer:$canonical")
    fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean = instance.type == "rig.scaffold" && instance.version >= 2) =
        instance.outputAliases["shared/part:$canonical"] ?: instance.outputId(if (shared) "shared/part:$canonical" else "$mesh/part:$canonical")

    fun apply(input: PuppetModel, instance: RigModifierInstance, source: Source, checkpoint: () -> Unit): PuppetModel =
        bind(input, instance, source, checkpoint, null)

    fun applyChanges(input: RigIR, instance: RigModifierInstance, source: Source, checkpoint: () -> Unit): ModifierModelChanges =
        ScaffoldChanges.build(input, instance, checkpoint) { model, writes ->
            bind(model, instance, source, checkpoint, writes)
        }

    private fun bind(
        input: PuppetModel, instance: RigModifierInstance, source: Source, checkpoint: () -> Unit,
        writes: ScaffoldChanges.Writes?,
    ): PuppetModel {
        var current = input
        if (instance.version >= 2) {
            val parents = instance.targets.map { target -> current.drawables.single { it.id.raw == target.id }.parentDeformerId }.distinct()
            require(parents.size == 1) { "A shared binding hierarchy requires targets in the same authored frame" }
        }
        val emittedDeformers = HashSet<DeformerId>()
        val emittedParts = HashSet<PartId>()
        val sharedTemplate = if (instance.outputAliases.keys.any { it.startsWith("shared/deformer:") })
            source.scaffoldFor(instance, instance.targets.first(), checkpoint) else null
        for (target in instance.targets) {
            checkpoint()
            val template = source.scaffoldFor(instance, target, checkpoint)
            val deformers = template.deformers.associateBy { it.id }
            val partParents = template.parts.flatMap { part -> part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id } }.toMap()
            val meshId = DrawableId(target.id)
            val actual = current.drawables.single { it.id == meshId }
            val upstreamParent = actual.parentDeformerId
            val desired = requireNotNull(template.drawables.singleOrNull { it.id == meshId }) { "No binding scaffold for ${target.id}" }
            val branch = LinkedHashSet<DeformerId>()
            var ancestor = desired.parentDeformerId
            while (ancestor != null) {
                require(branch.add(ancestor)) { "Cyclic source binding scaffold" }
                ancestor = deformers.getValue(ancestor).parent
            }
            require(branch.isNotEmpty()) { "The source layer has no binding hierarchy: ${target.id}" }
            val orgParent = template.parts.singleOrNull { OrgChild.Drawable(meshId) in it.children }?.id
            val partBranch = LinkedHashSet<PartId>()
            for (start in branch.mapNotNull { deformers.getValue(it).partId } + listOfNotNull(orgParent)) {
                var part: PartId? = start
                val seen = HashSet<PartId>()
                while (part != null) {
                    require(seen.add(part)) { "Cyclic source part hierarchy" }
                    partBranch += part
                    part = partParents[part]
                }
            }
            if (sharedTemplate != null) partBranch += template.parts.filter {
                "shared/part:${it.id.raw}" in instance.outputAliases
            }.map { it.id }
            val remap = ModelIdRemap(
                deformers = branch.associateWith { DeformerId(deformerId(instance, target.id, it.raw)) },
                parts = partBranch.associateWith { PartId(partId(instance, target.id, it.raw)) })
            val clonedDeformers = template.deformers.filter { it.id in branch }.map { original ->
                val cloned = remap.deformer(original)
                if (original.parent != null || upstreamParent == null) cloned else {
                    require(cloned is Deformer.Warp) { "The binding scaffold root must be a Warp" }
                    val grid = requireNotNull(cloned.geometryGrid)
                    require(grid.axes.isEmpty() && grid.cells.size == 1) { "The binding scaffold must be static" }
                    val points = grid.cells.single().form.controlPoints
                    val carried = BindingFrames.pointsInParent(current, actual, points, upstreamParent, instance.outputId("$meshId/frame"), checkpoint)
                    cloned.copy(parent = upstreamParent, geometryGrid = KeyformGrid(emptyList(),
                        listOf(KeyformCell(IntArray(0), WarpLatticeForm(carried)))))
                }
            }
            val clonedParts = template.parts.filter { it.id in partBranch }.map { original ->
                remap.part(original.copy(children = original.children.filter { it is OrgChild.Part && it.id in partBranch }))
            }
            require(clonedDeformers.all { added -> current.deformers.none { it.id == added.id } ||
                added.id in emittedDeformers && current.deformers.single { it.id == added.id } == added }) { "Binding scaffold output ID collision: ${instance.id}" }
            require(clonedParts.all { added -> current.parts.none { it.id == added.id } || added.id in emittedParts &&
                current.parts.single { it.id == added.id }.copy(children = emptyList()) == added.copy(children = emptyList()) }) { "Binding scaffold part ID collision: ${instance.id}" }
            val newDeformers = clonedDeformers.filter { emittedDeformers.add(it.id) }
            val newParts = clonedParts.filter { emittedParts.add(it.id) }
            val sharedParts = clonedParts.associateBy { it.id }
            val parts = current.parts.map { part -> sharedParts[part.id]?.let { next ->
                part.copy(children = (part.children + next.children).distinct())
            } ?: part }
            current = current.copy(deformers = current.deformers + newDeformers, parts = parts + newParts,
                rootChildren = (current.rootChildren + partBranch.filter { partParents[it] !in partBranch }.map { OrgChild.Part(remap.part(it)) }).distinct())
                .withDerivedRenderRoot()
            val destination = remap.deformer(requireNotNull(desired.parentDeformerId))
            current = if (source.originalMaterial(actual, desired)) current.copy(drawables = current.drawables.map {
                if (it.id == meshId) it.copy(parentDeformerId = destination, mesh = desired.mesh, geometryGrid = desired.geometryGrid) else it
            }) else {
                val rebound = current.copy(drawables = current.drawables.map {
                    if (it.id == meshId) it.copy(parentDeformerId = destination) else it
                }).withDerivedRenderRoot()
                if (actual.parentDeformerId != destination) BindingFrames.carriedToParent(current, rebound, meshId, checkpoint) else rebound
            }
            writes?.meshes?.add(meshId)
            if (orgParent != null) {
                val child = OrgChild.Drawable(meshId)
                if (writes != null) {
                    // Moving the target detaches every existing membership before attaching to the new part.
                    for (part in current.parts) {
                        if (part.id !in emittedParts && child in part.children) writes.updatedParts += part.id
                    }
                }
                current = current.withOrgChildMoved(child, remap.part(orgParent), null)
            }
        }
        if (sharedTemplate != null) {
            val mesh = instance.targets.first().id
            val remap = ModelIdRemap(parts = sharedTemplate.parts.associate {
                it.id to PartId(partId(instance, mesh, it.id.raw))
            })
            val desiredParts = sharedTemplate.parts.map(remap::part).associateBy { it.id }
            val deformerOrder = instance.targets.flatMap { target -> sharedTemplate.deformers.mapIndexed { index, value ->
                DeformerId(deformerId(instance, target.id, value.id.raw)) to index
            } }.toMap()
            fun ordered(children: List<OrgChild>, desired: List<OrgChild>) =
                children.filter { it !in desired } + desired.filter { it in children }
            current = current.copy(
                deformers = current.deformers.filter { it.id !in emittedDeformers } +
                    current.deformers.filter { it.id in emittedDeformers }.sortedBy { deformerOrder.getValue(it.id) },
                parts = current.parts.map { part -> desiredParts[part.id]?.let {
                    val children = ordered(part.children, it.children)
                    if (children != part.children && part.id !in emittedParts) writes?.updatedParts?.add(part.id)
                    part.copy(children = children)
                } ?: part },
                rootChildren = ordered(current.rootChildren, sharedTemplate.rootChildren.map(remap::child)),
            )
        }
        writes?.addedParts?.addAll(emittedParts)
        writes?.addedDeformers?.addAll(emittedDeformers)
        checkpoint()
        return current.withDerivedRenderRoot()
    }

}
