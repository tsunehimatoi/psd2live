package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthDepthAlgorithm
import io.github.psd2live.algorithms.binding.MouthDepthV3Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV4Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV5Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV6Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV7Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV8Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV9Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV10Algorithm
import io.github.psd2live.algorithms.binding.MouthRig.Follow
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModules
import kotlinx.serialization.json.*

internal object RigMouthDepthModifier {
    private val shadedFollows = setOf(Follow.TONGUE, Follow.DEEP, Follow.INSIDE, Follow.OPENING)

    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthDepthAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthDepthAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthDepthAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthDepthV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthDepthV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthDepthAlgorithm.TYPE
    }

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthDepthV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthDepthV4Algorithm.optionalUpstream
        override val canonicalOptInType = MouthDepthAlgorithm.TYPE
    }

    private val closedShellAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthDepthV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthDepthV5Algorithm.optionalUpstream
        override val canonicalOptInType = MouthDepthAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthV3Algorithm.VERSION, fittedAlgorithm)

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthV4Algorithm.VERSION, closedAlgorithm)

    fun closedShellDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthV5Algorithm.VERSION, closedShellAlgorithm)

    fun fallbackTongueFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthDepthV10Algorithm.VERSION, MouthDepthV10Algorithm, layered = false)

    fun sharedFrameDefinition(source: RigModifierSource?, withSharedTongue: Boolean = false,
                              layered: Boolean = false, withTeethOnly: Boolean = false): RigModifierDefinition {
        val algorithm = when {
            layered && withTeethOnly -> MouthDepthV9Algorithm
            layered -> MouthDepthV8Algorithm
            withSharedTongue -> MouthDepthV7Algorithm
            else -> MouthDepthV6Algorithm
        }
        val version = when {
            layered && withTeethOnly -> MouthDepthV9Algorithm.VERSION
            layered -> MouthDepthV8Algorithm.VERSION
            withSharedTongue -> MouthDepthV7Algorithm.VERSION
            else -> MouthDepthV6Algorithm.VERSION
        }
        return sourceFrameDefinition(source, version, algorithm, layered)
    }

    private fun sourceFrameDefinition(source: RigModifierSource?, version: Int,
                                      algorithm: ChangingModifierAlgorithmPlugin, layered: Boolean): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by algorithm,
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val canonicalOptInType = MouthDepthAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthDepthAlgorithm.TYPE, "mouth/fitted")
        }
        return fittedDefinition(source).copy(version = version, plugin = projected,
            changeWrites = when (version) {
                MouthDepthV9Algorithm.VERSION -> MouthDepthV9Algorithm.WRITES
                MouthDepthV10Algorithm.VERSION -> MouthDepthV10Algorithm.WRITES
                MouthDepthV8Algorithm.VERSION -> MouthDepthV8Algorithm.WRITES
                MouthDepthV7Algorithm.VERSION -> MouthDepthV7Algorithm.WRITES
                else -> MouthDepthV6Algorithm.WRITES
            }, authorContext = { requireNotNull(source).captureSharedFrames(it, layered) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance, layered: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        val fittedVersion = when {
            instance.version == MouthDepthV10Algorithm.VERSION -> 7
            layered -> 8
            else -> 6
        }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content &&
            it.type == "mouth/fitted" && it.version == fittedVersion }) {
            "Mouth interior depth v${instance.version} requires a shared fitted mouth v$fittedVersion"
        }
        val providers = setOf("mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid") +
            prefix.filter { it.type == "mouth.tongue" }.map { it.type }
        require(prefix.none { it.type.startsWith("mouth.") && it.type !in providers }) {
            "Mouth interior depth v${instance.version} requires the shared open-mouth source chain"
        }
        val interior = setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE, SemanticTag.MOUTH_OPEN)
        val projected = if (layered) interior + SemanticTag.MOUTH else interior
        return captureBindingTransition(instance, scaffold, prefix, CharacterAnalyzer.MOUTH_TAGS + SemanticTag.FACE + SemanticTag.NOSE,
            projectedTags = projected, includeMouthShading = true,
            includeLayeredTongueKeys = instance.version == MouthDepthV8Algorithm.VERSION, values = buildJsonObject {
                putJsonObject("providers") {
                    providers.forEach { type -> put(type, prefix.single { it.type == type }.id) }
                }
            })
    }

    private fun definition(source: RigModifierSource?, version: Int,
                           algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(MouthDepthAlgorithm.TYPE), source).copy(
            version = version,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            upstreamInputs = true,
            changeWrites = setOf("mesh", "deformer"),
            authorContext = { requireNotNull(source).capture(it, version) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val closed = version == MouthDepthV4Algorithm.VERSION || version == MouthDepthV5Algorithm.VERSION
        val sourceVersions = when (version) {
            MouthDepthV5Algorithm.VERSION -> 5..5
            MouthDepthV4Algorithm.VERSION -> 4..4
            MouthDepthV3Algorithm.VERSION -> 1..3
            else -> 1..2
        }
        val optional = when (version) {
            MouthDepthV5Algorithm.VERSION -> MouthDepthV5Algorithm.optionalUpstream
            MouthDepthV4Algorithm.VERSION -> MouthDepthV4Algorithm.optionalUpstream
            MouthDepthV3Algorithm.VERSION -> MouthDepthV3Algorithm.optionalUpstream
            else -> MouthDepthAlgorithm.optionalUpstream
        }
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth interior depth v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        require(preceding.any { it.id == baseId && it.type == "mouth/fitted" && it.version in sourceVersions }) {
            "Mouth interior depth v$version requires a compatible fitted mouth"
        }
        require(preceding.none { it.type.startsWith("mouth.") && it.type !in optional }) {
            "Use mouth interior depth v1 after other mouth modules"
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_DEPTH)
        val mouth = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null && (!closed || it.closedLine) }) {
            "Mouth interior depth v$version requires readable fitted mouth artwork"
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val inside = linkedMapOf<String, JsonObject>()
        val hasDrawnMouth = mouth.result.layers.any { it.semantic.tag == SemanticTag.MOUTH }
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measuredLayer = context.rigLayer(layer)
            val depth = if (!mouth.covers(measuredLayer)) 0f else when (mouth.follow(measuredLayer)) {
                Follow.UPPER_TEETH -> .06f
                Follow.LOWER_TEETH -> .1f
                Follow.TONGUE -> .16f
                Follow.DEEP -> .2f
                Follow.INSIDE, Follow.OPENING -> 0f
                else -> if (measuredLayer.semantic.tag == SemanticTag.MOUTH_OPEN && hasDrawnMouth) .12f else 0f
            }
            val shade = mouth.covers(layer) && (mouth.follow(layer) in shadedFollows ||
                (layer.semantic.tag == SemanticTag.MOUTH_OPEN && layer.semantic.role == null && hasDrawnMouth))
            if (depth == 0f && !shade) return@mapNotNull null
            require(preceding.all { target in it.targets }) { "Mouth interior depth v$version requires a shared source frame" }
            inside[target.id] = buildJsonObject { put("depth", depth); put("shade", shade) }
            target.id to layer
        }.toMap()
        val measured = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", "mouth-depth/1")
            put("source", measured.values)
            put("interior", JsonObject(inside))
        }, measured.resources)
    }
}
