package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import org.umamo.format.art.isEffectivelyVisible
import java.util.Locale

/** Select a complete hidden mouth construction when the visible drawing is only a closed seam. */
internal object WorkspaceMouthArtwork {
    fun prepare(document: WorkspaceDocument): WorkspaceDocument {
        val source = document.source
        val config = document.config()
        fun visible(layer: ClassifiedLayer) = layer.source.opacity > 0f &&
            (document.layerVisibility[layer.source.id.raw] ?: source.isEffectivelyVisible(layer.source))
        val layers = PartRoles.infer(source.layers.map { CharacterAnalyzer.classify(it, config) }, document.layerOverrides)
        val lines = layers.filter { it.semantic.role in setOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE) }
        val visibleLines = lines.filter(::visible)
        if (visibleLines.isNotEmpty()) {
            val seam = visibleLines.map { it.bounds }.reduce(Bounds::union)
            if (seam.height <= seam.width * .2f) {
                data class Construction(val layers: List<ClassifiedLayer>, val aperture: ClassifiedLayer,
                                        val upper: ClassifiedLayer, val lower: ClassifiedLayer)
                val candidates = layers.filter { it.opaquePixels > 0 && it.semantic.type == LayerType.PRESET &&
                    it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS }.groupBy { it.source.groupPath }.values.mapNotNull { group ->
                    if (group.any(::visible)) return@mapNotNull null
                    val aperture = group.filter { it.source.visible && it.source.opacity > 0f && !it.source.clipped &&
                        it.semantic.tag == SemanticTag.MOUTH &&
                        it.bounds.left < seam.centerX && it.bounds.right > seam.centerX &&
                        it.bounds.top < seam.centerY && it.bounds.bottom > seam.centerY }
                        .maxByOrNull { it.opaquePixels } ?: return@mapNotNull null
                    val outlines = group.filter { it.source.visible && it.source.opacity > 0f && !it.source.clipped && it !== aperture &&
                        it.bounds.width >= aperture.bounds.width * .7f && it.bounds.width <= aperture.bounds.width * 1.3f &&
                        it.semantic.tag == SemanticTag.MOUTH && it.opaquePixels < aperture.opaquePixels * .35f }
                    val upper = outlines.filter { it.centroidY < aperture.centroidY }.singleOrNull() ?: return@mapNotNull null
                    val lower = outlines.filter { it.centroidY > aperture.centroidY }.singleOrNull() ?: return@mapNotNull null
                    Construction(group, aperture, upper, lower)
                }
                // Alternate complete constructions require an explicit choice.
                candidates.singleOrNull()?.let { (selected, aperture, upper, lower) ->
                    val closedLines = lines.filter { it !in selected &&
                        it.bounds.left < aperture.bounds.right && it.bounds.right > aperture.bounds.left &&
                        it.bounds.top < aperture.bounds.bottom && it.bounds.bottom > aperture.bounds.top }
                    val overrides = document.layerOverrides.toMutableMap()
                    fun role(layer: ClassifiedLayer, value: PartRole) {
                        overrides.putIfAbsent(layer.source.id.raw, LayerClassificationOverride(tag = SemanticTag.MOUTH, role = value))
                    }
                    role(upper, PartRole.UPPER_LINE)
                    role(lower, PartRole.LOWER_LINE)
                    for (line in closedLines) overrides.putIfAbsent(line.source.id.raw,
                        LayerClassificationOverride(tag = SemanticTag.MOUTH, role = PartRole.CLOSED_LINE))
                    val skin = layers.filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null &&
                        it.bounds.left < aperture.bounds.right && it.bounds.right > aperture.bounds.left &&
                        it.source.groupPath.split('/').lastOrNull()?.lowercase(Locale.ROOT) in setOf("\u5634\u76ae", "lip skin", "\u5507\u76ae") }
                    for (layer in skin) role(layer, if (layer.centroidY < seam.centerY) PartRole.UPPER_LIP else PartRole.LOWER_LIP)
                    val visibility = document.layerVisibility.toMutableMap()
                    for (line in closedLines) visibility.putIfAbsent(line.source.id.raw, false)
                    for (layer in selected.filter { it.source.visible && it.source.opacity > 0f }) visibility.putIfAbsent(layer.source.id.raw, true)
                    return document.copy(layerOverrides = overrides, layerVisibility = visibility)
                }
            }
        }
        // Multiple active full-mouth variants at the same mouth position cannot all render at once.
        val activeBases = layers.filter { it.opaquePixels > 0 && it.semantic.type == LayerType.PRESET &&
            it.semantic.role == null && !it.source.clipped && visible(it) &&
            it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
        val duplicateIds = linkedSetOf<String>()
        for ((_, group) in activeBases.groupBy { it.semantic.tag to it.semantic.side }) {
            if (group.size < 2) continue
            val sorted = group.sortedByDescending { it.opaquePixels }
            val kept = mutableListOf<ClassifiedLayer>()
            for (candidate in sorted) {
                val overlapsKept = kept.any { other ->
                    minOf(candidate.bounds.right, other.bounds.right) > maxOf(candidate.bounds.left, other.bounds.left)
                }
                if (overlapsKept) duplicateIds += candidate.source.id.raw else kept += candidate
            }
        }
        if (duplicateIds.isEmpty()) return document
        val visibility = document.layerVisibility.toMutableMap()
        for (id in duplicateIds) visibility.putIfAbsent(id, false)
        return document.copy(layerVisibility = visibility)
    }
}
