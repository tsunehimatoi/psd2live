package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeSquashAlgorithm
import io.github.psd2live.algorithms.binding.EyeSquashV3Algorithm
import io.github.psd2live.algorithms.binding.EyeSquashV4Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigEyeSquashModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeSquashAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye.wide", EyeSquashAlgorithm.TYPE, EyeSquashAlgorithm.ENTRYPOINT) + EyeSquashAlgorithm.HEAD_TURN
        override val canonicalOptInType = EyeSquashAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeSquashV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye.wide", EyeSquashAlgorithm.TYPE, EyeSquashAlgorithm.ENTRYPOINT, EyeSquashV3Algorithm.ENTRYPOINT) + EyeSquashAlgorithm.HEAD_TURN
        override val canonicalOptInType = EyeSquashAlgorithm.TYPE
    }

    private val highlightAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeSquashV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = modernAlgorithm.optionalUpstream + EyeSquashV4Algorithm.HIGHLIGHT
        override val canonicalOptInType = EyeSquashAlgorithm.TYPE
    }

    fun highlightDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, modern = true).copy(
            version = EyeSquashV4Algorithm.VERSION,
            plugin = highlightAlgorithm,
            authorContext = { requireNotNull(source).capture(it, modern = true, highlights = true) },
        )

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeSquashAlgorithm.TYPE), source).copy(
            version = if (modern) EyeSquashV3Algorithm.VERSION else EyeSquashAlgorithm.VERSION,
            parameters = { emptyList() },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) EyeSquashV3Algorithm.WRITES else setOf("mesh"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it, modern) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, modern: Boolean, highlights: Boolean = false): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = activeBefore(instance).single { it.id == baseId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(base.type, base.version, RigLegacyBindings.values(base))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val ordinary = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()))
        val native = if (highlights) ordinary.copy(
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        ) else ordinary
        val captured = RigBuilder.eyeSquashSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }) { target, parent ->
            io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(scaffold, target, parent)
        }
        if (!modern) return captured
        val context = captureLidHeadSource(instance, scaffold, base, native, captured)
        if (!highlights) return context
        val targets = instance.targets.toSet()
        val measurements = buildJsonObject {
            for (highlight in activeBefore(instance)) {
                if (highlight.type != EyeSquashV4Algorithm.HIGHLIGHT || highlight.config["scaffold"] != JsonPrimitive(scaffold.id) ||
                    highlight.config["base"] != JsonPrimitive(base.id) || highlight.targets.none(targets::contains)) continue
                for ((id, input) in RigEyeHighlightModifier.inputs(this@capture, highlight)) {
                    if (ModifierTarget("mesh", id) !in targets) continue
                    putJsonObject(id) {
                        put("provider", highlight.id)
                        put("irisWidth", input.irisWidth)
                    }
                }
            }
        }
        if (measurements.isEmpty()) return context
        return ModifierAlgorithmContext(JsonObject(context.values + mapOf(
            "highlights" to measurements,
            "names" to buildJsonObject {
                put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
                put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
            },
        )), context.resources)
    }
}
