package io.github.psd2live.core

import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RigModules
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableMesh

/** Restores direct source-coordinate sampling for hair in a fresh raw-PSD CLI export. */
internal object CliHairChainsPresentation {
    /** Run after source aliases and shared-atlas binding, before compiling the export. */
    fun apply(preview: RigPreviewModel, sourceConfig: PipelineConfig): RigPreviewModel {
        if (!sourceConfig.recipe.modern || sourceConfig.meshOnly ||
            sourceConfig.recipe.base("hair") !== RigModules.HAIR_CHAINS) return preview
        val serialized = preview.config.rigEdits.modifierStack ?: return preview
        val binding = RigModifierStack.fromJson(serialized).instances.singleOrNull {
            it.enabled && it.type == "hair/chains" && it.version == 2
        } ?: return preview
        val model = preview.rig.puppet
        val targets = binding.targets.mapTo(HashSet()) { it.id }
        val analysis = RigGenerationSource.analyze(preview.analysis.source, sourceConfig)
        val hairLayers = analysis.layers.filter {
            it.semantic.tag == SemanticTag.FRONT_HAIR || it.semantic.tag == SemanticTag.BACK_HAIR
        }.mapTo(HashSet()) { it.source.id.raw }
        val ids = model.drawables.mapNotNull { mesh ->
            val layer = preview.rig.layerIdByDrawableId[mesh.id.raw] ?: return@mapNotNull null
            if (mesh.id.raw in targets && mesh.mesh != null && layer in hairLayers) layer to mesh.id else null
        }.toMap()
        if (ids.isEmpty()) return preview

        // Native generation normalizes source vertices directly in the chain frame. Reprojecting an
        // already normalized plane (the worker input) rounds the same coordinates twice.
        val (source, chains) = RigBuilder.attachmentBindingSource(analysis, preview.atlas, sourceConfig, PreviewMeshCache(), ids)
        val chainIds = chains.filter { it.kind.hair && it.layerId in ids }.mapTo(HashSet()) { it.warpId }
        val nativeCarriers = source.deformers.filterIsInstance<Deformer.Warp>()
            .filter { it.id.raw in chainIds }.associateBy { it.id }
        if (nativeCarriers.isEmpty()) return preview
        val nativeMeshes = source.drawables.filter { it.parentDeformerId in nativeCarriers }.associateBy { it.id }
        val originals = nativeMeshes.keys.associateBy(SeeThroughLayers::copyId)
        val deformers = model.deformers.associateBy { it.id }
        require(nativeCarriers.all { (id, native) ->
            val actual = deformers[id] as? Deformer.Warp
            actual != null && actual.parent == native.parent && actual.partId == native.partId &&
                actual.rows == native.rows && actual.columns == native.columns
        }) { "CLI hair presentation requires historical source chain aliases" }

        val drawables = model.drawables.map { actual ->
            val native = nativeMeshes[originals[actual.id] ?: actual.id] ?: return@map actual
            val observed = requireNotNull(actual.mesh)
            val mesh = requireNotNull(native.mesh)
            require(native.parentDeformerId == actual.parentDeformerId && observed.indices.contentEquals(mesh.indices)) {
                "CLI hair forms require source topology and parent for ${actual.id.raw}"
            }
            actual.copy(mesh = DrawableMesh(mesh.positions, observed.uvs, observed.indices), geometryGrid = native.geometryGrid)
        }
        // Blend-shape lowering appends mesh carriers in drawable order, parent before child.
        val sourceOrder = source.deformers.mapTo(LinkedHashSet()) { it.id }
        for (mesh in drawables) {
            val carriers = ArrayList<DeformerId>()
            var parent = mesh.parentDeformerId
            while (parent != null && parent !in sourceOrder) {
                val carrier = deformers[parent] ?: break
                carriers.add(parent)
                parent = carrier.parent
            }
            sourceOrder.addAll(carriers.asReversed())
        }
        val order = sourceOrder.withIndex().associate { (index, id) -> id to index }
        val carriers = model.deformers.map { actual ->
            val native = nativeCarriers[actual.id] ?: return@map actual
            (actual as Deformer.Warp).copy(geometryGrid = native.geometryGrid, blendShapes = native.blendShapes)
        }.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        return preview.copy(rig = preview.rig.copy(puppet = model.copy(drawables = drawables, deformers = carriers)))
    }
}
