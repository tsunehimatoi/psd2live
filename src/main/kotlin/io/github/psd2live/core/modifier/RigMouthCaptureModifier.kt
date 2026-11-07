package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm
import io.github.psd2live.algorithms.binding.MouthCaptureV3Algorithm
import io.github.psd2live.algorithms.binding.MouthCaptureV4Algorithm
import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigMouthCaptureModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthCaptureAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCaptureAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCaptureAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCaptureV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCaptureAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCaptureAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthCaptureAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthCaptureV3Algorithm.VERSION, fittedAlgorithm)

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthCaptureV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthCaptureAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthCaptureAlgorithm.TYPE
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthCaptureV4Algorithm.VERSION, closedAlgorithm)

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthCaptureAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return legacy.copy(version = version,
            parameters = { MouthCaptureAlgorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthCaptureAlgorithm.WRITES,
            authorContext = ::capture)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val closedDrawing = version == MouthCaptureV4Algorithm.VERSION
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth capture v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull {
            it.id == baseId && it.type == "mouth/fitted" &&
                (if (closedDrawing) it.version == 4 else it.version in 1..2 || version == MouthCaptureV3Algorithm.VERSION && it.version == 3)
        }) { "Mouth capture v$version requires a compatible fitted mouth on the same scaffold" }
        require(preceding.none { it.type.startsWith("mouth.") && it.type !in setOf("mouth.outline", "mouth.vowel") }) {
            "Mouth capture v$version supports preceding outlines and vowels. Use v1 for other preceding mouth modules."
        }
        // The worker reads the upstream mesh tracks. Only source coordinates and fitting belong here.
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_CAPTURE,
            neutralScaffold = version >= MouthCaptureV3Algorithm.VERSION)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
            "Mouth capture v$version requires readable fitted mouth source artwork"
        }
        require(fitted.closedLine == closedDrawing) {
            if (closedDrawing) "Mouth capture v$version requires a separate closed drawing."
            else "Mouth capture v$version requires a mouth without a separate closed drawing. Use v1 for closed-drawing swaps."
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val follows = linkedMapOf<String, MouthRig.Follow?>()
        val lippedSkin = linkedSetOf<String>()
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measured = context.rigLayer(layer)
            val covered = fitted.covers(measured)
            val lipSkin = measured.semantic.tag == SemanticTag.FACE && measured.semantic.role == null &&
                fitted.lipped && fitted.rig?.fit?.hasLines == false
            val face = measured.semantic.role in setOf(null, PartRole.SHADOW) &&
                (measured.semantic.tag in setOf(SemanticTag.FACE, SemanticTag.NOSE) ||
                    measured.semantic.tag == SemanticTag.FACE_DETAIL &&
                    requireNotNull(fitted.rig).fit.toMouth(measured.centroidX, measured.centroidY).second > 0f)
            if (!covered && !face) return@mapNotNull null
            if (lipSkin) lippedSkin += target.id
            require(target in base.targets && preceding.all { target in it.targets }) {
                "Mouth capture v$version requires a shared source frame for its mouth targets"
            }
            follows[target.id] = if (covered) fitted.follow(measured) else null
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Mouth capture v$version requires fitted mouth targets" }
        val measurements = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        return MouthCaptureAlgorithm.context(measurements,
            follows, lippedSkin, RigModules.MOUTH_CAPTURE.defines.associate { it.id to tr(it.nameKey) }, tr("model.group.mouth"))
    }
}
