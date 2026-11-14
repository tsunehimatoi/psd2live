package io.github.psd2live.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photoshop's Free Transform on a raster: the pixels of [PaintPixelTransform.apply]'s region - weighted by a
 * selection when there is one - are lifted off, put through an affine map and laid back over what is left.
 *
 * A map is six numbers `a b c d e f` taking a raster point (x, y) to (a x + c y + e, b x + d y + f), the layout of
 * [io.github.psd2live.document.LayerTransform].
 */
internal object PaintPixelTransform {
    /** [m] taking [x], [y] to raster x. */
    fun x(m: FloatArray, x: Float, y: Float) = m[0] * x + m[2] * y + m[4]
    fun y(m: FloatArray, x: Float, y: Float) = m[1] * x + m[3] * y + m[5]

    /** The inverse of [m], or null when it squashes the plane flat. */
    fun inverse(m: FloatArray): FloatArray? {
        val det = m[0] * m[3] - m[1] * m[2]
        if (!det.isFinite() || kotlin.math.abs(det) < 1e-9f) return null
        val a = m[3] / det; val b = -m[1] / det; val c = -m[2] / det; val d = m[0] / det
        return floatArrayOf(a, b, c, d, -(a * m[4] + c * m[5]), -(b * m[4] + d * m[5]))
    }

    /** The raster pixels [region] covers once mapped by [m], clamped to [width] x [height]; empty off the raster. */
    fun mappedBounds(m: FloatArray, region: Rectangle, width: Int, height: Int): Rectangle {
        val xs = FloatArray(4); val ys = FloatArray(4)
        val corners = listOf(region.x to region.y, region.x + region.width to region.y,
            region.x to region.y + region.height, region.x + region.width to region.y + region.height)
        corners.forEachIndexed { i, (cx, cy) -> xs[i] = x(m, cx.toFloat(), cy.toFloat()); ys[i] = y(m, cx.toFloat(), cy.toFloat()) }
        val left = floor(xs.min()).toInt(); val top = floor(ys.min()).toInt()
        val right = ceil(xs.max()).toInt(); val bottom = ceil(ys.max()).toInt()
        return Rectangle(left, top, right - left, bottom - top).intersection(Rectangle(0, 0, width, height))
    }

    /**
     * The pixels of [region] in [image] that [selection] takes, as straight ARGB of the region's size: what a
     * transform lifts. Pixels the selection takes only partly are lifted in that share of their alpha.
     */
    fun lifted(image: BufferedImage, region: Rectangle, selection: PaintSelection?): IntArray {
        val pixels = image.getRGB(region.x, region.y, region.width, region.height, null, 0, region.width)
        if (selection == null) return pixels
        for (row in 0 until region.height) for (column in 0 until region.width) {
            val at = row * region.width + column
            val weight = selection.at(region.x + column, region.y + row)
            val c = pixels[at]
            pixels[at] = if (weight >= 255) c else if (weight <= 0) 0
                else ((((c ushr 24) * weight + 127) / 255) shl 24) or (c and 0xFFFFFF)
        }
        return pixels
    }

    /**
     * Takes the lifted pixels out of [image]: each pixel of [region] keeps the share of its alpha [selection] leaves
     * behind - none of it without a selection.
     */
    fun clear(image: BufferedImage, region: Rectangle, selection: PaintSelection?) {
        if (selection == null) {
            image.setRGB(region.x, region.y, region.width, region.height, IntArray(region.width * region.height), 0, region.width)
            return
        }
        val pixels = image.getRGB(region.x, region.y, region.width, region.height, null, 0, region.width)
        for (row in 0 until region.height) for (column in 0 until region.width) {
            val at = row * region.width + column
            pixels[at] = mixArgb(pixels[at], 0, selection.at(region.x + column, region.y + row))
        }
        image.setRGB(region.x, region.y, region.width, region.height, pixels, 0, region.width)
    }

