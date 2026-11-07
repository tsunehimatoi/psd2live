package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthVowelAlgorithm
import io.github.psd2live.algorithms.binding.MouthVowelV3Algorithm
import io.github.psd2live.algorithms.binding.MouthVowelV4Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigMouthVowelModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthVowelAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthVowelAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthVowelAlgorithm.TYPE
    }

    private val outlineAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthVowelV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthVowelV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthVowelAlgorithm.TYPE
    }

    fun outlineDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthVowelV3Algorithm.VERSION, outlineAlgorithm)

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthVowelV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthVowelV4Algorithm.optionalUpstream
        override val canonicalOptInType = MouthVowelAlgorithm.TYPE
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthVowelV4Algorithm.VERSION, fittedAlgorithm)

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthVowelAlgorithm.VERSION, algorithm)

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthVowelAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(version = version,
            parameters = { MouthVowelAlgorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthVowelAlgorithm.WRITES,
            evaluate = { _, _, _ -> error("Mouth vowel requires captured fitted-mouth inputs") }, authorContext = ::capture)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth vowel v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull {
            it.id == baseId && it.type == "mouth/fitted" &&
                (it.version in 1..2 || version == MouthVowelV4Algorithm.VERSION && it.version == 3)
        }) { "Mouth vowel v$version requires a compatible fitted mouth on the same scaffold" }
        require(preceding.none { it.type.startsWith("mouth.") &&
            !(version in MouthVowelV3Algorithm.VERSION..MouthVowelV4Algorithm.VERSION && it.type == "mouth.outline" && it.version == 3) }) {
            if (version == MouthVowelAlgorithm.VERSION) "Mouth vowel v2 requires a fitted mouth without preceding mouth modules. Use v1 with earlier mouth modules."
            else "Mouth vowel v$version supports only the fitted outline as a preceding mouth module."
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_VOWEL)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
            "Mouth vowel v$version requires readable fitted mouth source artwork"
        }
        require(!fitted.closedLine) {
            "Mouth vowel v$version requires a mouth without a separate closed drawing. Use v1 for closed-drawing swaps."
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val follows = linkedMapOf<String, MouthRig.Follow?>()
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measured = context.rigLayer(layer)
            val covered = fitted.covers(measured)
            val lipSkin = measured.semantic.tag == SemanticTag.FACE && measured.semantic.role == null &&
                fitted.lipped && fitted.rig?.fit?.hasLines == false
            if (!covered && !lipSkin) return@mapNotNull null
            require(target in base.targets && preceding.all { target in it.targets }) {
                "Mouth vowel v$version requires a shared source frame for its mouth targets"
            }
            follows[target.id] = if (covered) fitted.follow(measured) else null
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Mouth vowel v$version requires fitted mouth targets" }
        val measurements = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        return MouthVowelAlgorithm.context(measurements,
            follows, RigModules.MOUTH_VOWEL.defines.associate { it.id to tr(it.nameKey) }, tr("model.group.mouth"))
    }
}
