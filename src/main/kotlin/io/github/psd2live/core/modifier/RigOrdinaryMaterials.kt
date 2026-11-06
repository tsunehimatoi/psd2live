package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.*

/** The material base before author edits: source folders and rasters, without semantic binding defaults. */
internal object RigOrdinaryMaterials {
    fun clipping(source: SourceArt, layersByMesh: Map<String, String>): Map<String, DrawableId> {
        val byLayer = layersByMesh.entries.groupBy { it.value }.mapValues { DrawableId(it.value.first().key) }
        val clipped = HashMap<String, DrawableId>()
        for ((_, group) in source.layers.groupBy { it.groupPath }) {
            var below: DrawableId? = null
            for (layer in group.sortedByDescending { it.order }) {
                if (layer.clipped) below?.let { clipped[layer.id.raw] = it }
                else below = byLayer[layer.id.raw]
            }
        }
        return clipped
    }

    fun of(base: BuiltRig, analysis: PipelineAnalysis): BuiltRig {
        val source = analysis.source
        val layers = source.layers.associateBy { it.id.raw }
        val paths = LinkedHashSet<String>()
        for (path in source.groups.map { it.path } + source.layers.map { it.groupPath }) {
            var current = path
            while (current.isNotEmpty()) {
                paths += current
                current = current.substringBeforeLast('/', "")
            }
        }
        fun parent(path: String) = path.substringBeforeLast('/', "")
        fun part(path: String) = PartId("source-group:" + java.util.HexFormat.of().formatHex(path.encodeToByteArray()))
        val groupInfo = source.groups.associateBy { it.path }
        val clipped = clipping(source, base.layerIdByDrawableId)
        val drawables = base.puppet.drawables.map { drawable ->
            val layer = base.layerIdByDrawableId[drawable.id.raw]
            drawable.copy(parentDeformerId = null, geometryGrid = null, channelGrids = ChannelGrids.Empty,
                blendShapes = emptyList(), maskedBy = listOfNotNull(clipped[layer]), invertMask = false)
        }
        fun children(path: String): List<OrgChild> {
            val folders = paths.filter { parent(it) == path }.map { nested ->
                val order = source.layers.filter { it.groupPath == nested || it.groupPath.startsWith("$nested/") }.minOfOrNull { it.order } ?: Int.MAX_VALUE
                order to OrgChild.Part(part(nested))
            }
            val meshes = drawables.mapNotNull { drawable ->
                val layer = base.layerIdByDrawableId[drawable.id.raw]?.let(layers::get) ?: return@mapNotNull null
                if (layer.groupPath != path) null else layer.order to OrgChild.Drawable(drawable.id)
            }
            return (folders + meshes).sortedBy { it.first }.map { it.second }
        }
        val parts = paths.map { path -> Part(part(path), groupInfo[path]?.name ?: path.substringAfterLast('/'), children(path),
            isVisible = groupInfo[path]?.visible ?: true, groupMode = PartGroupMode.PassThrough) }
        val puppet = base.puppet.copy(parameters = emptyList(), parameterTree = emptyList(), parameterLinks = emptyList(),
            deformers = emptyList(), parts = parts, drawables = drawables, rootPartId = null, rootChildren = children(""),
            glues = emptyList(), deformPaths = emptyList()).withDerivedRenderRoot()
        return base.copy(puppet = puppet, unbound = null)
    }
}