    /**
     * Lays [floating] - straight ARGB covering [region] - over [image] through [m], sampled bilinearly ([smooth]) or
     * by nearest pixel, and composited source-over. Returns the raster pixels it reached, or null for a map that
     * squashes the region flat.
     */
    fun place(image: BufferedImage, floating: IntArray, region: Rectangle, m: FloatArray, smooth: Boolean,
              checkpoint: () -> Unit = {}): Rectangle? {
        val inverse = inverse(m) ?: return null
        val target = mappedBounds(m, region, image.width, image.height)
        if (target.isEmpty) return target
        val w = region.width; val h = region.height
        // Premultiplied channels, so a transparent neighbour does not darken an edge it is blended with.
        val pa = FloatArray(w * h); val pr = FloatArray(w * h); val pg = FloatArray(w * h); val pb = FloatArray(w * h)
        for (i in floating.indices) {
            val c = floating[i]; val a = (c ushr 24) / 255f
            pa[i] = a; pr[i] = ((c ushr 16) and 0xFF) * a; pg[i] = ((c ushr 8) and 0xFF) * a; pb[i] = (c and 0xFF) * a
        }
        val out = image.getRGB(target.x, target.y, target.width, target.height, null, 0, target.width)
        val sample = FloatArray(4)
        fun sampleAt(sx: Float, sy: Float): Boolean {
            // Region-relative pixel centres.
            val u = sx - region.x - 0.5f; val v = sy - region.y - 0.5f
            if (u < -1f || v < -1f || u > w || v > h) return false
            if (!smooth) {
                val ix = (u + 0.5f).toInt(); val iy = (v + 0.5f).toInt()
                if (u + 0.5f < 0f || v + 0.5f < 0f || ix >= w || iy >= h) return false
                val at = iy * w + ix
                sample[0] = pa[at]; sample[1] = pr[at]; sample[2] = pg[at]; sample[3] = pb[at]
                return sample[0] > 0f
            }
            val x0 = floor(u).toInt(); val y0 = floor(v).toInt()
            val fx = u - x0; val fy = v - y0
            sample.fill(0f)
            for (dy in 0..1) for (dx in 0..1) {
                val px = x0 + dx; val py = y0 + dy
                if (px !in 0 until w || py !in 0 until h) continue
                val k = (if (dx == 0) 1f - fx else fx) * (if (dy == 0) 1f - fy else fy)
                if (k <= 0f) continue
                val at = py * w + px
                sample[0] += pa[at] * k; sample[1] += pr[at] * k; sample[2] += pg[at] * k; sample[3] += pb[at] * k
            }
            return sample[0] > 0f
        }
        for (row in 0 until target.height) {
            if (row % 64 == 0) checkpoint()
            val ty = target.y + row + 0.5f
            for (column in 0 until target.width) {
                val tx = target.x + column + 0.5f
                if (!sampleAt(x(inverse, tx, ty), y(inverse, tx, ty))) continue
                val at = row * target.width + column
                val d = out[at]; val da = (d ushr 24) / 255f
                val sa = min(1f, sample[0])
                val keep = da * (1f - sa)
                val a = sa + keep
                if (a <= 0f) continue
                fun channel(source: Float, shift: Int) =
                    ((source + ((d ushr shift) and 0xFF) * keep) / a).roundToInt().coerceIn(0, 255)
                out[at] = ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or (channel(sample[1], 16) shl 16) or
                    (channel(sample[2], 8) shl 8) or channel(sample[3], 0)
            }
        }
        image.setRGB(target.x, target.y, target.width, target.height, out, 0, target.width)
        return target
    }

    /**
     * [selection] put through [m] the way its pixels are: where the selection follows a transform to, as Photoshop's
     * does. Null when nothing of it lands on the raster.
     */
    fun mapSelection(selection: PaintSelection, m: FloatArray): PaintSelection? {
        val region = selection.bounds ?: return null
        val floating = IntArray(region.width * region.height) { i ->
            selection.at(region.x + i % region.width, region.y + i / region.width) shl 24 or 0xFFFFFF
        }
        val canvas = BufferedImage(selection.width, selection.height, BufferedImage.TYPE_INT_ARGB)
        place(canvas, floating, region, m, smooth = true) ?: return null
        val pixels = canvas.getRGB(0, 0, canvas.width, canvas.height, null, 0, canvas.width)
        return PaintSelection(selection.width, selection.height, ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() })
            .takeUnless { it.isEmpty }
    }

    /** The smallest rectangle of [region] holding a pixel that is not fully transparent; null when all of it is. */
    fun contentBounds(image: BufferedImage, region: Rectangle): Rectangle? {
        val area = region.intersection(Rectangle(0, 0, image.width, image.height))
        if (area.isEmpty) return null
        val pixels = image.getRGB(area.x, area.y, area.width, area.height, null, 0, area.width)
        var left = area.width; var top = area.height; var right = -1; var bottom = -1
        for (row in 0 until area.height) for (column in 0 until area.width) if (pixels[row * area.width + column] ushr 24 != 0) {
            left = min(left, column); right = max(right, column); top = min(top, row); bottom = max(bottom, row)
        }
        return if (right < 0) null else Rectangle(area.x + left, area.y + top, right - left + 1, bottom - top + 1)
    }
}
