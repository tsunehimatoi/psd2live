package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.CheekSource
import io.github.psd2live.algorithms.binding.CheekSource.LayerKind
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.i18n.tr

/** Adapts captured classification after the host resolves the actual upstream color providers. */
internal object RigCheekSource {
    data class Prior(
        val loweringInvariant: Boolean,
        val generatedMultiplyTrack: Boolean,
        val cheek: Float? = null,
        val gloom: Float? = null,
    )

    fun capture(hasFace: Boolean, layers: Map<String, ClassifiedLayer>, prior: Map<String, Prior>): CheekSource {
        require(layers.keys == prior.keys) { "Cheek requires an upstream color observation for every target" }
        require(prior.values.all { it.loweringInvariant }) { "Cheek requires an upstream pre-lowering binding candidate" }
        return CheekSource(hasFace, tr("model.parameter.cheek"), tr("model.group.face"), layers.mapValues { (id, layer) ->
            val kind = when (layer.semantic.tag) {
                SemanticTag.FACE -> LayerKind.FACE
                SemanticTag.NOSE -> LayerKind.NOSE
                SemanticTag.EARS -> LayerKind.EARS
                SemanticTag.FACE_DETAIL -> when (layer.semantic.role) {
                    PartRole.BLUSH -> LayerKind.BLUSH
                    PartRole.HIGHLIGHT -> LayerKind.OTHER
                    else -> LayerKind.FACE_DETAIL
                }
                else -> LayerKind.OTHER
            }
            val upstream = prior.getValue(id)
            CheekSource.Layer(kind, upstream.cheek, upstream.gloom, upstream.generatedMultiplyTrack)
        })
    }
}
