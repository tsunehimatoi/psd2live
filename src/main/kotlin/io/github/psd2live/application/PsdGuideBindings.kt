package io.github.psd2live.application

import io.github.psd2live.core.*
import org.umamo.format.art.isEffectivelyVisible

internal object PsdGuideBindings {
    enum class Kind { SIDE, CONFLICT, DUPLICATE, UNKNOWN, MISSING }
    data class Finding(val kind: Kind, val layers: List<String>, val tag: SemanticTag)
    data class Result(
        val layers: List<ClassifiedLayer>,
        val findings: List<Finding>,
        val automatic: Map<String, LayerClassificationOverride>,
        val manual: Map<String, LayerClassificationOverride>,
    ) {
        val overrides get() = automatic + manual
    }

    private val eyeTags = setOf(SemanticTag.EYEWHITE, SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
    private val required = listOf(SemanticTag.FACE, SemanticTag.EYEWHITE, SemanticTag.MOUTH)
    private fun ClassifiedLayer.id() = source.id.raw
    fun classification(layer: ClassifiedLayer) = layer.semantic.run {
        LayerClassificationOverride(type, tag, side, parameter, switchId, role)
    }

    fun inspect(analysis: PipelineAnalysis, edits: Map<String, LayerClassificationOverride>): Result {
        val layers = analysis.layers.filter { it.opaquePixels > 0 && analysis.source.isEffectivelyVisible(it.source) }
        val manual = edits.filterKeys { id -> layers.any { it.id() == id } }
        val effective = layers.associate { it.id() to (manual[it.id()] ?: classification(it)) }
        val automatic = linkedMapOf<String, LayerClassificationOverride>()
        val findings = mutableListOf<Finding>()
        val face = layers.singleOrNull { effective.getValue(it.id()).tag == SemanticTag.FACE }
        val center = face?.bounds?.centerX
        fun side(layer: ClassifiedLayer) = when {
            center == null -> Side.NONE
            layer.bounds.right < center -> Side.RIGHT
            layer.bounds.left > center -> Side.LEFT
            else -> Side.NONE
        }
        fun nameSides(layer: ClassifiedLayer): Set<Side> =
            (layer.source.groupPath.split('/') + layer.source.name).map { LayerClassifier.classify(it).side }
                .filterTo(mutableSetOf()) { it != Side.NONE }
        fun overlapsY(a: Bounds, b: Bounds) = a.top < b.bottom && b.top < a.bottom
        fun overlaps(a: Bounds, b: Bounds) = overlapsY(a, b) && a.left < b.right && b.left < a.right
        fun canAssign(layer: ClassifiedLayer, assigned: Side): Boolean {
            val value = effective.getValue(layer.id())
            return layer.id() !in manual && value.side == Side.NONE && assigned != Side.NONE &&
                nameSides(layer).all { it == assigned }
        }

        val whites = layers.filter { effective.getValue(it.id()).tag == SemanticTag.EYEWHITE }
        val pair = whites.takeIf { it.size == 2 && overlapsY(it[0].bounds, it[1].bounds) }
            ?.associateBy(::side)?.takeIf { Side.LEFT in it && Side.RIGHT in it }
        if (pair != null && pair.all { (side, layer) ->
                val current = effective.getValue(layer.id()).side
                if (current == Side.NONE) canAssign(layer, side) else current == side &&
                    (layer.id() in manual || nameSides(layer).all { it == side })
            }) {
            pair.forEach { (side, layer) ->
                if (canAssign(layer, side)) automatic[layer.id()] = effective.getValue(layer.id()).copy(side = side)
            }
            layers.filter { effective.getValue(it.id()).tag in eyeTags && it !in whites }.forEach { layer ->
                val matches = pair.filter { (_, white) -> overlaps(layer.bounds, white.bounds) }
                val assigned = matches.keys.singleOrNull()
                if (assigned != null && assigned == side(layer) && canAssign(layer, assigned)) {
                    automatic[layer.id()] = effective.getValue(layer.id()).copy(side = assigned)
                }
            }
        }

        layers.forEach { layer ->
            val id = layer.id()
            val value = automatic[id] ?: effective.getValue(id)
            val tag = value.tag
            val sides = nameSides(layer)
            val spatial = side(layer)
            val kind = when {
                tag == SemanticTag.UNKNOWN && value.type == LayerType.PRESET && id !in manual -> Kind.UNKNOWN
                tag !in eyeTags || id in manual -> null
                sides.size > 1 || value.side != Side.NONE && spatial != Side.NONE && value.side != spatial ||
                    spatial != Side.NONE && sides.any { it != spatial } -> Kind.CONFLICT
                value.side == Side.NONE && (tag == SemanticTag.EYEWHITE || spatial != Side.NONE ||
                    pair?.values?.all { white -> overlaps(layer.bounds, white.bounds) } == true) -> Kind.SIDE
                else -> null
            }
            if (kind != null) findings += Finding(kind, listOf(id), tag)
        }
        layers.groupBy { it.semantic.normalizedName }.values.forEach { group ->
            group.filter { it.id() !in manual }.groupBy { automatic[it.id()] ?: effective.getValue(it.id()) }
                .values.filter { it.size > 1 }.forEach { duplicates ->
                    val tag = effective.getValue(duplicates.first().id()).tag
                    findings += Finding(Kind.DUPLICATE, duplicates.map { it.id() }, tag)
                }
        }
        required.forEach { tag ->
            if (effective.values.none { it.tag == tag || tag == SemanticTag.MOUTH &&
                        it.tag in setOf(SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE) }) {
                findings += Finding(Kind.MISSING, emptyList(), tag)
            }
        }
        return Result(layers, findings, automatic, manual)
    }
}
