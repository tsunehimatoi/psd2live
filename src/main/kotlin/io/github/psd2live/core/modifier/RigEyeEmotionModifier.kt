package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV3Algorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV4Algorithm
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object RigEyeEmotionModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeEmotionAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeEmotionAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeEmotionV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeEmotionV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by EyeEmotionV4Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - EyeEmotionAlgorithm.TYPE
            override val canonicalOptInType = EyeEmotionAlgorithm.TYPE
        }
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).captureSharedFrames(instance) }
        val controls = parameterNames().keys
        return definition(source).copy(version = EyeEmotionV4Algorithm.VERSION, plugin = projected,
            parameters = { instance ->
                RigIrBinary.decode(capture(instance).resources.getValue("after").read()).parameters.filter { it.id in controls }
            },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = ::capture)
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(it.type)?.let { spec -> !spec.expressionToggles && !spec.seeThrough } == true
        }
        val face = prefix.single { it.type == "face/fitted" && it.version == 3 }
        val brow = prefix.single { it.type == "brow/fitted" && it.version == 4 }
        val smile = prefix.single { it.type == "eye.smile" && it.version == 4 }
        require(prefix.all { it.targets.containsAll(instance.targets) }) { "Eye emotion v4 requires a shared source stack" }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "eye/fitted" && it.version == 5 })
        val (before, old) = RigFittedMouthModuleSource.context(this, scaffold, prefix, null)
        val (after, next) = RigFittedMouthModuleSource.context(this, scaffold, prefix + instance, null)
        val eyeTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
        fun eyes(context: RigBuilder.RigContext): Map<String, ClassifiedLayer> {
            val layers = context.analysis.layers.associateBy { it.source.id.raw }
            return instance.targets.mapNotNull { target ->
                val layer = layers[layersByMesh[target.id]] ?: return@mapNotNull null
                (target.id to layer).takeIf { layer.semantic.tag in eyeTags && layer.source.id.raw in atlas.placementByLayerId }
            }.toMap()
        }
        val prior = eyes(old)
        val desired = eyes(next)
        require(prior.isNotEmpty() && prior.keys == desired.keys)
        val axes = prefix.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(buildJsonObject {
            put("face", face.id)
            put("brow", brow.id)
            put("smile", smile.id)
            put("group", tr("model.group.eyes"))
        }, mapOf(
            "before" to outlineShellSource(old, before, prior, axes, includeFaceLean = true),
            "after" to outlineShellSource(next, after, desired, axes + RigLegacyBindings.spec(instance.type).parameters,
                includeFaceLean = true),
        ))
    }

    private fun parameterNames() = RigModules.EYE_EMOTION.defines.associate { it.id to tr(it.nameKey) }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) {
            val captured = captureLidSource(requireNotNull(source), instance, parameterNames(), fittedHead = modern)
            if (!modern) captured else ModifierAlgorithmContext(
                JsonObject(captured.values + ("shape_name" to JsonPrimitive(tr("model.deformer.meshShape", "{meshName}")))),
                captured.resources)
        }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(EyeEmotionAlgorithm.TYPE), source).copy(
            version = if (modern) EyeEmotionV3Algorithm.VERSION else EyeEmotionAlgorithm.VERSION,
            parameters = { if (modern) EyeEmotionV3Algorithm.parameters(capture(it)) else EyeEmotionAlgorithm.parameters(capture(it)) },
            resolveParameter = { _, _, _ -> null },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) EyeEmotionV3Algorithm.WRITES else EyeEmotionAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }
}
