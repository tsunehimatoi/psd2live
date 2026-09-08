package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.art.*
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Regenerated assets have stable independent IDs, so selection, visibility and mesh edits persist. */
internal class MouthLipLayer(
    val ownerId: String,
    val side: Int,
    private val owner: SourceLayer,
    override val raster: LayerRaster,
) : SourceLayer by owner {
    override val id = LayerId(idFor(ownerId, side))
    override val name = owner.name + " / " + tr(if (side == 0) "mouth.upperLip" else "mouth.lowerLip")
    override val bounds = LayerBounds(owner.bounds.left, owner.bounds.top, raster.width, raster.height)
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
        val layers = originals.flatMap { owner ->
            if (owner.semantic.tag !in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) || owner.opaquePixels == 0) listOf(owner)
            else {
                val rgb = config.mouthColor ?: perimeterColor(owner.source.raster, config.alphaThreshold)
                val lips = (0..1).mapNotNull { side ->
                    val source = MouthLipLayer(owner.source.id.raw, side, owner.source, strokeRaster(rgb))
                    if (source.id.raw in config.deletedLayerIds) null else owner.copy(source = source)
                }
                lips + owner
            }
        }
        return input.copy(layers = layers)
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

    /** Straight body plus circular end patches; each lip owns a distinct packed texture region. */
    private fun strokeRaster(rgb: Int): LayerRaster {
        val image = BufferedImage(128, 56, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color(rgb)
            fillRoundRect(2, 8, 124, 16, 16, 16)
            fillOval(2, 34, 16, 16)
            dispose()
        }
        val rgba = ByteArray(128 * 56 * 4)
        for (y in 0 until 56) for (x in 0 until 128) {
            val argb = image.getRGB(x, y)
            val i = (y * 128 + x) * 4
            rgba[i] = (argb shr 16).toByte()
            rgba[i + 1] = (argb shr 8).toByte()
            rgba[i + 2] = argb.toByte()
            rgba[i + 3] = (argb ushr 24).toByte()
        }
        return LayerRaster(128, 56, rgba)
    }
}
