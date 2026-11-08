package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthTongueAlgorithm
import io.github.psd2live.algorithms.binding.MouthTongueV3Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV4Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV5Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV6Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV7Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV8Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import io.github.psd2live.format.compile.RigIrBinary
import kotlinx.serialization.json.*

internal object RigMouthTongueModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthTongueAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthTongueAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthTongueAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthTongueV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthTongueAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthTongueAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthTongueAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthTongueV3Algorithm.VERSION, fittedAlgorithm)

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthTongueV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthTongueAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthTongueAlgorithm.TYPE
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthTongueV4Algorithm.VERSION, closedAlgorithm)

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthTongueV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthTongueAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthTongueAlgorithm.TYPE
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthTongueV5Algorithm.VERSION, modernAlgorithm)

    fun fallbackFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sharedFrameDefinition(source, singleEye = true)

    fun sharedFrameDefinition(source: RigModifierSource?, layered: Boolean = false,
                              singleEye: Boolean = false): RigModifierDefinition {
        val (algorithm, version) = when {
            singleEye -> MouthTongueV8Algorithm() to MouthTongueV8Algorithm.VERSION
            layered -> MouthTongueV7Algorithm() to MouthTongueV7Algorithm.VERSION
            else -> MouthTongueV6Algorithm() to MouthTongueV6Algorithm.VERSION
        }
        val projected = object : ChangingModifierAlgorithmPlugin by algorithm,
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val canonicalOptInType = MouthTongueAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthTongueAlgorithm.TYPE, "mouth/fitted", "mouth.capture")
        }
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).captureSharedFrames(instance, layered, singleEye) }
        return definition(source).copy(version = version, plugin = projected,
            parameters = { instance -> RigIrBinary.decode(capture(instance).resources.getValue("after").read())
                .parameters.filter { it.id == MouthRig.TONGUE_OUT.raw } },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = ::capture)
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance, layered: Boolean, singleEye: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        val fittedVersion = when { singleEye -> 7; layered -> 8; else -> 6 }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content &&
            it.type == "mouth/fitted" && it.version == fittedVersion }) {
            "Mouth tongue v${instance.version} requires a shared fitted mouth v$fittedVersion"
        }
        require(prefix.none { it.type.startsWith("mouth.") && it.type !in supportedMouth }) {
            "Mouth tongue v${instance.version} requires the shared open-mouth source chain"
        }
        require(instance.targets.any { target -> analysis.layers.any { layer ->
            layer.source.id.raw == layersByMesh[target.id] && layer.semantic.tag == SemanticTag.TONGUE && layer.opaquePixels > 0
        } }) { "Mouth tongue v${instance.version} requires source tongue artwork" }
        val tags = if (layered || singleEye) CharacterAnalyzer.MOUTH_TAGS + SemanticTag.FACE else CharacterAnalyzer.MOUTH_TAGS
        return captureBindingTransition(instance, scaffold, prefix, tags, includeLayeredTongueKeys = layered, values = buildJsonObject {
            put("group", tr("model.group.mouth"))
            putJsonObject("providers") {
                supportedMouth.forEach { type -> put(type, prefix.single { it.type == type }.id) }
            }
        })
    }

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthTongueAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(version = version,
            parameters = { listOf(MouthTongueAlgorithm.parameter(capture(it))) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthTongueAlgorithm.WRITES,
            authorContext = ::capture)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth tongue v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        require(preceding.none { it.type.startsWith("mouth.") && it.type !in supportedMouth }) {
            "Mouth tongue v$version supports preceding outlines, vowels and capture. Use v1 for other preceding mouth modules."
        }
        val modern = version == MouthTongueV5Algorithm.VERSION
        val fittedVersion = if (modern) {
            val base = instance.config.getValue("base").jsonPrimitive.content
            requireNotNull(preceding.singleOrNull { it.type == "mouth/fitted" && it.id == base && (it.version == 3 || it.version == 5) }) {
                "Mouth tongue v5 requires the matching fitted mouth provider"
            }.version
        } else null
        val closedDrawing = version == MouthTongueV4Algorithm.VERSION || fittedVersion == 5
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_TONGUE,
            neutralScaffold = closedDrawing)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
            "Mouth tongue v$version requires readable fitted mouth source artwork"
        }
        require(fitted.closedLine == closedDrawing && !fitted.layered && fitted.rig?.fit?.layered == null) {
            if (closedDrawing) "Mouth tongue v$version requires a separate closed drawing without layered lips."
            else "Mouth tongue v$version requires a single mouth drawing. Use v1 for layered lips or separate closed drawings."
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val follows = linkedMapOf<String, MouthRig.Follow>()
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measured = context.rigLayer(layer)
            if (!fitted.covers(measured)) return@mapNotNull null
            require(preceding.all { target in it.targets }) { "Mouth tongue v$version requires a shared source frame for its mouth targets" }
            follows[target.id] = fitted.follow(measured)
            target.id to layer
        }.toMap()
        require(follows.containsValue(MouthRig.Follow.TONGUE)) { "Mouth tongue v$version requires tongue artwork among its targets" }
        val measurements = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        return MouthTongueAlgorithm.context(measurements, follows,
            tr(RigModules.MOUTH_TONGUE.defines.single().nameKey), tr("model.group.mouth"))
    }

    private val supportedMouth = setOf("mouth.outline", "mouth.vowel", "mouth.capture")
}
