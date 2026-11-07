package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthDepthAlgorithm
import io.github.psd2live.algorithms.binding.MouthDepthV3Algorithm
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

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthDepthV3Algorithm.VERSION, fittedAlgorithm)

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
        val fittedSource = version == MouthDepthV3Algorithm.VERSION
        val sourceVersions = if (fittedSource) 1..3 else 1..2
        val optional = if (fittedSource) MouthDepthV3Algorithm.optionalUpstream else MouthDepthAlgorithm.optionalUpstream
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
        val mouth = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null }) {
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
