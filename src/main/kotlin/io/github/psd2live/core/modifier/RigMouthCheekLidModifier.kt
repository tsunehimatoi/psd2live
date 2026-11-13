package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV3Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV4Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV5Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV6Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV7Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV8Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV9Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV10Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV11Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV12Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV13Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV14Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV15Algorithm
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

    private val openShellAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCheekLidV9Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCheekLidV9Algorithm.optionalUpstream
        override val canonicalOptInType = MouthCheekLidV9Algorithm.TYPE
    }

    /** Single-eye open-mouth source contract; closed and bilateral variants retain their own definitions. */
    fun openShellDefinition(source: RigModifierSource?): RigModifierDefinition {
        val fitted = fittedDefinition(source)
        return fitted.copy(version = MouthCheekLidV9Algorithm.VERSION, plugin = openShellAlgorithm,
            requires = fitted.requires + setOf("face/fitted", "brow/fitted"),
            authorContext = { instance ->
                val capture = requireNotNull(source)
                val scaffold = capture.provider(instance)
                require(scaffold.version == 2) { "Cheek and lid v9 requires a shared v2 scaffold" }
                val prefix = capture.activeBefore(instance).filter { entry ->
                    entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                        RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
                }
                val mouthId = instance.config.getValue("base").jsonPrimitive.content
                val eyeId = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
                for ((type, version) in mapOf("mouth/fitted" to 3, "eye/fitted" to 5, "face/fitted" to 3, "brow/fitted" to 4)) {
                    val provider = prefix.singleOrNull { it.type == type }
                    require(provider != null && provider.version == version && provider.targets.containsAll(instance.targets) &&
                        (type != "mouth/fitted" || provider.id == mouthId) && (type != "eye/fitted" || provider.id == eyeId)) {
                        "Cheek and lid v9 requires $type v$version on the same shared scaffold"
                    }
                }
                val (native, measured) = RigFittedMouthModuleSource.context(capture, scaffold, prefix, null)
                val mouth = requireNotNull(measured.fittedMouth(native)?.takeIf { it.source != null }) {
                    "Cheek and lid v9 requires readable fitted open-mouth source artwork"
                }
                require(!mouth.closedLine) { "Cheek and lid v9 does not support a separate closed-mouth drawing" }
                require(measured.fittedEyes(native)?.rigs?.size == 1) { "Cheek and lid v9 requires exactly one readable fitted eye" }
                val context = fitted.authorContext(instance)
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

    fun sharedFrameDefinition(source: RigModifierSource?, withTongue: Boolean = false,
                              layered: Boolean = false): RigModifierDefinition = when {
        layered && withTongue -> sourceFrameDefinition(source, MouthCheekLidV12Algorithm.VERSION, MouthCheekLidV12Algorithm, withTongue = true)
        layered -> sourceFrameDefinition(source, MouthCheekLidV10Algorithm.VERSION, MouthCheekLidV10Algorithm)
        withTongue -> sourceFrameDefinition(source, MouthCheekLidV15Algorithm.VERSION, MouthCheekLidV15Algorithm, withTongue = true)
        else -> sourceFrameDefinition(source, MouthCheekLidV8Algorithm.VERSION, MouthCheekLidV8Algorithm)
    }

    fun fallbackFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthCheekLidV11Algorithm.VERSION, MouthCheekLidV11Algorithm)

    fun fallbackTongueFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthCheekLidV13Algorithm.VERSION, MouthCheekLidV13Algorithm, withTongue = true)

    fun layeredTongueTipDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthCheekLidV14Algorithm.VERSION, MouthCheekLidV14Algorithm, withTongue = true)

    private fun sourceFrameDefinition(source: RigModifierSource?, version: Int, algorithm: ChangingModifierAlgorithmPlugin,
                                      withTongue: Boolean = false): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by algorithm, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthCheekLidV8Algorithm.TYPE, "mouth/fitted", "eye/fitted")
            override val canonicalOptInType = MouthCheekLidV8Algorithm.TYPE
        }
        return definition(source).copy(version = version, plugin = projected,
            changeWrites = when (version) {
                MouthCheekLidV10Algorithm.VERSION -> MouthCheekLidV10Algorithm.WRITES
                MouthCheekLidV11Algorithm.VERSION -> MouthCheekLidV11Algorithm.WRITES
                MouthCheekLidV12Algorithm.VERSION -> MouthCheekLidV12Algorithm.WRITES
                MouthCheekLidV13Algorithm.VERSION -> MouthCheekLidV13Algorithm.WRITES
                MouthCheekLidV14Algorithm.VERSION -> MouthCheekLidV14Algorithm.WRITES
                MouthCheekLidV15Algorithm.VERSION -> MouthCheekLidV15Algorithm.WRITES
                else -> MouthCheekLidV8Algorithm.WRITES
            },
            authorContext = { requireNotNull(source).captureSharedFrames(it, withTongue) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance, withTongue: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = bindingPredecessors(instance, scaffold)
        require(scaffold.version == 2 && prior.all { it.targets.containsAll(instance.targets) } &&
            prior.none { it.type == MouthCheekLidV8Algorithm.TYPE }) { "Cheek and lid v${instance.version} requires a shared fitted source" }
        val baseProviders = setOf("face/fitted", "eye/fitted", "brow/fitted", "mouth/fitted", "mouth.outline", "mouth.vowel",
            "mouth.capture", "mouth.jaw")
        val providers = if (withTongue) baseProviders + "mouth.tongue" else baseProviders
        val lids = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH)
        return captureBindingTransition(instance, scaffold, prior, eyeBindingTags + CharacterAnalyzer.MOUTH_TAGS + SemanticTag.EYEBROW,
            projectedTags = lids, includeLayeredTongueKeys = instance.version in setOf(MouthCheekLidV12Algorithm.VERSION, MouthCheekLidV14Algorithm.VERSION),
            values = buildJsonObject {
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
                RigBuilder.rigContext(capture.analysis, capture.sourceConfig().copy(generateDeformers = false),
                    capture.meshCache, neutralScaffold = true).browCover
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
