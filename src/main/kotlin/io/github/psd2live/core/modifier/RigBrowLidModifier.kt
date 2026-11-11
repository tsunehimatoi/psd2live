package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowLidAlgorithm
import io.github.psd2live.algorithms.binding.BrowLidV3Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV4Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV5Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV6Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV8Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV7Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

internal object RigBrowLidModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowLidAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowLidAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV3Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV3Algorithm.TYPE
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
        changeWrites = BrowLidV3Algorithm.WRITES,
    )

    private val fittedEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV4Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV4Algorithm.TYPE
    }

    fun fittedEyeDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV4Algorithm.VERSION,
        plugin = fittedEyeAlgorithm,
        changeWrites = BrowLidV4Algorithm.WRITES,
    )

    private val sidedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV5Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV5Algorithm.TYPE
    }

    fun sidedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV5Algorithm.VERSION,
        plugin = sidedAlgorithm,
        changeWrites = BrowLidV5Algorithm.WRITES,
    )

    private val fallbackAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV6Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV6Algorithm.optionalUpstream + setOf("face/fitted", "face/legacy", "brow.shape", "eye.highlight", "eye.squash")
        override val canonicalOptInType = BrowLidV6Algorithm.TYPE
    }

    fun fallbackDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV6Algorithm.VERSION, plugin = fallbackAlgorithm, changeWrites = BrowLidV6Algorithm.WRITES,
        authorContext = { requireNotNull(source).captureFallback(it) },
    )

    private val singleEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowLidV8Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowLidV8Algorithm.optionalUpstream
        override val canonicalOptInType = BrowLidV8Algorithm.TYPE
    }

    /** Explicit candidate. The host captures only source measurements, never this modifier's motion. */
    fun singleEyeDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowLidV8Algorithm.VERSION, plugin = singleEyeAlgorithm, changeWrites = BrowLidV8Algorithm.WRITES,
        requires = definition(source).requires + setOf("face/fitted", "brow.shape"),
        authorContext = { requireNotNull(source).captureSingleEye(it) },
    )

    private fun RigModifierSource.captureSingleEye(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
        }
        require(scaffold.version == 2)
        require(prior.none { it.type in setOf(BrowLidV8Algorithm.TYPE, "face.depth-turn", "mouth.cheek-lid") })
        val eye = prior.single { it.id == instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content }
        val brow = prior.single { it.id == instance.config.getValue("base").jsonPrimitive.content }
        val face = prior.single { it.type == "face/fitted" }
        val shape = prior.single { it.type == "brow.shape" }
        require(eye.type == "eye/fitted" && eye.version == 5 && brow.type == "brow/fitted" && brow.version == 4 &&
            face.version == 3 && shape.version in setOf(1, 6) && shape.config["base"] == JsonPrimitive(brow.id) &&
            shape.targets.toSet() == instance.targets.toSet() && brow.targets.toSet() == instance.targets.toSet() &&
            listOf(eye, brow, face, shape).all { it.targets.containsAll(instance.targets) }) {
            "Brow and lid v8 requires shared scaffold2/face3/eye5/brow4 with shape1 or shape6 on the same targets"
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()),
            headTurnStrength = 1f, bodyStrength = 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        val captured = capture(instance)
        val brows = analysis.layers.filter { it.semantic.tag == SemanticTag.EYEBROW && it.semantic.role == null }
        val sides = instance.targets.mapNotNull { target ->
            val layer = brows.singleOrNull { it.source.id.raw == layersByMesh[target.id] } ?: return@mapNotNull null
            target.id to JsonPrimitive(layer.semantic.side.name)
        }.toMap()
        val source = ModifierAlgorithmContext(JsonObject(captured.values + mapOf(
            "shape" to JsonPrimitive(shape.id), "brow_sides" to JsonObject(sides))), captured.resources)
        return captureLidHeadSource(instance, scaffold, eye, native, source)
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val implementation = object : ChangingModifierAlgorithmPlugin by BrowLidV7Algorithm, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(BrowLidV7Algorithm.TYPE, "brow/fitted", "eye/fitted")
            override val canonicalOptInType = BrowLidV7Algorithm.TYPE
        }
        return definition(source).copy(version = BrowLidV7Algorithm.VERSION, plugin = implementation,
            changeWrites = BrowLidV7Algorithm.WRITES, authorContext = { requireNotNull(source).captureSharedFrame(it) })
    }

    private fun RigModifierSource.captureSharedFrame(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = bindingPredecessors(instance, scaffold)
        require(scaffold.version == 2 && prior.all { it.targets.containsAll(instance.targets) }) {
            "Brow and lid v7 requires a shared fitted source"
        }
        val face = prior.single { it.type == "face/fitted" && it.version == 3 }
        val shape = prior.single { it.type == "brow.shape" && it.version in setOf(1, 5) }
        require(prior.none { it.type in setOf(BrowLidV7Algorithm.TYPE, "mouth.cheek-lid", "face.depth-turn") })
        return captureBindingTransition(instance, scaffold, prior, eyeBindingTags + SemanticTag.EYEBROW,
            projectedTags = eyeBindingTags, values = buildJsonObject {
                put("face", face.id)
                put("shape", shape.id)
            })
    }

    private fun RigModifierSource.captureFallback(instance: RigModifierInstance): ModifierAlgorithmContext {
        val context = capture(instance)
        val scaffold = provider(instance)
        val prefix = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val (native, measured) = RigFittedMouthModuleSource.context(this, scaffold, prefix, null)
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        val turns = instance.targets.mapNotNull { target ->
            val layer = layers[layersByMesh[target.id]] ?: return@mapNotNull null
            if (layer.semantic.tag !in setOf(SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.IRIDES)) return@mapNotNull null
            val channels = Drawable(DrawableId(target.id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
            PuppetIr.meshToIr(RigBuilder.lidTurnLoweringSource(layer, measured, native, atlas, meshCache, channels))
        }
        val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = turns)))
        return ModifierAlgorithmContext(JsonObject(context.values + ("turn_name" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")))),
            context.resources + ("lid_turn" to resource))
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(BrowLidAlgorithm.TYPE), source).copy(
            version = BrowLidAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = BrowLidAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val eyeId = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val eye = activeBefore(instance).single { it.id == eyeId && it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(eye, RigTuning()),
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val targets = instance.targets.associate { it.id to layersByMesh.getValue(it.id) }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val measurements = RigBuilder.eyeWideSourceInputs(analysis, atlas, native, meshCache, targets) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        val context = RigBuilder.rigContext(analysis, native, meshCache, neutralScaffold = true)
        return ModifierAlgorithmContext(JsonObject(measurements.values + mapOf(
            "brow_cover" to JsonPrimitive(context.browCover),
            "brow_meshes" to JsonArray(targets.filterValues { layer ->
                layers.getValue(layer).semantic.let { it.tag == SemanticTag.EYEBROW && it.role == null }
            }.keys.map(::JsonPrimitive)),
            "carrier_names" to JsonObject(targets.mapValues { (_, layer) ->
                JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layer).source.name))
            }),
            "native_ids" to JsonObject(targets.mapValues { (_, layer) -> JsonPrimitive(primaryByLayer.getValue(layer)) }),
        )), measurements.resources)
    }
}
