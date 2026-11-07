package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeWideAlgorithm
import io.github.psd2live.algorithms.binding.EyeWideV3Algorithm
import io.github.psd2live.algorithms.binding.EyeWideV4Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigEyeWideModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeWideAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeWideAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeWideAlgorithm.TYPE
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val modern = object : ReadingModifierAlgorithmPlugin by EyeWideV3Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = EyeWideV3Algorithm.optionalUpstream
            override val canonicalOptInType = EyeWideAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeWideV3Algorithm.VERSION, plugin = modern,
            changeWrites = EyeWideV3Algorithm.WRITES,
            authorContext = { instance -> requireNotNull(source).capture(instance, fittedHead = true) })
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by EyeWideV4Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - EyeWideAlgorithm.TYPE
            override val canonicalOptInType = EyeWideAlgorithm.TYPE
        }
        return definition(source).copy(version = EyeWideV4Algorithm.VERSION, plugin = projected,
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).captureSharedFrames(it) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        require(scaffold.version == 2 && prefix.all { it.targets.containsAll(instance.targets) }) {
            "Eye widening v4 requires a shared fitted source"
        }
        val face = prefix.single { it.type == "face/fitted" && it.version == 3 }
        val brow = prefix.single { it.type == "brow/fitted" && it.version == 4 }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "eye/fitted" && it.version == 5 })
        val smile = prefix.single { it.type == "eye.smile" && it.version in setOf(1, 4) }
        require(prefix.none { it.type == EyeWideAlgorithm.TYPE })
        return captureBindingTransition(instance, scaffold, prefix, eyeBindingTags, values = buildJsonObject {
            put("face", face.id)
            put("brow", brow.id)
            put("smile", smile.id)
        })
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(EyeWideAlgorithm.TYPE), source).copy(
            version = EyeWideAlgorithm.VERSION,
            parameters = {
                StandardParameters.all.filter { it.id.raw in EyeWideAlgorithm.openings }.map {
                    Parameter(it.id.raw, it.name, it.min, 2f, it.default)
                }
            },
            plugin = algorithm,
            changeWrites = setOf("mesh", "parameter"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, fittedHead: Boolean = false): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = activeBefore(instance).single { it.id == baseId }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted", base.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base.copy(type = "eye/fitted"), RigTuning()),
            headTurnStrength = 1f, bodyStrength = 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val captured = RigBuilder.eyeWideSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        if (!fittedHead) return captured
        return captureLidHeadSource(instance, scaffold, base, native, captured)
    }
}

internal fun RigModifierSource.captureLidHeadSource(
    instance: RigModifierInstance, scaffold: RigModifierInstance, base: RigModifierInstance,
    native: PipelineConfig, captured: ModifierAlgorithmContext,
): ModifierAlgorithmContext {
    val preceding = activeBefore(instance).filter {
        it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
    }
    val head = preceding.lastOrNull { it.type in setOf("face/fitted", "face.depth-turn") } ?: return captured
    require(head.targets.containsAll(instance.targets)) { "Lid binding requires a shared fitted head" }
    val depth = RigModules.FACE_DEPTH_TURN
    val body = preceding.lastOrNull { it.type == "body/legacy" }
    val turning = native.copy(
        recipe = native.recipe.copy(modules = native.recipe.modules + (depth.id to ModuleChoice(true, depth.version, RigLegacyBindings.values(head)))),
        headTurnStrength = preceding.lastOrNull { it.type in setOf("face/fitted", "face/legacy") }
            ?.let(RigLegacyBindings::strength) ?: 1f,
        rigTuning = RigLegacyBindings.tuning(base,
            RigLegacyBindings.tuning(head, body?.let { RigLegacyBindings.tuning(it, RigTuning()) } ?: RigTuning())),
    )
    val face = RigBuilder.rigContext(analysis, turning, meshCache).faceRig
    return ModifierAlgorithmContext(JsonObject(captured.values + ("head_turn" to buildJsonObject {
        put("provider", head.id)
        put("shell", JsonArray(RigBuilder.headTurnMeasurements(face, turning).map(::JsonPrimitive)))
        put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
        put("shape_name", tr("model.deformer.meshShape", "{meshName}"))
        put("native_ids", JsonObject(instance.targets.associate { target ->
            target.id to JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(target.id)))
        }))
    })), captured.resources)
}
