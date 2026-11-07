package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV3Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV4Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV5Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV6Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV7Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV8Algorithm
import io.github.psd2live.core.CharacterAnalyzer
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

internal object RigMouthCheekLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    private val fittedMouthAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    fun fittedMouthDefinition(source: RigModifierSource?): RigModifierDefinition = fittedDefinition(source).copy(
        version = MouthCheekLidV4Algorithm.VERSION,
        plugin = fittedMouthAlgorithm,
    )

    private val fittedEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV3Algorithm.TYPE
    }

    fun fittedEyeDefinition(source: RigModifierSource?): RigModifierDefinition = fittedDefinition(source).copy(
        version = MouthCheekLidV5Algorithm.VERSION,
        plugin = fittedEyeAlgorithm,
    )

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV6Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV5Algorithm.TYPE
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition = fittedDefinition(source).copy(
        version = MouthCheekLidV6Algorithm.VERSION,
        plugin = closedAlgorithm,
    )

    private val closedShellAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV5Algorithm.TYPE
    }

    fun closedShellDefinition(source: RigModifierSource?): RigModifierDefinition {
        val fitted = fittedDefinition(source)
        return fitted.copy(version = MouthCheekLidV7Algorithm.VERSION, plugin = closedShellAlgorithm,
            authorContext = { instance ->
                val capture = requireNotNull(source)
                val context = fitted.authorContext(instance)
                val scaffold = capture.provider(instance)
                val prefix = capture.activeBefore(instance).filter { entry ->
                    entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                        RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
                }
                val (native, measured) = RigFittedMouthModuleSource.context(capture, scaffold, prefix, null)
                val layers = measured.analysis.layers.associateBy { it.source.id.raw }
                val turns = instance.targets.mapNotNull { target ->
                    val layer = layers[capture.layersByMesh[target.id]] ?: return@mapNotNull null
                    if (layer.semantic.tag !in setOf(SemanticTag.EYELASH, SemanticTag.EYEWHITE)) return@mapNotNull null
                    val channels = Drawable(DrawableId(target.id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
                    val raw = RigBuilder.lidTurnLoweringSource(layer, measured, native, capture.atlas, capture.meshCache, channels)
                    PuppetIr.meshToIr(raw).takeIf { it.shapes.isNotEmpty() }
                }
                val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = turns)))
                ModifierAlgorithmContext(JsonObject(context.values + ("turnName" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")))),
                    context.resources + ("lid_turn" to resource))
            })
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by MouthCheekLidV8Algorithm, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthCheekLidV8Algorithm.TYPE, "mouth/fitted", "eye/fitted")
            override val canonicalOptInType = MouthCheekLidV8Algorithm.TYPE
        }
        return definition(source).copy(version = MouthCheekLidV8Algorithm.VERSION, plugin = projected,
            changeWrites = MouthCheekLidV8Algorithm.WRITES, authorContext = { requireNotNull(source).captureSharedFrames(it) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = bindingPredecessors(instance, scaffold)
        require(scaffold.version == 2 && prior.all { it.targets.containsAll(instance.targets) } &&
            prior.none { it.type == MouthCheekLidV8Algorithm.TYPE }) { "Cheek and lid v8 requires a shared fitted source" }
        val providers = setOf("face/fitted", "eye/fitted", "brow/fitted", "mouth/fitted", "mouth.outline", "mouth.vowel",
            "mouth.capture", "mouth.jaw")
        val lids = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH)
        return captureBindingTransition(instance, scaffold, prior, eyeBindingTags + CharacterAnalyzer.MOUTH_TAGS + SemanticTag.EYEBROW,
            projectedTags = lids, values = buildJsonObject {
                putJsonObject("providers") { for (type in providers) put(type, prior.single { it.type == type }.id) }
            })
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = MouthCheekLidV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
        authorContext = { instance ->
            val capture = requireNotNull(source)
            val (measurements, carriers) = inputs(capture, instance)
            val browCover = if (capture.activeBefore(instance).any { it.type == "brow.lid-coupling" }) {
                RigBuilder.rigContext(capture.analysis, capture.sourceConfig(), capture.meshCache, neutralScaffold = true).browCover
            } else 0f
            MouthCheekLidV3Algorithm.context(measurements, carriers, browCover)
        },
    )

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(MouthCheekLidAlgorithm.TYPE), source).copy(
            version = MouthCheekLidAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = MouthCheekLidAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { instance ->
                val (measurements, carriers) = inputs(requireNotNull(source), instance)
                MouthCheekLidAlgorithm.context(measurements, carriers)
            },
        )

    private fun inputs(capture: RigModifierSource, instance: RigModifierInstance): Pair<ModifierAlgorithmContext, Map<String, Pair<String, String>>> {
        val eye = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val measurements = captureLidSource(capture, instance, emptyMap(), eye)
        val layers = capture.analysis.layers.associateBy { it.source.id.raw }
        return measurements to instance.targets.associate { target ->
            val layer = capture.layersByMesh.getValue(target.id)
            target.id to (capture.primaryByLayer.getValue(layer) to tr("model.deformer.meshShape", layers.getValue(layer).source.name))
        }
    }
}
