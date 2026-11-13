package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowBlinkAlgorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV3Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV4Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV5Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV6Algorithm
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

    private val sharedFrameAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = fallbackAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkAlgorithm.TYPE
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowBlinkV5Algorithm.VERSION,
        plugin = sharedFrameAlgorithm,
        authorContext = { requireNotNull(source).captureFallback(it) },
    )

    private val singleEyeAlgorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV6Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkV6Algorithm.optionalUpstream
        override val canonicalOptInType = BrowBlinkV6Algorithm.TYPE
    }

    /** Explicit source-only candidate; dip is read only by the independent worker. */
    fun singleEyeDefinition(source: RigModifierSource?): RigModifierDefinition = definition(source).copy(
        version = BrowBlinkV6Algorithm.VERSION, plugin = singleEyeAlgorithm,
        requires = BrowBlinkV6Algorithm.requires,
        authorContext = { requireNotNull(source).captureSingleEye(it) },
    )

    private fun RigModifierSource.captureSingleEye(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2 && scaffold.targets.containsAll(instance.targets))
        val prefix = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        require(prefix.none { it.type in BrowBlinkV6Algorithm.unsupported }) {
            "Brow blink v6 does not support preceding blink, spring, cheek coupling or separate depth turn"
        }
        fun required(type: String, version: Int) = requireNotNull(prefix.singleOrNull { it.type == type }) {
            "Brow blink v6 requires one unambiguous $type provider"
        }.also {
            require(it.version == version && it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
                it.targets.containsAll(instance.targets)) { "Brow blink v6 requires $type@$version on its shared scaffold" }
        }
        val face = required("face/fitted", 3)
        val eye = required("eye/fitted", 5)
        val brow = required("brow/fitted", 4)
        val shape = required("brow.shape", 6)
        val lid = required("brow.lid-coupling", 8)
        require(brow.id == instance.config.getValue("base").jsonPrimitive.content &&
            listOf(brow, shape, lid).all { it.targets.toSet() == instance.targets.toSet() } &&
            shape.config["base"] == JsonPrimitive(brow.id) && lid.config["base"] == JsonPrimitive(brow.id) &&
            lid.config["dependencies"]?.jsonObject?.get("eye/fitted") == JsonPrimitive(eye.id) &&
            prefix.indexOf(shape) < prefix.indexOf(lid)) { "Brow blink v6 requires its preceding shape6 and lid8 chain" }
        for ((type, version) in BrowBlinkV6Algorithm.directVersions) {
            val candidates = prefix.filter { it.type == type }
            require(candidates.size <= 1)
            candidates.singleOrNull()?.let { require(it.version == version && it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
                it.config["base"] == JsonPrimitive(eye.id) && it.targets.containsAll(instance.targets)) }
        }
        // Use the actual preceding shape instance: neither this blink nor the later lid can
        // influence the source measurements or the already witnessed head-turn capture.
        val captured = RigBrowShapeModifier.openShellDefinition(this).authorContext(shape)
        require(captured.values["face"] == JsonPrimitive(face.id) && captured.values["eye"] == JsonPrimitive(eye.id))
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val names = captured.values.getValue("native_ids").jsonObject.keys.associateWith { id ->
            JsonPrimitive(tr("model.deformer.meshShape", layers.getValue(layersByMesh.getValue(id)).source.name))
        }
        return ModifierAlgorithmContext(JsonObject(captured.values + mapOf(
            "shape" to JsonPrimitive(shape.id), "lid" to JsonPrimitive(lid.id), "carrier_names" to JsonObject(names))), captured.resources)
    }

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
