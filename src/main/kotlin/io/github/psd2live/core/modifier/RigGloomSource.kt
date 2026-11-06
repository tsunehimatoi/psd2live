package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.GloomInputs
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.i18n.tr

/** Capture adapter for a host with independently resolved upstream channel projections. */
internal object RigGloomSource {
    fun capture(
        instance: RigModifierInstance,
        hasFace: Boolean,
        layersByTarget: Map<String, ClassifiedLayer>,
        neutralChannels: Map<String, Mesh>,
        precedingCheekByTarget: Map<String, ModifierUpstreamCapability>,
        precedingGloomByTarget: Map<String, ModifierUpstreamCapability>,
    ): ModifierAlgorithmContext {
        val layers = instance.targets.associate { target ->
            val layer = requireNotNull(layersByTarget[target.id]) { "Missing captured gloom layer: ${target.id}" }
            val tag = layer.semantic.tag
            val role = layer.semantic.role
            val skin = tag == SemanticTag.FACE || tag == SemanticTag.NOSE || tag == SemanticTag.EARS ||
                tag == SemanticTag.FACE_DETAIL && role != PartRole.HIGHLIGHT
            val warmth = when {
                tag == SemanticTag.FACE_DETAIL && role == PartRole.BLUSH -> .3f
                tag == SemanticTag.FACE || tag == SemanticTag.NOSE -> .06f
                tag == SemanticTag.EARS -> .05f
                else -> 0f
            }
            fun strength(provider: ModifierUpstreamCapability?, type: String): Float? = provider?.let {
                require(provider.type == type && target in provider.targets)
                val providerId = requireNotNull(provider.instanceId)
                val preceding = RigModifierInstance(providerId, provider.type, provider.version, provider.targets, config = provider.config)
                RigLegacyBindings.values(preceding)["strength"] ?: 100f
            }
            target.id to GloomInputs.Layer(skin, role == PartRole.HIGHLIGHT, warmth,
                strength(precedingCheekByTarget[target.id], "expression.cheek"),
                strength(precedingGloomByTarget[target.id], "expression.gloom"))
        }
        val projection = layers.keys.associateWith { id ->
            val mesh = requireNotNull(neutralChannels[id]) { "Missing independent gloom channel projection: $id" }
            Mesh(id, id, null, geometry = null, offsets = null, channels = mesh.channels, drawOrder = mesh.drawOrder,
                opacity = mesh.opacity, multiply = mesh.multiply, screen = mesh.screen)
        }
        return GloomInputs(hasFace, tr("model.parameter.gloom"), tr("model.group.face"), layers, projection).context()
    }
}
