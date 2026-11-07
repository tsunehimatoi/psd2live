package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.CheekV2Algorithm
import io.github.psd2live.algorithms.binding.CheekSource
import io.github.psd2live.algorithms.binding.CheekSource.LayerKind
import io.github.psd2live.algorithms.binding.MoodChannels
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonPrimitive

internal object RigCheekModifier {
    private val algorithm = object : ChangingModifierAlgorithmPlugin by CheekV2Algorithm(), ModifierVersionSelection {
        override val canonicalOptInType = CheekV2Algorithm.TYPE
    }

    private fun parameter() = CheekSource(true, tr("model.parameter.cheek"), tr("model.group.face"), emptyMap()).parameter()

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(CheekV2Algorithm.TYPE), source).copy(
            version = CheekV2Algorithm.VERSION,
            parameters = { if (requireNotNull(source).applicable()) listOf(parameter()) else emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            upstreamInputs = true,
            changeWrites = setOf("mesh", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.applicable() = RigModules.EXPRESSION_CHEEK.applies(RecipeSubject.of(analysis))

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Cheek v2 requires a shared v2 scaffold" }
        require(instance.outputAliases.keys.none { it.startsWith("parameter-folder:") }) {
            "Use cheek v1 for legacy parameter folder order"
        }
        require(expressionIds(scaffold).none { it in MoodChannels.parameters }) {
            "Use cheek v1 when source expressions use the standard mood parameters"
        }
        val preceding = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
        }
        require(preceding.none { it.type == CheekV2Algorithm.TYPE || it.type == CheekV2Algorithm.ENTRYPOINT || it.type == "expression.gloom" }) {
            "Use cheek v1 after another mood binding"
        }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.associate { target ->
            val layer = layers.getValue(layersByMesh.getValue(target.id))
            val kind = when (layer.semantic.tag) {
                SemanticTag.FACE -> LayerKind.FACE
                SemanticTag.NOSE -> LayerKind.NOSE
                SemanticTag.EARS -> LayerKind.EARS
                SemanticTag.FACE_DETAIL -> if (layer.semantic.role == PartRole.BLUSH) LayerKind.BLUSH else LayerKind.OTHER
                else -> LayerKind.OTHER
            }
            target.id to CheekSource.Layer(kind)
        }
        return CheekSource(applicable(), tr("model.parameter.cheek"), tr("model.group.face"), targets).context()
    }
}
