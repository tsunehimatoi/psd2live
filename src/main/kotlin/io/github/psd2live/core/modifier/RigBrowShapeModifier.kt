package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowShapeAlgorithm
import io.github.psd2live.algorithms.binding.BrowShapeV3Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV4Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV5Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeInputs
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DeformerId
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

internal object RigBrowShapeModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye/fitted")
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye/fitted")
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, BrowShapeAlgorithm.VERSION, algorithm)

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition =
        definition(source, BrowShapeV3Algorithm.VERSION, fittedAlgorithm)

    private val sharedFrameAlgorithm = object : ChangingModifierAlgorithmPlugin by BrowShapeV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
            .mapTo(linkedSetOf()) { it.type } - BrowShapeAlgorithm.TYPE
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val fitted = fittedDefinition(source)
        return fitted.copy(version = BrowShapeV5Algorithm.VERSION, plugin = sharedFrameAlgorithm,
            parameters = { instance -> BrowShapeAlgorithm.parameters(requireNotNull(source).capture(instance, BrowShapeV5Algorithm.VERSION)) },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).captureSharedFrames(it) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        val face = prefix.single { it.type == "face/fitted" && it.version == 3 }
        val eye = prefix.single { it.type == "eye/fitted" && it.version == 5 }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "brow/fitted" && it.version == 4 })
        return captureBindingTransition(instance, scaffold, prefix, eyeBindingTags + SemanticTag.EYEBROW,
            projectedTags = setOf(SemanticTag.EYEBROW), values = buildJsonObject {
                put("face", face.id)
                put("eye", eye.id)
                put("group", tr("model.group.brows"))
            })
    }

    private val fallbackAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = setOf("eye/fitted", "face/fitted", "face/legacy", "mouth/fitted")
        override val canonicalOptInType = BrowShapeAlgorithm.TYPE
    }

    fun fallbackDefinition(source: RigModifierSource?): RigModifierDefinition {
        val fitted = fittedDefinition(source)
        return fitted.copy(version = BrowShapeV4Algorithm.VERSION, plugin = fallbackAlgorithm,
            changeWrites = fitted.changeWrites.orEmpty() + "deformer",
            authorContext = { instance -> requireNotNull(source).captureFallback(instance, fitted.authorContext(instance)) })
    }

    private fun RigModifierSource.captureFallback(
        instance: RigModifierInstance, context: ModifierAlgorithmContext,
    ): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val preceding = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val (native, measured) = RigFittedMouthModuleSource.context(this, scaffold, preceding, null)
        val source = BrowShapeInputs.decode(context) {}
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        val raw = source.targets.keys.map { id ->
            val layer = layers.getValue(layersByMesh.getValue(id))
            require(layer.semantic.tag == SemanticTag.EYEBROW)
            val channels = Drawable(DrawableId(id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
            PuppetIr.meshToIr(RigBuilder.browTurnLoweringSource(layer, measured, native, atlas, meshCache, channels))
        }
        val branch = RigBuilder.browTurnSourceBranch(measured, native, meshCache, raw.mapNotNullTo(hashSetOf()) {
            it.parent?.let(::DeformerId)
        }).map(PuppetIr::deformerToIr)
        val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = raw, deformers = branch)))
        val ids = JsonObject(source.targets.keys.associateWith { id -> JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(id))) })
        return ModifierAlgorithmContext(JsonObject(context.values + mapOf(
            "native_ids" to ids, "turn_name" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")))),
            context.resources + ("brow_turn" to resource))
    }

    private fun definition(source: RigModifierSource?, version: Int,
                           algorithm: ReadingModifierAlgorithmPlugin): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, BrowShapeInputs>()
        fun measurements(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance, version) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowShapeAlgorithm.TYPE), source).copy(
            version = version,
            parameters = { BrowShapeAlgorithm.parameters(measurements(it)) },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = setOf("mesh", "parameter", "hierarchy"),
            upstreamInputs = true,
            authorContext = { measurements(it).encode() },
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int): BrowShapeInputs {
        val fitted = version in setOf(BrowShapeV3Algorithm.VERSION, BrowShapeV5Algorithm.VERSION)
        val browVersions = when (version) {
            BrowShapeV5Algorithm.VERSION -> 4..4
            BrowShapeV3Algorithm.VERSION -> 1..3
            else -> 1..2
        }
        val eyeVersions = if (fitted) setOf(2, 5) else setOf(2)
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow shape v$version requires a shared v2 scaffold" }
        val upstream = activeBefore(instance).filter { previous ->
            previous.config["scaffold"] == JsonPrimitive(scaffold.id) && previous.targets.any(instance.targets::contains)
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val base = requireNotNull(upstream.singleOrNull { it.id == baseId && it.type == "brow/fitted" && it.version in browVersions }) {
            "Brow shape v$version requires a compatible fitted brow provider"
        }
        require(upstream.none { previous ->
            previous.type in setOf(BrowShapeAlgorithm.TYPE, BrowShapeAlgorithm.ENTRYPOINT, BrowShapeV3Algorithm.ENTRYPOINT, "brow.spring", "brow.blink", "brow.lid-coupling")
        }) { "Brow shape v$version must precede other brow motion modifiers" }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
        require(eye == null || eye.version in eyeVersions) { "Brow shape v$version requires compatible fitted eyes" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val bases = RigModules.areas.associate { area ->
            val chosen = when (area.id) {
                "eye" -> eye
                "brow" -> base
                else -> null
            }
            area.id to if (chosen == null) BaseChoice(area.legacy.id, area.legacy.version)
            else BaseChoice(chosen.type, chosen.version, RigLegacyBindings.values(chosen))
        }
        val recipe = RigRecipe(true, bases = bases, modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(base, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        return RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, eye?.id) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
    }
}
