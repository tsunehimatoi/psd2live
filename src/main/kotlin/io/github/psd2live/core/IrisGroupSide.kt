package io.github.psd2live.core

import org.umamo.format.art.SourceLayer
import java.text.Normalizer
import java.util.Collections
import java.util.WeakHashMap

/** A folder can name a single iris' side, but cannot decide how a bilateral drawing should be split. */
internal object IrisGroupSide {
    private val eyeNames = setOf("eye", "eyes", "iris", "irides", "pupil", "pupils", "瞳孔", "虹膜", "眼珠", "眼睛",
        "目", "眼", "瞳", "ひとみ", "眼球", "眼黑", "目玉", "め", "눈", "눈동자", "동공", "홍채", "눈알")
    private val bilateral = Regex("(?i)(?:\\bboth\\b|\\bbilateral\\b|\\bleft\\s*(?:and|&|/|\\+)\\s*right\\b|\\bl\\s*[&+/]\\s*r\\b|両目|両眼|双眼|雙眼|双目|雙目|左右|양쪽|양눈)")
    private data class Islands(val width: Int, val height: Int, val threshold: Int, val single: Boolean)
    private val islands = Collections.synchronizedMap(WeakHashMap<ByteArray, Islands>())

    fun inherit(layer: SourceLayer, semantic: LayerSemantic, alphaThreshold: Int, opaquePixels: Int): LayerSemantic {
        if (opaquePixels == 0 || semantic.tag != SemanticTag.IRIDES || semantic.side != Side.NONE) return semantic
        val groups = layer.groupPath.split('/').filter { it.isNotBlank() }
        if (groups.isEmpty() || (groups + layer.name).any { bilateral.containsMatchIn(Normalizer.normalize(it, Normalizer.Form.NFKC)) }) return semantic
        var inherited = Side.NONE
        for (name in groups.asReversed()) {
            val group = LayerClassifier.classify(name)
            if (group.tag != SemanticTag.IRIDES) continue
            // Only complete eye/iris names qualify; a prefix match such as "eye decoration L" is not evidence.
            if (group.side == Side.NONE) continue
            if (group.normalizedName !in eyeNames) return semantic
            if (inherited != Side.NONE && inherited != group.side) return semantic
            inherited = group.side
        }
        if (inherited == Side.NONE || !singleIsland(layer, alphaThreshold, opaquePixels)) return semantic
        return semantic.copy(side = inherited)
    }

    /** Eight-connected alpha, cached by immutable raster identity. Detached eyes are never assigned one folder side. */
    private fun singleIsland(layer: SourceLayer, threshold: Int, opaquePixels: Int): Boolean {
        val raster = layer.raster
        val rgba = raster.rgba
        islands[rgba]?.takeIf { it.width == raster.width && it.height == raster.height && it.threshold == threshold }
            ?.let { return it.single }
        val width = raster.width
        val height = raster.height
        val size = width * height
        fun opaque(index: Int) = (rgba[index * 4 + 3].toInt() and 0xff) >= threshold
        val first = (0 until size).firstOrNull(::opaque)
        var single = false
        if (first != null) {
            val seen = java.util.BitSet(size)
            val queue = IntArray(opaquePixels)
            var read = 0
            var write = 1
            queue[0] = first
            seen.set(first)
            while (read < write) {
                if (read % 4096 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
                val index = queue[read++]
                val x = index % width
                val y = index / width
                for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val next = ny * width + nx
                        if (!seen[next] && opaque(next)) {
                            seen.set(next)
                            queue[write++] = next
                        }
                    }
                }
            }
            single = (0 until size).none { !seen[it] && opaque(it) }
        }
        islands[rgba] = Islands(width, height, threshold, single)
        return single
    }
}
