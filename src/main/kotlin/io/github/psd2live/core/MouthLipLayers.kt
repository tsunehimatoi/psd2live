package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableMesh
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
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
    }
}

internal object MouthLipLayers {
    fun prepare(input: PipelineAnalysis, config: PipelineConfig): PipelineAnalysis {
        val originals = input.layers.filter { it.source !is MouthLipLayer && it.source.id.raw !in config.deletedLayerIds }
        if (!config.mouthOutlineEnabled || config.meshOnly) return input.copy(layers = originals)
        val layout = input.calibration ?: input
        val faceRig = NinePoseFaceRig.from(layout)
        val headSpace = faceRig.coordinateSpace
        val layers = originals.flatMap { owner ->
            if (owner.semantic.tag !in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) || owner.opaquePixels == 0) listOf(owner)
            else {
                val rgb = config.mouthColor ?: perimeterColor(owner.source.raster, config.alphaThreshold)
                val adaptive = AdaptiveMeshGenerator.generate(
                    width = owner.source.raster.width,
                    height = owner.source.raster.height,
                    rgba = owner.source.raster.rgba,
                    alphaThreshold = config.alphaThreshold,
                    spacing = max(6f, config.meshMaxEdgeDistance * 0.45f),
                    interiorSpacing = max(12f, config.meshInteriorDensity * 0.45f),
                    outerMargin = 0f,
                    innerMargin = config.meshInnerMargin,
                    innerMarginEnabled = false,
                    fillAlgorithm = config.meshOverrides[owner.source.id.raw]?.fillAlgorithm ?: config.meshFillAlgorithm,
                    suppressBoundaryDiagonals = config.meshOverrides[owner.source.id.raw]?.suppressBoundaryDiagonals
                        ?: config.meshSuppressBoundaryDiagonals,
                )
                val lips = if (adaptive != null) {
                    val rigPositions = FloatArray(adaptive.positions.size)
                    for (i in adaptive.positions.indices step 2) {
                        val canvasX = owner.source.bounds.left + adaptive.positions[i]
                        val canvasY = owner.source.bounds.top + adaptive.positions[i + 1]
                        val rig = headSpace.toAligned(canvasX, canvasY)
                        rigPositions[i] = rig.first
                        rigPositions[i + 1] = rig.second
                    }
                    val meshData = MeshData(DrawableMesh(rigPositions, FloatArray(0), adaptive.indices), rigPositions)
                    val columns = MouthContour.uniformColumns(meshData, MouthContour.DEFAULT_SEGMENTS)
                    if (columns.size >= 2) {
                        (0..1).mapNotNull { side ->
                            val lipLayer = createLipLayer(owner, side, rgb, columns, config.mouthThickness, headSpace)
                            if (lipLayer.source.id.raw in config.deletedLayerIds) null else lipLayer
                        }
                    } else emptyList()
                } else emptyList()
                lips + owner
            }
        }
        return input.copy(layers = layers)
    }

    private fun createLipLayer(
        owner: ClassifiedLayer,
        side: Int,
        rgb: Int,
        columns: List<MouthColumn>,
        mouthThickness: Float,
        headSpace: HeadCoordinateSpace,
    ): ClassifiedLayer {
        val path = MouthContour.crossedPath(columns, side)
        val overlap = MouthContour.overlapCount(columns.size)
        val joins = listOf(overlap, overlap + columns.lastIndex)
        val radius = mouthThickness.coerceIn(0.5f, 8f) * 0.5f
        val strokePositions = MouthStrokeMesh.positions(path, radius, joins)
        val indices = MouthStrokeMesh.indices(path.size, joins)

        val canvasPositions = FloatArray(strokePositions.size)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in strokePositions.indices step 2) {
            val canvas = headSpace.toCanvas(strokePositions[i], strokePositions[i + 1])
            canvasPositions[i] = canvas.first
            canvasPositions[i + 1] = canvas.second
            if (canvas.first < minX) minX = canvas.first
            if (canvas.first > maxX) maxX = canvas.first
            if (canvas.second < minY) minY = canvas.second
            if (canvas.second > maxY) maxY = canvas.second
        }

        val pad = (ceil(radius) + 4).toInt()
        val strokeLeft = floor(minX).toInt() - pad
        val strokeTop = floor(minY).toInt() - pad
        val strokeRight = ceil(maxX).toInt() + pad
        val strokeBottom = ceil(maxY).toInt() + pad
        val strokeWidth = max(8, strokeRight - strokeLeft)
        val strokeHeight = max(8, strokeBottom - strokeTop)

        val image = BufferedImage(strokeWidth, strokeHeight, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.color = Color(rgb)

        val triPath = Path2D.Float()
        for (t in indices.indices step 3) {
            val i0 = indices[t] * 2
            val i1 = indices[t + 1] * 2
            val i2 = indices[t + 2] * 2
            triPath.reset()
            triPath.moveTo(canvasPositions[i0] - strokeLeft, canvasPositions[i0 + 1] - strokeTop)
            triPath.lineTo(canvasPositions[i1] - strokeLeft, canvasPositions[i1 + 1] - strokeTop)
            triPath.lineTo(canvasPositions[i2] - strokeLeft, canvasPositions[i2 + 1] - strokeTop)
            triPath.closePath()
            g2.fill(triPath)
        }
        g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (t in indices.indices step 3) {
            val i0 = indices[t] * 2
            val i1 = indices[t + 1] * 2
            val i2 = indices[t + 2] * 2
            triPath.reset()
            triPath.moveTo(canvasPositions[i0] - strokeLeft, canvasPositions[i0 + 1] - strokeTop)
            triPath.lineTo(canvasPositions[i1] - strokeLeft, canvasPositions[i1 + 1] - strokeTop)
            triPath.lineTo(canvasPositions[i2] - strokeLeft, canvasPositions[i2 + 1] - strokeTop)
            triPath.closePath()
            g2.draw(triPath)
        }
        g2.dispose()

        val rgba = ByteArray(strokeWidth * strokeHeight * 4)
        var opaqueCount = 0
        for (y in 0 until strokeHeight) {
            for (x in 0 until strokeWidth) {
                val argb = image.getRGB(x, y)
                val a = (argb ushr 24) and 255
                if (a > 0) opaqueCount++
                val idx = (y * strokeWidth + x) * 4
                rgba[idx] = ((argb shr 16) and 255).toByte()
                rgba[idx + 1] = ((argb shr 8) and 255).toByte()
                rgba[idx + 2] = (argb and 255).toByte()
                rgba[idx + 3] = a.toByte()
            }
        }

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
            opaquePixels = max(1, opaqueCount),
        )
    }

    /** Sample only the outside boundary, excluding interior details and transparent RGB noise. */
    internal fun perimeterColor(raster: LayerRaster, alphaThreshold: Int): Int {
        val w = raster.width
        val h = raster.height
        val maxAlpha = (0 until w * h).maxOfOrNull { raster.rgba[it * 4 + 3].toInt() and 255 } ?: 0
        if (maxAlpha == 0) return 0
        val threshold = alphaThreshold.coerceIn(1, 255).coerceAtLeast(64).coerceAtMost(maxAlpha)
        fun opaque(index: Int) = (raster.rgba[index * 4 + 3].toInt() and 255) >= threshold
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun visit(i: Int) {
            if (!outside[i] && !opaque(i)) { outside[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { visit(x); visit((h - 1) * w + x) }
        for (y in 0 until h) { visit(y * w); visit(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            if (i % w > 0) visit(i - 1)
            if (i % w < w - 1) visit(i + 1)
            if (i >= w) visit(i - w)
            if (i < w * (h - 1)) visit(i + w)
        }
        fun color(i: Int): Int {
            val b = raster.rgba
            return ((b[i * 4].toInt() and 255) shl 16) or
                ((b[i * 4 + 1].toInt() and 255) shl 8) or (b[i * 4 + 2].toInt() and 255)
        }
        val edge = (0 until w * h).filter { i ->
            opaque(i) && (i % w == 0 || i % w == w - 1 || i < w || i >= w * (h - 1) ||
                outside[i - 1] || outside[i + 1] || outside[i - w] || outside[i + w])
        }.map(::color)
        val candidates = edge.ifEmpty {
            (0 until w * h).filter { (raster.rgba[it * 4 + 3].toInt() and 255) > 0 }.map(::color)
        }
        if (candidates.isEmpty()) return 0
        val ordered = candidates.sortedBy { rgb ->
            2126 * (rgb shr 16 and 255) + 7152 * (rgb shr 8 and 255) + 722 * (rgb and 255)
        }
        // A dark percentile retains the painted hue without letting one nearly-black speck dominate.
        return ordered[(ordered.lastIndex * 0.2f).toInt()]
    }
}
