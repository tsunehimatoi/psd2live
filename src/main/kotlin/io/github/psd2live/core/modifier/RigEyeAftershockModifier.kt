package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV3Algorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV4Algorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV5Algorithm
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigEyeAftershockModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockAlgorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    private fun parameterNames() = RigModules.EYE_AFTERSHOCK.defines.associate { it.id to tr(it.nameKey) }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockV3Algorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, EyeAftershockAlgorithm.VERSION, algorithm)

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, EyeAftershockV3Algorithm.VERSION, modernAlgorithm)

    private val fittedHeadAlgorithm = object : ReadingModifierAlgorithmPlugin by EyeAftershockV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = EyeAftershockV4Algorithm.optionalUpstream
        override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
    }

    fun fittedHeadDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, EyeAftershockV4Algorithm.VERSION, fittedHeadAlgorithm)

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by EyeAftershockV5Algorithm(),
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - EyeAftershockAlgorithm.TYPE
            override val canonicalOptInType = EyeAftershockAlgorithm.TYPE
        }
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).captureSharedFrames(instance) }
        val parameterIds = RigModules.EYE_AFTERSHOCK.defines.mapTo(hashSetOf()) { it.id }
        return fittedHeadDefinition(source).copy(version = EyeAftershockV5Algorithm.VERSION, plugin = projected,
            parameters = { instance -> RigIrBinary.decode(capture(instance).resources.getValue("after").read())
                .parameters.filter { it.id in parameterIds } },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy", "physics"),
            authorContext = ::capture)
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
            put("group", tr("model.group.physics"))
            val controls = setOf("ParamEyeLOpen", "ParamEyeROpen", "ParamEyeLBounce", "ParamEyeRBounce")
            put("physics", JsonArray(PhysicsGenerator.moduleRules(controls).map { it.toJson() }))
        })
    }

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(EyeAftershockAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(
            version = version,
            composableKinds = legacy.composableKinds + "physics",
            parameters = { instance ->
                val context = capture(instance)
                when (version) {
                    EyeAftershockV3Algorithm.VERSION -> EyeAftershockV3Algorithm.parameters(context)
                    EyeAftershockV4Algorithm.VERSION -> EyeAftershockV4Algorithm.parameters(context)
                    else -> EyeAftershockAlgorithm.parameters(context)
                }
            },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = EyeAftershockAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = ::capture,
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val inputs = captureLidSource(this, instance, parameterNames(), fittedHead = version == EyeAftershockV4Algorithm.VERSION)
        val head = inputs.values["head_turn"]
        val lids = if (head == null) inputs else ModifierAlgorithmContext(JsonObject(inputs.values - "head_turn"), inputs.resources)
        val context = EyeAftershockAlgorithm.context(lids, tr("model.group.physics"),
            listOf(tr("model.physics.eyeBounce", "L"), tr("model.physics.eyeBounce", "R")))
        if (version == EyeAftershockAlgorithm.VERSION) return context
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val carriers = buildJsonObject {
            for (target in instance.targets) {
                val layer = layersByMesh.getValue(target.id)
                putJsonObject(target.id) {
                    put("id", primaryByLayer.getValue(layer))
                    put("name", tr("model.deformer.meshShape", layers.getValue(layer).source.name))
                }
            }
        }
        val values = context.values + ("carriers" to carriers)
        return ModifierAlgorithmContext(JsonObject(if (head == null) values else values + ("head_turn" to head)), context.resources)
    }
}
