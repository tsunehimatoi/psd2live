package io.github.psd2live.core

import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigFittedMouthOutlineDefinition
import io.github.psd2live.core.modifier.RigModifierSource
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.MeshForm

/** Binds unchanged source face topology directly to the RAW export atlas. */
internal object CliFaceSourcePresentation {
    fun restoreSingleEyeForms(source: RigPreviewModel, preview: RigPreviewModel, analysis: PipelineAnalysis,
                              config: PipelineConfig): RigPreviewModel {
        val active = source.modifierEvaluation?.entries?.filter { it.status == ModifierStatus.ACTIVE }
            ?.associate { it.instance.type to it.instance } ?: return preview
        if (active["mouth/fitted"]?.takeIf(BuiltinModifierPlugins::matches)?.version != 7 ||
            active["mouth.outline"]?.takeIf(BuiltinModifierPlugins::matches)?.version != 10) return preview

        val model = preview.rig.puppet
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val tags = setOf(SemanticTag.EYEBROW, SemanticTag.NECK, SemanticTag.TOPWEAR)
        // The eye's ordinary keys also govern which brow shapes Cubism lowers into keyforms.
        val sourceTags = tags + setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.IRIDES)
        val selected = model.drawables.mapNotNull { drawable ->
            val layer = layers[preview.rig.ownLayerIdByDrawableId[drawable.id.raw]] ?: return@mapNotNull null
            (drawable.id.raw to layer).takeIf { drawable.mesh != null && layer.semantic.tag in sourceTags }
        }.toMap()
        if (selected.isEmpty()) return preview
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        val parameters = model.parameters.mapTo(hashSetOf()) { it.id }
        val projected = RigBuilder.closedMouthShellProjection(context, config, preview.atlas, cache, model, selected, parameters,
            includeFaceLean = true)
        val nativeMeshes = projected.drawables.associateBy { it.id }
        val drawables = model.drawables.map { drawable ->
            if (selected[drawable.id.raw]?.semantic?.tag !in tags) return@map drawable
            val native = nativeMeshes[drawable.id] ?: return@map drawable
            require(drawable.parentDeformerId == native.parentDeformerId) {
                "CLI single-eye forms require the source parent for ${drawable.id.raw}"
            }
            drawable.copy(mesh = native.mesh, geometryGrid = native.geometryGrid, blendShapes = native.blendShapes)
        }
        val browFrames = selected.values.filter { it.semantic.tag == SemanticTag.EYEBROW }
            .mapTo(hashSetOf()) { context.parentAndFrame(it, config).first }
        val nativeFrames = projected.deformers.filterIsInstance<Deformer.Warp>().associateBy { it.id }
        val deformers = model.deformers.map { deformer ->
            if (deformer !is Deformer.Warp || deformer.id !in browFrames) return@map deformer
            val native = nativeFrames.getValue(deformer.id)
            require(deformer.parent == native.parent && deformer.rows == native.rows && deformer.columns == native.columns)
            deformer.copy(geometryGrid = native.geometryGrid)
        }
        return preview.copy(rig = preview.rig.copy(puppet = model.copy(drawables = drawables, deformers = deformers)))
    }

    fun bindUvs(beforePacking: RigPreviewModel, packed: RigPreviewModel, config: PipelineConfig): RigPreviewModel {
        val analysis = RigGenerationSource.analyze(packed.analysis.source, config)
        val layers = analysis.layers.filter {
            it.semantic.tag in setOf(SemanticTag.EYEBROW, SemanticTag.IRIDES, SemanticTag.NOSE)
        }.mapTo(hashSetOf()) { it.source.id.raw }
        val original = beforePacking.rig.puppet.drawables.associateBy { it.id }
        val ids = original.values.mapNotNull { drawable ->
            if (drawable.mesh == null || drawable.textureSourceId != null) return@mapNotNull null
            val layer = beforePacking.rig.ownLayerIdByDrawableId[drawable.id.raw] ?: return@mapNotNull null
            if (layer !in layers || layer !in beforePacking.atlas.placementByLayerId || layer !in packed.atlas.placementByLayerId)
                return@mapNotNull null
            layer to drawable.id
        }.toMap()
        if (ids.isEmpty()) return packed
        val cache = PreviewMeshCache()
        fun source(atlas: PackedAtlas) =
            RigBuilder.attachmentBindingSource(analysis, atlas, config, cache, ids).first.drawables.associateBy { it.id }

        val prior = source(beforePacking.atlas)
        val next = source(packed.atlas)
        val model = packed.rig.puppet
        val drawables = model.drawables.map { drawable ->
            val canonical = drawable.textureSourceId ?: drawable.id
            val before = original[drawable.id]?.mesh ?: return@map drawable
            val expected = prior[canonical]?.mesh ?: return@map drawable
            val desired = next[canonical]?.mesh ?: return@map drawable
            val actual = drawable.mesh ?: return@map drawable
            if (!before.indices.contentEquals(expected.indices) || !before.uvs.contentEquals(expected.uvs) ||
                !actual.indices.contentEquals(desired.indices) || actual.uvs.contentEquals(desired.uvs)) return@map drawable

            drawable.copy(mesh = DrawableMesh(actual.positions, desired.uvs, actual.indices))
        }
        return if (drawables == model.drawables) packed
        else packed.copy(rig = packed.rig.copy(puppet = model.copy(drawables = drawables)))
    }

    fun restoreBrowForms(source: RigPreviewModel, preview: RigPreviewModel, config: PipelineConfig): RigPreviewModel {
        val outline = source.modifierEvaluation?.entries?.singleOrNull {
            it.status == ModifierStatus.ACTIVE && it.instance.type == "mouth.outline" && it.instance.version == 8
        }?.instance ?: return preview
        if (!BuiltinModifierPlugins.matches(outline)) return preview
        val owner = RigModifierSource.of(source)
        val sourceBrows = owner.analysis.layers.filter { it.semantic.tag == SemanticTag.EYEBROW }
            .mapTo(hashSetOf()) { it.source.id.raw }
        val context = RigFittedMouthOutlineDefinition.sharedFrameDefinition(owner).authorContext(outline)
        fun phase(name: String) = RigIrBinary.decode(context.resources.getValue(name).read())
        val prior = phase("before").meshes.associateBy { it.id }
        val desired = phase("after")
        val unchanged = desired.meshes.filter { mesh ->
            val original = prior[mesh.id]
            source.rig.ownLayerIdByDrawableId[mesh.id] in sourceBrows && original != null &&
                mesh.parent == original.parent && mesh.geometry == original.geometry &&
                mesh.offsets == original.offsets && mesh.shapes == original.shapes
        }.mapTo(hashSetOf()) { it.id }
        if (unchanged.isEmpty()) return preview
        val forms = PuppetIr.toPuppet(desired).drawables.filter { it.id.raw in unchanged }.associateBy { it.id }
        val model = preview.rig.puppet
        val repaired = linkedSetOf<String>()
        val drawables = model.drawables.map { drawable ->
            val native = forms[drawable.id] ?: return@map drawable
            val expected = native.mesh ?: return@map drawable
            val actual = drawable.mesh ?: return@map drawable
            if (drawable.parentDeformerId != native.parentDeformerId || !actual.indices.contentEquals(expected.indices)) return@map drawable
            val update = drawable.copy(mesh = DrawableMesh(expected.positions, actual.uvs, actual.indices),
                geometryGrid = native.geometryGrid, blendShapes = native.blendShapes)
            repaired += drawable.id.raw
            update
        }
        if (repaired.isEmpty()) return preview

        // RAW brow copies inherit the final source keyforms, with the normal see-through alpha and order.
        val copies = model.drawables.mapNotNull { drawable ->
            val original = drawable.textureSourceId ?: return@mapNotNull null
            if (original.raw in repaired && drawable.id == SeeThroughLayers.copyId(original)) drawable.id.raw to original.raw else null
        }.toMap()
        val corrected = model.copy(drawables = drawables)
        val shown = preview.copy(config = config, rig = preview.rig.copy(seeThroughCopies = copies))
        val refreshed = SeeThroughLayers.refresh(corrected, shown).drawables.associateBy { it.id }
        return preview.copy(rig = preview.rig.copy(puppet = corrected.copy(drawables = drawables.map { drawable ->
            if (drawable.id.raw !in copies) return@map drawable
            val copy = refreshed[drawable.id] ?: return@map drawable
            // Placement already came from the full preset. Refresh only the forms that follow the source.
            drawable.copy(mesh = copy.mesh, geometryGrid = copy.geometryGrid, blendShapes = copy.blendShapes.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, drawable.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
                })
            })
        })))
    }
}
