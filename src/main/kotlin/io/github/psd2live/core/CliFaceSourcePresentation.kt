package io.github.psd2live.core

import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigFittedMouthOutlineDefinition
import io.github.psd2live.core.modifier.RigModifierSource
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.MeshForm

/** Binds unchanged source face topology directly to the RAW export atlas. */
internal object CliFaceSourcePresentation {
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
