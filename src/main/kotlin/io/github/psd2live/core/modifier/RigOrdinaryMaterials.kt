package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import org.umamo.format.art.SourceArt
import org.umamo.format.art.LayerBlend
import org.umamo.runtime.model.*
import java.text.Normalizer
import java.util.Locale

/** The material base before author edits: source folders and rasters, without semantic binding defaults. */
internal object RigOrdinaryMaterials {
    private val fringeShadowNames = setOf("刘海脸部投影", "瀏海臉部投影", "fringe cast shadow")
    private val eyeAndBrow = setOf(SemanticTag.EYEBROW, SemanticTag.EYEWHITE, SemanticTag.IRIDES,
        SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
    private val hairPaint = setOf(PartRole.SHADOW, PartRole.HIGHLIGHT)

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

    fun of(base: BuiltRig, analysis: PipelineAnalysis, layerVisibility: Map<String, Boolean> = emptyMap()): BuiltRig {
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
        val placement = source.layers.associate { layer ->
            val original = layer.groupPath
            var path = original
            if (layerVisibility[layer.id.raw] == true) {
                // Explicitly enabled artwork may come from a hidden source folder.
                for (ancestor in generateSequence(original, ::parent).takeWhile { it.isNotEmpty() }) {
                    if (groupInfo[ancestor]?.visible == false) path = parent(ancestor)
                }
            }
            layer.id.raw to path
        }
        val clipped = clipping(source, base.layerIdByDrawableId)
        val drawables = base.puppet.drawables.map { drawable ->
            val layer = base.layerIdByDrawableId[drawable.id.raw]
            drawable.copy(parentDeformerId = null, geometryGrid = null, channelGrids = ChannelGrids.Empty,
                blendShapes = emptyList(), maskedBy = listOfNotNull(clipped[layer]), invertMask = false)
        }
        val classified = analysis.layers.associateBy { it.source.id.raw }
        fun visibleLayer(drawable: Drawable): ClassifiedLayer? {
            val layer = base.layerIdByDrawableId[drawable.id.raw]?.let(classified::get) ?: return null
            if (layer.source.opacity <= 0f || layer.opaquePixels <= 0 || !drawable.isVisible) return null
            val path = placement[layer.source.id.raw].orEmpty()
            return layer.takeIf {
                generateSequence(path, ::parent).takeWhile(String::isNotEmpty).none { ancestor ->
                    groupInfo[ancestor]?.let { !it.visible || it.opacity <= 0f } == true
                }
            }
        }
        val visibleLayers by lazy { drawables.mapNotNull { mesh -> visibleLayer(mesh)?.let { mesh.id to it } } }
        fun children(path: String): List<OrgChild> {
            val folders = paths.filter { parent(it) == path }.map { nested ->
                val order = source.layers.filter { it.groupPath == nested || it.groupPath.startsWith("$nested/") }.minOfOrNull { it.order } ?: Int.MAX_VALUE
                order to OrgChild.Part(part(nested))
            }
            val meshes = drawables.mapNotNull { drawable ->
                val layer = base.layerIdByDrawableId[drawable.id.raw]?.let(layers::get) ?: return@mapNotNull null
                if (placement[layer.id.raw] != path) null else layer.order to OrgChild.Drawable(drawable.id)
            }
            return (folders + meshes).sortedBy { it.first }.map { it.second }
        }
        val parts = paths.map { path ->
            val group = groupInfo[path]
            val opacity = group?.opacity?.coerceIn(0f, 1f) ?: 1f
            fun under(candidate: String) = candidate == path || candidate.startsWith("$path/")
            val members = source.layers.filter { under(it.groupPath) }
            val name = group?.name?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT) }
            // An explicitly named painted overlay is visible on the moving hair itself. Clip the composite once so the
            // translucent copies keep their original stacking, mask relationships and shared source opacity.
            val hairMask = if (group != null && PartRoles.isThroughHairGroup(group.name) && members.any { classified[it.id.raw]?.semantic?.tag in eyeAndBrow }) {
                val firstOrder = members.minOf { it.order }
                visibleLayers.filter { (_, layer) -> layer.semantic.tag == SemanticTag.FRONT_HAIR && layer.semantic.role !in hairPaint &&
                    layer.source.order > firstOrder && !under(layer.source.groupPath) }.map { it.first }
            } else emptyList()
            val blend = when {
                group == null || group.clipped -> null
                !group.passThrough && group.blend == LayerBlend.Multiply -> BlendMode.MultiplyPremultiplied
                group.blend == LayerBlend.Normal && (opacity < 1f || hairMask.isNotEmpty()) &&
                    (!group.passThrough || members.all { !it.clipped && it.blend == LayerBlend.Normal } &&
                        source.groups.filter { under(it.path) }.all { !it.clipped && (it.passThrough || it.blend == LayerBlend.Normal) }) ->
                    BlendMode.Normal
                else -> null
            }
            val shadowMask = if (blend != null && name in fringeShadowNames && members.isNotEmpty()) {
                val firstOrder = members.minOf { it.order }
                visibleLayers.filter { (_, layer) -> layer.semantic.tag == SemanticTag.FACE &&
                    layer.source.order > firstOrder && !under(layer.source.groupPath) }
                    .maxByOrNull { (_, layer) -> layer.opaquePixels }?.first
            } else null
            val order = if (blend != null) drawables.filter { drawable ->
                placement[base.layerIdByDrawableId[drawable.id.raw]]?.let(::under) == true
            }.maxOfOrNull { it.drawOrder }?.toInt() else null
            Part(part(path), group?.name ?: path.substringAfterLast('/'), children(path),
                isVisible = group?.visible ?: true,
                groupMode = if (blend != null) PartGroupMode.Isolated else PartGroupMode.PassThrough,
                drawOrder = order ?: DEFAULT_DRAW_ORDER,
                composite = if (blend != null) PartComposite(blendMode = blend, maskedBy = hairMask + listOfNotNull(shadowMask),
                    opacity = opacity) else PartComposite())
        }
        val puppet = base.puppet.copy(parameters = emptyList(), parameterTree = emptyList(), parameterLinks = emptyList(),
            deformers = emptyList(), parts = parts, drawables = drawables, rootPartId = null, rootChildren = children(""),
            glues = emptyList(), deformPaths = emptyList()).withDerivedRenderRoot()
        return base.copy(puppet = puppet, unbound = null)
    }
}
