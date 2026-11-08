package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthJawAlgorithm
import io.github.psd2live.algorithms.binding.MouthJawV3Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV4Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV5Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV6Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV7Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV8Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV9Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV10Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV11Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV12Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV13Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import kotlinx.serialization.json.*

internal object RigMouthJawModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthJawAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthJawV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    private val tongueAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthJawV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawV4Algorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    fun tongueDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawV4Algorithm.VERSION, tongueAlgorithm)

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthJawV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawV5Algorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawV5Algorithm.VERSION, closedAlgorithm)

    private val closedTongueAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthJawV6Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawV5Algorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    fun closedTongueDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawV6Algorithm.VERSION, closedTongueAlgorithm)

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthJawV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthJawV7Algorithm.optionalUpstream
        override val canonicalOptInType = MouthJawAlgorithm.TYPE
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawV7Algorithm.VERSION, modernAlgorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawV3Algorithm.VERSION, fittedAlgorithm)

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, MouthJawAlgorithm.VERSION, algorithm)

    private enum class FrameMode(val version: Int, val fitted: Int, val algorithm: ChangingModifierAlgorithmPlugin) {
        FITTED(MouthJawV8Algorithm.VERSION, 6, MouthJawV8Algorithm),
        TONGUE(MouthJawV9Algorithm.VERSION, 6, MouthJawV9Algorithm),
        LAYERED(MouthJawV10Algorithm.VERSION, 8, MouthJawV10Algorithm),
        LAYERED_TONGUE(MouthJawV12Algorithm.VERSION, 8, MouthJawV12Algorithm),
        FALLBACK(MouthJawV11Algorithm.VERSION, 7, MouthJawV11Algorithm),
        FALLBACK_TONGUE(MouthJawV13Algorithm.VERSION, 7, MouthJawV13Algorithm),
    }

    fun sharedFrameDefinition(source: RigModifierSource?, withTongue: Boolean = false,
                              layered: Boolean = false): RigModifierDefinition = frameDefinition(source, when {
        layered && withTongue -> FrameMode.LAYERED_TONGUE
        layered -> FrameMode.LAYERED
        withTongue -> FrameMode.TONGUE
        else -> FrameMode.FITTED
    })

    fun fallbackFrameDefinition(source: RigModifierSource?): RigModifierDefinition = frameDefinition(source, FrameMode.FALLBACK)

    fun fallbackTongueFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        frameDefinition(source, FrameMode.FALLBACK_TONGUE)

    private fun frameDefinition(source: RigModifierSource?, mode: FrameMode): RigModifierDefinition {
        val algorithm = mode.algorithm
        val projected = object : ChangingModifierAlgorithmPlugin by algorithm,
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val canonicalOptInType = MouthJawAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthJawAlgorithm.TYPE, "mouth/fitted")
        }
        return modernDefinition(source).copy(version = mode.version, plugin = projected,
            authorContext = { requireNotNull(source).captureSharedFrames(it, mode) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance, mode: FrameMode): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val version = instance.version
        val prefix = bindingPredecessors(instance, scaffold)
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content &&
            it.type == "mouth/fitted" && it.version == mode.fitted }) {
            "Mouth jaw v$version requires a shared fitted mouth v${mode.fitted}"
        }
        val withTongue = mode in setOf(FrameMode.TONGUE, FrameMode.LAYERED_TONGUE, FrameMode.FALLBACK_TONGUE)
        val layered = mode == FrameMode.LAYERED || mode == FrameMode.LAYERED_TONGUE
        val baseProviders = setOf("mouth.outline", "mouth.vowel", "mouth.capture")
        val providers = if (withTongue) baseProviders + "mouth.tongue" else baseProviders
        require(prefix.none { it.type.startsWith("mouth.") && it.type !in providers }) {
            if (withTongue) "Mouth jaw v$version requires the shared open-mouth outline, vowel, capture and tongue chain"
            else "Mouth jaw v$version requires the shared open-mouth outline, vowel and capture chain"
        }
        val face = setOf(SemanticTag.FACE, SemanticTag.NOSE, SemanticTag.FACE_DETAIL)
        val captured = face + CharacterAnalyzer.MOUTH_TAGS
        return captureBindingTransition(instance, scaffold, prefix, captured,
            projectedTags = if (layered) captured else face, includeLayeredTongueKeys = mode == FrameMode.LAYERED_TONGUE,
            values = buildJsonObject {
                putJsonObject("providers") {
                    providers.forEach { type -> put(type, prefix.single { it.type == type }.id) }
                }
            })
    }

    private fun definition(source: RigModifierSource?, version: Int, algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(MouthJawAlgorithm.TYPE), source).copy(
            version = version, parameters = { emptyList() }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = setOf("mesh"),
            authorContext = { requireNotNull(source).capture(it, version) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth jaw v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val modern = version == MouthJawV7Algorithm.VERSION
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = preceding.singleOrNull { it.id == baseId && it.type == "mouth/fitted" }
        val closedDrawing = version in MouthJawV5Algorithm.VERSION..MouthJawV6Algorithm.VERSION || modern && base?.version == 5
        val sourceBinding = version >= MouthJawV3Algorithm.VERSION
        val fittedVersions = when {
            modern -> if (closedDrawing) 5..5 else 3..3
            closedDrawing -> 4..4
            sourceBinding -> 3..3
            else -> 1..2
        }
        require(base != null && base.version in fittedVersions) {
            "Mouth jaw v$version requires a compatible fitted mouth"
        }
        val allowTongue = version == MouthJawV4Algorithm.VERSION
        val mouthUpstream = when {
            modern -> MouthJawV7Algorithm.mouthUpstream
            closedDrawing -> MouthJawV5Algorithm.mouthUpstream
            allowTongue -> MouthJawV4Algorithm.mouthUpstream
            else -> MouthJawAlgorithm.mouthUpstream
        }
        require(preceding.none { it.type.startsWith("mouth.") && it.type !in mouthUpstream }) {
            "Use mouth jaw v1 after other mouth modules"
        }
        require(!allowTongue || preceding.filter { it.type == "mouth.tongue" }.all { it.version == 3 }) {
            "Mouth jaw v4 requires a compatible tongue provider"
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, null,
            neutralScaffold = closedDrawing)
        val fitted = requireNotNull(context.fittedMouth(native))
        val measured = requireNotNull(fitted.source) { "Mouth jaw v$version requires readable fitted mouth artwork" }
        require(!fitted.layered) { "Use mouth jaw v1 for artwork with separate lip or line layers" }
        require(if (modern) fitted.closedLine == closedDrawing else !closedDrawing || fitted.closedLine) {
            if (modern) "Mouth jaw v$version requires a fitted provider for its drawing profile"
            else "Mouth jaw v5 requires a separate closed drawing"
        }
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val materials = mutableListOf<ScaffoldMeshContext.Material>()
        val targets = buildJsonObject {
            for (target in instance.targets) {
                val layer = sourceLayers[layersByMesh[target.id]] ?: continue
                val aligned = context.rigLayer(layer)
                val tag = aligned.semantic.tag
                if (fitted.covers(aligned) || aligned.semantic.role?.let { it != PartRole.SHADOW } == true) continue
                if (tag != SemanticTag.FACE && tag != SemanticTag.NOSE &&
                    !(tag == SemanticTag.FACE_DETAIL && measured.fit.toMouth(aligned.centroidX, aligned.centroidY).second > 0f)) continue
                require(preceding.all { target in it.targets }) { "Mouth jaw v$version requires a shared source frame for its targets" }
                val (parent, frame) = context.parentAndFrame(layer, native)
                val data = RigBuilder.fittedMouthSourceMesh(layer, context, native, atlas, meshCache)
                materials += ScaffoldMeshContext.Material(target.id, layer.source.id.raw,
                    ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
                putJsonObject(target.id) {
                    put("nose", tag == SemanticTag.NOSE)
                    put("parent", parent?.raw?.let(::JsonPrimitive) ?: JsonNull)
                    put("frame", JsonArray(listOf(frame.left, frame.top, frame.right, frame.bottom).map(::JsonPrimitive)))
                }
            }
        }
        val fit = measured.fit
        val fittedSource = if (sourceBinding) measured.encode() else null
        return ModifierAlgorithmContext(buildJsonObject {
            if (fittedSource != null) {
                put("schema", "mouth-jaw/2")
                put("mouth", fittedSource.values)
                if (modern) put("closedDrawing", closedDrawing)
            } else {
                put("schema", "mouth-jaw/1")
                put("fit", JsonArray(listOf(fit.originX, fit.originY, fit.axisX, fit.axisY, fit.width, fit.opening).map(::JsonPrimitive)))
                put("reach", measured.options.reach)
            }
            put("chin", fitted.chin)
            put("targets", targets)
            putJsonObject("providers") {
                val optionalUpstream = when {
                    modern -> MouthJawV7Algorithm.optionalUpstream
                    closedDrawing -> MouthJawV5Algorithm.optionalUpstream
                    allowTongue -> MouthJawV4Algorithm.optionalUpstream
                    else -> MouthJawAlgorithm.optionalUpstream
                }
                for (provider in preceding.filter { it.type in optionalUpstream }) {
                    put(provider.type, provider.id)
                }
            }
        }, ScaffoldMeshContext.capture(materials).resources + fittedSource?.resources.orEmpty())
    }
}
