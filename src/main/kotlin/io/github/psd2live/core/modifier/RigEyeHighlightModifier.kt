package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm
import io.github.psd2live.algorithms.binding.EyeHighlightV3Algorithm
import io.github.psd2live.algorithms.binding.EyeHighlightInput
import io.github.psd2live.algorithms.binding.EyeModules
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

internal object RigEyeHighlightModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeHighlightAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeHighlightAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeHighlightAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeHighlightV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeHighlightV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeHighlightAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeHighlightAlgorithm.TYPE), source).copy(
            version = if (modern) EyeHighlightV3Algorithm.VERSION else EyeHighlightAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Eye highlight requires resolved modifier inputs") },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) EyeHighlightV3Algorithm.WRITES else setOf("mesh"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it, modern) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, modern: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = activeBefore(instance).single { it.id == baseId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true,
            bases = mapOf("eye" to BaseChoice(base.type, base.version, RigLegacyBindings.values(base))),
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val context = RigBuilder.rigContext(analysis, native, meshCache, neutralScaffold = true)
        val eyes = context.fittedEyes(native)?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.mapNotNull { target ->
            val layer = layers[layersByMesh.getValue(target.id)] ?: return@mapNotNull null
            val semantic = layer.semantic
            if (semantic.tag != SemanticTag.IRIDES || semantic.role != PartRole.HIGHLIGHT || layer.opaquePixels == 0)
                return@mapNotNull null
            val eye = eyes[semantic.side] ?: return@mapNotNull null
            val aligned = context.rigLayer(layer)
            val (parent, frame) = context.parentAndFrame(layer, native)
            val override = native.layerOverrides[layer.source.id.raw]
            val automaticOpacity = (override?.type ?: semantic.type) == LayerType.PRESET ||
                (override?.parameter ?: semantic.parameter).isBlank()
            target.id to EyeHighlightInput(EyeModules.open(semantic.side).raw, eye.crease,
                (eye.fit.iris ?: aligned.bounds).width, frame.width, frame.height,
                parent?.raw?.let { ScaffoldBinding.deformerId(scaffold, target.id, it) },
                layer.source.opacity.coerceIn(0f, 1f), automaticOpacity)
        }.toMap()
        val captured = EyeHighlightInput.encode(targets)
        if (!modern || targets.isEmpty()) return captured
        val iris = RigBuilder.eyeSquashSourceInputs(analysis, atlas, native, meshCache,
            targets.keys.associateWith(layersByMesh::getValue)) { target, parent ->
            ScaffoldBinding.deformerId(scaffold, target, parent)
        }
        val source = ModifierAlgorithmContext(JsonObject(captured.values + mapOf(
            "iris" to iris.values,
            "turn_name" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")),
        )), iris.resources)
        return captureLidHeadSource(instance, scaffold, base, native, source)
    }
}
