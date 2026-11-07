package io.github.psd2live.core

import io.github.psd2live.core.modifier.RigModifierStack
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId

/** Adapts a transient raw PSD result to its public export layout. */
internal object CliPresetPresentation {
    fun apply(preview: RigPreviewModel, source: RigGenerationSource.Analyses, moduleIds: List<ParameterId>,
              config: PipelineConfig): RigPreviewModel {
        // Use the source packing order with the pixels produced by the evaluated plugins.
        val sourceOrder = source.textures.layers.mapIndexed { index, layer -> layer.source.id.raw to index }.toMap()
        val exportArt = preview.analysis.copy(layers = preview.analysis.layers.sortedBy {
            sourceOrder[it.source.id.raw] ?: Int.MAX_VALUE
        })
        val atlas = AtlasLayout.pack(exportArt.layers, config)
        val rig = requireNotNull(RigLayerDeletion.rebind(preview.rig, preview.analysis, preview.atlas, exportArt, atlas)) {
            "Raw PSD export cannot bind every generated mesh to its source tile"
        }
        val packed = restoreGeneratedFrameIds(preview.copy(analysis = exportArt, rig = rig, atlas = atlas))
        val ordered = orderExportMetadata(CliMouthOutlinePresentation.orderMeshes(packed, source.geometry), moduleIds, config)
        return CliHairChainsPresentation.apply(CliMouthOutlinePresentation.apply(ordered, config), config)
    }

    private fun restoreGeneratedFrameIds(preview: RigPreviewModel): RigPreviewModel {
        val stack = RigModifierStack.fromJson(requireNotNull(preview.config.rigEdits.modifierStack))
        val rig = preview.rig
        val model = rig.puppet
        // Lowered mesh carriers are discovered during evaluation. Apply their historical names only
        // to the export graph, leaving the stack's own intermediate references intact.
        val aliases = model.deformers.mapNotNull { deformer ->
            if (!deformer.id.raw.startsWith("modifier:")) return@mapNotNull null
            val local = stack.instances.firstNotNullOfOrNull { it.localOutputName(deformer.id.raw) } ?: return@mapNotNull null
            if (!local.startsWith("shared/deformer:")) return@mapNotNull null
            val canonical = local.removePrefix("shared/deformer:")
            require(!canonical.startsWith("SourceChain:")) { "Missing source alias for $canonical" }
            deformer.id to DeformerId(canonical)
        }.toMap()
        if (aliases.isEmpty()) return preview
        require(model.deformers.map { aliases[it.id] ?: it.id }.distinct().size == model.deformers.size) {
            "Raw PSD export frame IDs conflict"
        }
        return preview.copy(rig = rig.copy(puppet = RigIdRemap(deformers = aliases).model(model)))
    }

    private fun orderExportMetadata(preview: RigPreviewModel, moduleIds: List<ParameterId>, config: PipelineConfig): RigPreviewModel {
        val rig = preview.rig
        val puppet = rig.puppet
        val parameters = puppet.parameters.associateBy { it.id }
        val modules = moduleIds.distinct().filter { it in parameters }
        val standard = StandardParameters.all.map { it.id }
        val preset = standard + modules
        val custom = parameters.keys.filterNot { it in preset }
        val output = requireNotNull(rig.modifierOutput)
        // Keep the exported physics order without replacing values returned by the plugins.
        val physicsOrder = PhysicsCatalog.groups(preview.analysis, config, parameters.keys.mapTo(HashSet()) { it.raw })
            .map { it.id }
        val ordered = puppet.copy(parameters = (preset + custom).mapNotNull(parameters::get),
            parameterTree = StandardParameters.tree(custom, modules, parameters.keys), parameterLinks = StandardParameters.links(preset.toSet()))
        return preview.copy(rig = rig.copy(puppet = ordered, modifierOutput = output.copy(
            physics = output.physics.copy(groups = PhysicsCatalog.ordered(output.physics.groups, physicsOrder) { it.id }))))
    }
}
