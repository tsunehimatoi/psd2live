package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowBlinkAlgorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV3Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV4Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeInputs
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
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
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

internal object RigBrowBlinkModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkV3Algorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkV3Algorithm.TYPE
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowBlinkV3Algorithm.VERSION,
        plugin = fittedAlgorithm,
    )

    private val fallbackAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkV3Algorithm.optionalUpstream + setOf(
            "face/fitted", "face/legacy", "mouth/fitted", "brow.lid-coupling",
        )
        override val canonicalOptInType = BrowBlinkAlgorithm.TYPE
    }

    fun fallbackDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowBlinkV4Algorithm.VERSION, plugin = fallbackAlgorithm,
        authorContext = { requireNotNull(source).captureFallback(it) },
    )

    private fun RigModifierSource.captureFallback(instance: RigModifierInstance): ModifierAlgorithmContext {
        val observations = capture(instance)
        val fitted = BrowShapeInputs.decode(ModifierAlgorithmContext(
            JsonObject(observations.values - "carrier_names"), observations.resources)) {}
        val scaffold = provider(instance)
        val prefix = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        fun phase(entries: List<RigModifierInstance>): ModifierAlgorithmResource {
            val (native, measured) = RigFittedMouthModuleSource.context(this, scaffold, entries, null)
            val layers = measured.analysis.layers.associateBy { it.source.id.raw }
            val raw = fitted.targets.keys.map { id ->
                val layer = layers.getValue(layersByMesh.getValue(id))
                val channels = Drawable(DrawableId(id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
                PuppetIr.meshToIr(RigBuilder.browBlinkLoweringSource(layer, measured, native, atlas, meshCache, channels))
            }
            val branch = RigBuilder.browTurnSourceBranch(measured, native, meshCache, raw.mapNotNullTo(hashSetOf()) {
                it.parent?.let(::DeformerId)
            }).map(PuppetIr::deformerToIr)
            return ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = raw, deformers = branch)))
        }
        val ids = JsonObject(fitted.targets.keys.associateWith { id -> JsonPrimitive(primaryByLayer.getValue(layersByMesh.getValue(id))) })
        return ModifierAlgorithmContext(buildJsonObject {
            put("native_ids", ids)
            put("shape_names", observations.values.getValue("carrier_names"))
            put("turn_name", tr("model.deformer.meshTurn", "{meshName}"))
        }, mapOf("before" to phase(prefix), "after" to phase(prefix + instance)))
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(BrowBlinkAlgorithm.TYPE), source).copy(
            version = BrowBlinkAlgorithm.VERSION,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            plugin = algorithm,
            changeWrites = BrowBlinkAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow blink v${instance.version} requires a shared v2 scaffold" }
        val upstream = activeBefore(instance).filter { previous ->
            previous.config["scaffold"] == JsonPrimitive(scaffold.id) && previous.targets.any(instance.targets::contains)
        }
        val base = upstream.single { it.id == instance.config.getValue("base").jsonPrimitive.content && it.type == "brow/fitted" }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
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
        val measurements = RigBuilder.browShapeSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, eye?.id) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
        require(eye == null || measurements.targets.keys.all { ModifierTarget("mesh", it) in eye.targets }) {
            "Brow blink v${instance.version} requires the same fitted eye provider for all fitted brow targets"
        }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val names = JsonObject(measurements.targets.keys.associateWith { id ->
            JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layersByMesh.getValue(id)).source.name))
        })
        val encoded = measurements.encode()
        return ModifierAlgorithmContext(JsonObject(encoded.values + ("carrier_names" to names)), encoded.resources)
    }
}
