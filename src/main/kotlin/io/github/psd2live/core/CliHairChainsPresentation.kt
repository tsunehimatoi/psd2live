package io.github.psd2live.core

import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.recipe.RigModules
import org.umamo.runtime.model.Deformer
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
        val ids = model.drawables.filter { it.id.raw in targets && it.mesh != null }.associate { mesh ->
            preview.rig.layerIdByDrawableId.getValue(mesh.id.raw) to mesh.id
        }
        if (ids.isEmpty()) return preview

        // Native generation normalizes source vertices directly in the chain frame. Reprojecting an
        // already normalized plane (the worker input) rounds the same coordinates twice.
        val analysis = RigGenerationSource.analyze(preview.analysis.source, sourceConfig)
        val (source, chains) = RigBuilder.attachmentBindingSource(analysis, preview.atlas, sourceConfig, PreviewMeshCache(), ids)
        val nativeMeshes = source.drawables.associateBy { it.id }
        val originals = nativeMeshes.keys.associateBy(SeeThroughLayers::copyId)
        val nativeCarriers = source.deformers.filterIsInstance<Deformer.Warp>()
            .filter { warp -> chains.any { it.kind.hair && it.layerId in ids && it.warpId == warp.id.raw } }
            .associateBy { it.id }
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
        val carriers = model.deformers.map { actual ->
            val native = nativeCarriers[actual.id] ?: return@map actual
            (actual as Deformer.Warp).copy(geometryGrid = native.geometryGrid, blendShapes = native.blendShapes)
        }
        return preview.copy(rig = preview.rig.copy(puppet = model.copy(drawables = drawables, deformers = carriers)))
    }
}
