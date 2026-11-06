package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceInputs
import io.github.psd2live.core.modifier.ModifierSourceTransform
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.*

/** Captured contour to RGBA artwork; no product layer, project or atlas access. */
object MouthOutlineRaster {
    data class Result(val left: Int, val top: Int, val width: Int, val height: Int,
                      val rgba: ByteArray, val opaquePixels: Int)

    /** The raster contour comes from source tracing, separately from the posed ribbon mesh. */
    fun fromSource(
        source: ModifierSourceInputs.Raster,
        tracedPositions: FloatArray,
        tracedTriangles: IntArray,
        alignedToCanvas: ModifierSourceTransform,
        alphaThreshold: Int,
        thickness: Float,
        color: Int? = null,
        checkpoint: () -> Unit = {},
    ): List<Result> {
        checkpoint()
        val columns = MouthContour.uniformColumns(tracedPositions, tracedTriangles)
        if (columns.size < 2) return emptyList()
        val rgb = color ?: perimeterColor(source.width, source.height, source.rgba(), alphaThreshold, checkpoint)
        return (0..1).map { side -> render(columns, side, rgb, thickness, alignedToCanvas::map, checkpoint) }
    }

    fun render(columns: List<MouthColumn>, side: Int, rgb: Int, mouthThickness: Float,
               toCanvas: (Float, Float) -> Pair<Float, Float>, checkpoint: () -> Unit = {}): Result {
        checkpoint()
        val stroke = MouthOutlineStroke(columns, side, mouthThickness)
        val radius = stroke.radius
        val strokePositions = stroke.positions()
        val indices = stroke.indices()

        val canvasPositions = FloatArray(strokePositions.size)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in strokePositions.indices step 2) {
            checkpoint()
            val canvas = toCanvas(strokePositions[i], strokePositions[i + 1])
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
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.color = Color(rgb)

            val triPath = Path2D.Float()
            for (t in indices.indices step 3) {
                checkpoint()
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
                checkpoint()
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
        } finally { g2.dispose() }

        val rgba = ByteArray(strokeWidth * strokeHeight * 4)
        var opaqueCount = 0
        for (y in 0 until strokeHeight) {
            checkpoint()
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

        return Result(strokeLeft, strokeTop, strokeWidth, strokeHeight, rgba, max(1, opaqueCount))
    }

    /** Sample only the outside boundary, excluding interior details and transparent RGB noise. */
    fun perimeterColor(width: Int, height: Int, rgba: ByteArray, alphaThreshold: Int, checkpoint: () -> Unit = {}): Int {
        checkpoint()
        require(width >= 0 && height >= 0 && width.toLong() * height * 4 == rgba.size.toLong()) { "Invalid RGBA raster" }
        val w = width
        val h = height
        val maxAlpha = (0 until w * h).maxOfOrNull { rgba[it * 4 + 3].toInt() and 255 } ?: 0
        if (maxAlpha == 0) return 0
        val threshold = alphaThreshold.coerceIn(1, 255).coerceAtLeast(64).coerceAtMost(maxAlpha)
        fun opaque(index: Int) = (rgba[index * 4 + 3].toInt() and 255) >= threshold
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
            if (head % 4096 == 0) checkpoint()
            val i = queue[head++]
            if (i % w > 0) visit(i - 1)
            if (i % w < w - 1) visit(i + 1)
            if (i >= w) visit(i - w)
            if (i < w * (h - 1)) visit(i + w)
        }
        fun color(i: Int): Int {
            val b = rgba
            return ((b[i * 4].toInt() and 255) shl 16) or
                ((b[i * 4 + 1].toInt() and 255) shl 8) or (b[i * 4 + 2].toInt() and 255)
        }
        val edge = (0 until w * h).filter { i ->
            opaque(i) && (i % w == 0 || i % w == w - 1 || i < w || i >= w * (h - 1) ||
                outside[i - 1] || outside[i + 1] || outside[i - w] || outside[i + w])
        }.map(::color)
        val candidates = edge.ifEmpty {
            (0 until w * h).filter { (rgba[it * 4 + 3].toInt() and 255) > 0 }.map(::color)
        }
        if (candidates.isEmpty()) return 0
        val ordered = candidates.sortedBy { rgb ->
            2126 * (rgb shr 16 and 255) + 7152 * (rgb shr 8 and 255) + 722 * (rgb and 255)
        }
        // A dark percentile retains the painted hue without letting one nearly-black speck dominate.
        return ordered[(ordered.lastIndex * 0.2f).toInt()]
    }
}
