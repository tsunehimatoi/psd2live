package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthTongueAlgorithm
import io.github.psd2live.algorithms.binding.MouthTongueV3Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
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

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthTongueAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(version = version,
            parameters = { listOf(MouthTongueAlgorithm.parameter(capture(it))) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthTongueAlgorithm.WRITES,
            evaluate = { _, _, _ -> error("Mouth tongue requires captured fitted-mouth inputs") }, authorContext = ::capture)
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
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_TONGUE)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
            "Mouth tongue v$version requires readable fitted mouth source artwork"
        }
        require(!fitted.closedLine && !fitted.layered && fitted.rig?.fit?.layered == null) {
            "Mouth tongue v$version requires a single mouth drawing. Use v1 for layered lips or separate closed drawings."
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
