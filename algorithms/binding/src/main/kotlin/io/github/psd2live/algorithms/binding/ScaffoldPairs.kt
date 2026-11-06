package io.github.psd2live.algorithms.binding

import java.text.Normalizer
import java.util.Locale
import org.umamo.runtime.model.*

/** Builds static common parents for sided source layers in captured source order. */
object ScaffoldPairs {
    data class Layer(
        val id: String,
        val name: String,
        val semanticTag: String,
        val side: Side,
        val bounds: Bounds,
        val defaultParent: DeformerId,
        val defaultParentFrame: Bounds,
        val part: PartId,
    )

    data class PairFrame(val warp: Deformer.Warp, val bounds: Bounds, val layerIds: List<String>)

    fun build(
        layers: List<Layer>,
        reservedIds: Set<String>,
        nameForPair: (String) -> String,
        checkpoint: () -> Unit = {},
    ): List<PairFrame> {
        val usedIds = reservedIds.toMutableSet()
        val groups = layers.filter { it.side != Side.NONE }.groupBy { layer ->
            checkpoint()
            layer.defaultParent to baseName(layer.name).lowercase(Locale.ROOT).trim()
        }
        return buildList {
            for (group in groups.values) {
                checkpoint()
                if (group.none { it.side == Side.LEFT } || group.none { it.side == Side.RIGHT }) continue
                val first = group.first()
                val cleanName = baseName(first.name)
                val id = pairId(cleanName, first.semanticTag, usedIds)
                val union = group.map { it.bounds }.reduce(Bounds::union)
                val padX = maxOf(union.width * 0.04f, 4f)
                val padY = maxOf(union.height * 0.04f, 4f)
                val frame = Bounds(union.left - padX, union.top - padY, union.right + padX, union.bottom + padY)
                val warp = identityWarp(id, nameForPair(cleanName), first.defaultParent, frame, first.defaultParentFrame, first.part)
                add(PairFrame(warp, frame, group.map { it.id }))
            }
        }
    }

    fun identityWarp(
        id: DeformerId,
        name: String,
        parent: DeformerId,
        frame: Bounds,
        parentFrame: Bounds,
        partId: PartId,
        rows: Int = 3,
        columns: Int = 3,
    ): Deformer.Warp {
        val width = parentFrame.width.coerceAtLeast(1e-4f)
        val height = parentFrame.height.coerceAtLeast(1e-4f)
        val local = Bounds(
            (frame.left - parentFrame.left) / width, (frame.top - parentFrame.top) / height,
            (frame.right - parentFrame.left) / width, (frame.bottom - parentFrame.top) / height,
        )
        val points = FloatArray((rows + 1) * (columns + 1) * 2)
        var index = 0
        for (row in 0..rows) for (column in 0..columns) {
            points[index++] = local.left + (column.toFloat() / columns) * local.width
            points[index++] = local.top + (row.toFloat() / rows) * local.height
        }
        val geometry = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(points))))
        return Deformer.Warp(id, name, parent, partId, rows, columns, true, geometry)
    }

    private val sideSuffix = Regex("(?:[-_.\\s]+)(l|r|left|right|左|右)$", RegexOption.IGNORE_CASE)
    private val sidePrefix = Regex("^(左|右)(?:[-_.\\s]+)?", RegexOption.IGNORE_CASE)

    private fun baseName(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).trim()
        val side = sideSuffix.find(normalized) ?: sidePrefix.find(normalized)
        val clean = side?.let { normalized.removeRange(it.range).trim() } ?: normalized
        return clean.ifBlank { name.trim() }
    }

    private fun pairId(name: String, semanticTag: String, usedIds: MutableSet<String>): DeformerId {
        val words = name.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        val pascal = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val suffix = when {
            pascal.isNotBlank() -> pascal
            semanticTag != "UNKNOWN" -> semanticTag.lowercase(Locale.ROOT).split('_')
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            else -> "Part"
        }
        val base = "DeformPair_$suffix"
        var id = base
        var ordinal = 2
        while (!usedIds.add(id)) id = "${base}_${ordinal++}"
        return DeformerId(id)
    }
}
