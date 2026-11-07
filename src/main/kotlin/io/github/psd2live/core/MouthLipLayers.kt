package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.art.*
import kotlin.math.*

/** Regenerated assets have stable independent IDs, so selection, visibility and mesh edits persist. */
internal class MouthLipLayer(
    val ownerId: String,
    val side: Int,
    private val owner: SourceLayer,
    override val raster: LayerRaster,
    override val bounds: LayerBounds,
) : SourceLayer by owner {
    override val id = LayerId(idFor(ownerId, side))
    override val name = owner.name + " / " + tr(if (side == 0) "mouth.upperLip" else "mouth.lowerLip")
    override val clipped = false
    override val blend = LayerBlend.Normal

    companion object {
        fun idFor(ownerId: String, side: Int) = "$ownerId::mouth-lip-$side"

        /** Whether [id] names a generated lip ribbon ([idFor]). */
        fun isLipId(id: String) = LIP_ID.matches(id)

        private val LIP_ID = Regex(".+::mouth-lip-\\d+")
    }
}

internal object MouthLipLayers {
    fun prepare(input: PipelineAnalysis, config: PipelineConfig, cache: PreviewMeshCache? = null): PipelineAnalysis {
        val compute = { RigBuildProfile.stage("source artwork: mouth outlines") { prepareUncached(input, config) } }
        if (cache == null || !RigStageCache.enabled) return compute()
        // Outline pixels do not read motion recipes, tuning or binding strength. Keep source and
        // authoring inputs in the key so a repaint, split, deleted layer or pending rig cannot reuse stale art.
        val key = listOf(input, config.deletedLayerIds, config.rigPendingLayerIds, config.rigEdits,
            config.mouthOutlineEnabled, config.meshOnly, config.deferRig, config.mouthColor, config.mouthThickness,
            config.alphaThreshold, config.meshMaxEdgeDistance, config.meshInteriorDensity, config.meshOverrides,
            config.meshFillAlgorithm, config.meshSuppressBoundaryDiagonals, config.meshFillParameters,
            MeshResolution.unitScale(config, input.source), io.github.psd2live.i18n.I18n.currentLanguage)
        return cache.stages.get("mouth outline artwork", 16, key, compute)
    }

