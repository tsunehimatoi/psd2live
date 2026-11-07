package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowLidAlgorithm
import io.github.psd2live.algorithms.binding.BrowLidV3Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV4Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV5Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV6Algorithm
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
