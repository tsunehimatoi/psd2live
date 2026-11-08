package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthOutlineV4Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV7Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV8Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV10Algorithm
import io.github.psd2live.core.modifier.RigModifierStack
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.withDerivedRenderRoot

/** Final source pose forms for generated ribbons in a fresh raw-PSD CLI export. */
internal object CliMouthOutlinePresentation {
    /**
     * Run after preset evaluation and shared-atlas binding, before compiling the export.
     * The outline worker owns the output geometry, pixels and paths. The CLI supplies only the
     * historical draw order and the forms sampled from the final source recipe.
     */
    fun apply(preview: RigPreviewModel, sourceConfig: PipelineConfig): RigPreviewModel {
        if (sourceConfig.meshOnly || !sourceConfig.mouthOutlineEnabled) return preview
        val modern = sourceConfig.recipe.modern
        val versions = if (modern) setOf(MouthOutlineV7Algorithm.VERSION, MouthOutlineV8Algorithm.VERSION, MouthOutlineV10Algorithm.VERSION)
            else setOf(MouthOutlineV4Algorithm.VERSION)
        val serialized = preview.config.rigEdits.modifierStack ?: return preview
        val outline = RigModifierStack.fromJson(serialized).instances.singleOrNull {
            it.enabled && it.type == "mouth.outline" && it.version in versions
        } ?: return preview

        val source = preview.analysis.source
        val measured = RigGenerationSource.prepare(RigGenerationSource.analyze(source, sourceConfig), sourceConfig).geometry
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(measured, sourceConfig, cache)
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val lips = measured.layers.filter { it.source is MouthLipLayer }.associateBy { it.source.id.raw }
        val step = RigBuilder.generatedOrderStep(sourceConfig.drawOrderStep, context.analysis.layers.size)
        val model = preview.rig.puppet
        val byId = model.drawables.associateBy { it.id }
        val projected = buildMap {
            for (target in outline.targets) {
                val owner = byId[DrawableId(target.id)] ?: continue
                val layer = layers[preview.rig.layerIdByDrawableId[target.id]] ?: continue
                for (side in 0..1) {
                    val id = DrawableId(outline.outputId("mouth-lip:${target.id}:$side"))
                    val actual = byId[id] ?: continue
                    val lip = lips[MouthLipLayer.idFor(layer.source.id.raw, side)] ?: continue
                    val front = model.drawables.filter { it.id == owner.id || owner.id in it.maskedBy }
                        .maxOf { it.drawOrder }
                    val order = (sourceConfig.drawOrderOverrides[lip.source.id.raw]
                        ?: sourceConfig.drawOrderOverrides[id.raw]
                        ?: front + (step / 2).coerceAtLeast(1)).coerceIn(0f, 1000f)
                    if (!modern) {
                        put(id, actual.copy(drawOrder = order))
                        continue
                    }
                    val native = RigBuilder.sourceOutlineRibbon(owner, layer, lip, side, context, sourceConfig,
                        preview.atlas, cache) ?: continue
                    val observed = requireNotNull(actual.mesh)
                    val sourceMesh = requireNotNull(native.mesh)
                    require(observed.positions.contentEquals(sourceMesh.positions) && observed.indices.contentEquals(sourceMesh.indices)) {
                        "CLI lip forms require source topology for ${id.raw}"
                    }
                    put(id, actual.copy(drawOrder = order, geometryGrid = native.geometryGrid,
                        blendShapes = native.blendShapes))
                }
            }
        }
        if (projected.isEmpty()) return preview
        val sourceForms = model.copy(drawables = model.drawables.map { projected[it.id] ?: it })
        if (!modern) return preview.copy(rig = preview.rig.copy(puppet = sourceForms))
        val lowered = CubismBlendShapes.lawful(sourceForms)
        val output = lowered.drawables.filter { it.id in projected }.associateBy { it.id }
        require(lowered.parameters == model.parameters && output.values.all { mesh ->
            val before = projected.getValue(mesh.id)
            mesh.parentDeformerId == before.parentDeformerId && mesh.mesh === before.mesh
        }) { "CLI lip forms require mesh-local lowering" }
        val adjusted = model.copy(drawables = model.drawables.map { output[it.id] ?: it })
        return preview.copy(rig = preview.rig.copy(puppet = adjusted))
    }

    fun orderMeshes(preview: RigPreviewModel, measured: PipelineAnalysis): RigPreviewModel {
        val rig = preview.rig
        val meshByLayer = rig.ownLayerIdByDrawableId.entries.groupBy({ it.value }, { DrawableId(it.key) })
        val lips = measured.layers.mapNotNull { it.source as? MouthLipLayer }.sortedBy { it.side }
        val pairs = lips.mapNotNull { lip ->
            val owner = meshByLayer[lip.ownerId]?.singleOrNull() ?: return@mapNotNull null
            val mesh = meshByLayer[lip.id.raw]?.singleOrNull() ?: return@mapNotNull null
            owner to mesh
        }
        if (pairs.isEmpty()) return preview
        val lipByOwner = pairs.groupBy({ it.first }, { it.second })
        val ownerByLip = pairs.associate { (owner, lip) -> lip to owner }
        fun <T> order(items: List<T>, id: (T) -> DrawableId?): List<T> {
            val byId = items.mapNotNull { item -> id(item)?.let { it to item } }.toMap()
            return buildList {
                for (item in items) {
                    val mesh = id(item)
                    if (ownerByLip[mesh] in byId) continue
                    add(item)
                    lipByOwner[mesh].orEmpty().mapNotNullTo(this, byId::get)
                }
            }
        }
        fun children(items: List<OrgChild>) = order(items) { (it as? OrgChild.Drawable)?.id }
        val puppet = rig.puppet
        return preview.copy(rig = rig.copy(puppet = puppet.copy(drawables = order(puppet.drawables) { it.id },
            parts = puppet.parts.map { it.copy(children = children(it.children)) },
            rootChildren = children(puppet.rootChildren)).withDerivedRenderRoot()))
    }
}