    private fun prepareUncached(input: PipelineAnalysis, config: PipelineConfig): PipelineAnalysis {
        val originals = input.layers.filter { it.source !is MouthLipLayer && it.source.id.raw !in config.deletedLayerIds }
        if (!config.mouthOutlineEnabled || config.meshOnly || config.deferRig) return input.copy(layers = originals)
        // A mouth drawn in layers has its own lips and lines: no outline is drawn over them.
        if (originals.any { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.semantic.role in io.github.psd2live.core.fit.FittedMouth.LIPS })
            return input.copy(layers = originals)
        // Version 2 records: superseded layers never feed the face rig, and parts own no ribbons (their meshes are pinned).
        val resolution = PrimitiveResolution.of(config.rigEdits)
        val layout = (input.calibration ?: input).let { if (resolution.active) it.copy(layers = resolution.aggregates(it.layers)) else it }
            .let { analysis ->
                if (config.rigPendingLayerIds.isEmpty()) analysis else {
                    val layers = analysis.layers.filterNot { it.source.id.raw in config.rigPendingLayerIds }
                    analysis.copy(layers = layers, anchors = if (layers.any { it.opaquePixels > 0 }) CharacterAnalyzer.anchorsFor(layers) else analysis.anchors)
                }
            }
        val faceRig = NinePoseFaceRig.from(layout)
        val headSpace = faceRig.coordinateSpace
        // A depth front copies exactly the selected mesh, including its already authored mouth motion.
        val depthFronts = DepthSplit.frontLayerIds(config)
        val unitScale = MeshResolution.unitScale(config, input.source)
        val layers = originals.flatMap { owner ->
            if (owner.source.id.raw in config.rigPendingLayerIds || owner.source.id.raw in depthFronts || resolution.isPartLayer(owner.source.id.raw) || owner.semantic.tag !in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) || owner.opaquePixels == 0) listOf(owner)
            else {
                val rgb = config.mouthColor ?: perimeterColor(owner.source.raster, config.alphaThreshold)
                val columns = trace(owner, config, unitScale, headSpace)
                val lips = if (columns.size < 2) emptyList() else (0..1).mapNotNull { side ->
                    createLipLayer(owner, side, rgb, columns, config.mouthThickness, headSpace)
                        .takeUnless { it.source.id.raw in config.deletedLayerIds }
                }
                lips + owner
            }
        }
        return input.copy(layers = layers)
    }

    /** Dense raster contour used to paint the ribbon, distinct from scaffold topology. */
    internal fun trace(owner: ClassifiedLayer, config: PipelineConfig, unitScale: Float,
                       headSpace: HeadCoordinateSpace): List<MouthColumn> {
        val adaptive = AdaptiveMeshGenerator.generate(
            width = owner.source.raster.width,
            height = owner.source.raster.height,
            rgba = owner.source.raster.rgba,
            alphaThreshold = config.alphaThreshold,
            spacing = max(6f, config.meshMaxEdgeDistance * 0.45f),
            interiorSpacing = max(12f, config.meshInteriorDensity * 0.45f),
            outerMargin = 0f,
            // Lip strokes are too thin for contour row bands.
            edgeMode = MeshEdgeMode.SINGLE,
            fillAlgorithm = config.meshOverrides[owner.source.id.raw]?.fillAlgorithm ?: config.meshFillAlgorithm,
            suppressBoundaryDiagonals = config.meshOverrides[owner.source.id.raw]?.suppressBoundaryDiagonals
                ?: config.meshSuppressBoundaryDiagonals,
            fillParameters = config.meshOverrides[owner.source.id.raw]?.fillParameters ?: config.meshFillParameters,
            unitScale = unitScale,
        )
        if (adaptive == null) return emptyList()
        val positions = FloatArray(adaptive.positions.size)
        for (i in positions.indices step 2) {
            val (x, y) = headSpace.toAligned(owner.source.bounds.left + adaptive.positions[i],
                owner.source.bounds.top + adaptive.positions[i + 1])
            positions[i] = x
            positions[i + 1] = y
        }
        return io.github.psd2live.algorithms.binding.MouthContour.uniformColumns(positions, adaptive.indices)
    }

    private fun createLipLayer(
        owner: ClassifiedLayer,
        side: Int,
        rgb: Int,
        columns: List<MouthColumn>,
        mouthThickness: Float,
        headSpace: HeadCoordinateSpace,
    ): ClassifiedLayer {
        val artwork = io.github.psd2live.algorithms.binding.MouthOutlineRaster.render(
            columns, side, rgb, mouthThickness, headSpace::toCanvas)
        val strokeLeft = artwork.left; val strokeTop = artwork.top
        val strokeWidth = artwork.width; val strokeHeight = artwork.height
        val rgba = artwork.rgba
        val source = MouthLipLayer(
            owner.source.id.raw,
            side,
            owner.source,
            LayerRaster(strokeWidth, strokeHeight, rgba),
            LayerBounds(strokeLeft, strokeTop, strokeWidth, strokeHeight),
        )
        val strokeBounds = Bounds(
            strokeLeft.toFloat(),
            strokeTop.toFloat(),
            (strokeLeft + strokeWidth).toFloat(),
            (strokeTop + strokeHeight).toFloat(),
        )
        return owner.copy(
            source = source,
            bounds = strokeBounds,
            centroidX = strokeBounds.centerX,
            centroidY = strokeBounds.centerY,
            opaquePixels = artwork.opaquePixels,
        )
    }

    internal fun perimeterColor(raster: LayerRaster, alphaThreshold: Int): Int =
        io.github.psd2live.algorithms.binding.MouthOutlineRaster.perimeterColor(
            raster.width, raster.height, raster.rgba, alphaThreshold)
}
