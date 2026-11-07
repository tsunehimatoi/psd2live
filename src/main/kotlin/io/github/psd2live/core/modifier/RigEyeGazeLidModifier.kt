package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeGazeLidAlgorithm
import io.github.psd2live.algorithms.binding.EyeGazeLidV3Algorithm
import io.github.psd2live.algorithms.binding.EyeGazeLidV4Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object RigEyeGazeLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeGazeLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeGazeLidAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeGazeLidAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeGazeLidV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeGazeLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeGazeLidAlgorithm.TYPE
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by EyeGazeLidV4Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - EyeGazeLidAlgorithm.TYPE
            override val canonicalOptInType = EyeGazeLidAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeGazeLidV4Algorithm.VERSION, plugin = projected,
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy", "relations"),
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

    private fun parameterNames() = RigModules.EYE_GAZE_LID.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeGazeLidAlgorithm.TYPE), source).copy(
            version = if (modern) EyeGazeLidV3Algorithm.VERSION else EyeGazeLidAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = EyeGazeLidAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { instance ->
                val capture = requireNotNull(source)
                val scaffold = capture.provider(instance)
                val carriers = mapOf(Side.LEFT to "L", Side.RIGHT to "R", Side.NONE to "Both").mapKeys { (side, _) ->
                    ScaffoldBinding.deformerId(scaffold, "", ScaffoldHead.gazeId(side).raw)
                }
                EyeGazeLidAlgorithm.context(captureLidSource(capture, instance, parameterNames(), fittedHead = modern), carriers,
                    tr("model.deformer.meshShape", "{meshName}"))
            },
        )
}
