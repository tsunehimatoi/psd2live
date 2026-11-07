package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeSmileAlgorithm
import io.github.psd2live.algorithms.binding.EyeSmileV3Algorithm
import io.github.psd2live.algorithms.binding.EyeSmileV4Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

internal object RigEyeSmileModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeSmileAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeSmileAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeSmileAlgorithm.TYPE
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val modern = object : ReadingModifierAlgorithmPlugin by EyeSmileV3Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = EyeSmileV3Algorithm.optionalUpstream
            override val canonicalOptInType = EyeSmileAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeSmileV3Algorithm.VERSION, plugin = modern,
            changeWrites = EyeSmileV3Algorithm.WRITES,
            authorContext = { instance -> captureLidSource(requireNotNull(source), instance, parameterNames(), fittedHead = true) })
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by EyeSmileV4Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - EyeSmileAlgorithm.TYPE
            override val canonicalOptInType = EyeSmileAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeSmileV4Algorithm.VERSION, plugin = projected,
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).captureSharedFrames(it) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        val face = prefix.single { it.type == "face/fitted" && it.version == 3 }
        val brow = prefix.single { it.type == "brow/fitted" && it.version == 4 }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "eye/fitted" && it.version == 5 })
        return captureBindingTransition(instance, scaffold, prefix, eyeBindingTags, values = buildJsonObject {
            put("face", face.id)
            put("brow", brow.id)
            put("group", tr("model.group.eyes"))
        })
    }

    private fun parameterNames() = RigModules.EYE_SMILE.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeSmileAlgorithm.TYPE), source).copy(
            version = EyeSmileAlgorithm.VERSION,
            parameters = { EyeSmileAlgorithm.parameters(parameterNames()) },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = EyeSmileAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { captureLidSource(requireNotNull(source), it, parameterNames()) },
        )
}

internal fun captureLidSource(
    source: RigModifierSource, instance: RigModifierInstance, parameterNames: Map<String, String>,
    fittedEyeId: String = instance.config.getValue("base").jsonPrimitive.content,
    fittedHead: Boolean = false,
): ModifierAlgorithmContext = with(source) {
    val scaffold = provider(instance)
    val base = activeBefore(instance).single { it.id == fittedEyeId && it.type == "eye/fitted" }
    val options = scaffold.config
    val corrections = WorkspaceSettingsCodec.decodeRecipe(options["corrections"])
    val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(base.type, base.version)),
        modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
        moves = corrections.moves, eyeParts = corrections.eyeParts)
    val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()),
        headTurnStrength = 1f, bodyStrength = 1f,
        featureDisplacementEnabled = options["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
        hairSimulationFront = !(options["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
        hairSimulationBack = !(options["backHairSway"]?.jsonPrimitive?.boolean ?: true))
    val captured = RigBuilder.eyeSmileSourceInputs(analysis, atlas, native, meshCache,
        instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, parameterNames) { mesh, parent ->
        ScaffoldBinding.deformerId(scaffold, mesh, parent)
    }
    if (fittedHead) captureLidHeadSource(instance, scaffold, base, native, captured) else captured
}
