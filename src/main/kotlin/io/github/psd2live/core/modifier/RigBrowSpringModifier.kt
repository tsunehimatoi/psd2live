package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowSpringAlgorithm
import io.github.psd2live.algorithms.binding.BrowSpringInputs
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

internal object RigBrowSpringModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowSpringAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowSpringAlgorithm.optionalUpstream
        override val canonicalOptInType = BrowSpringAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val cache = HashMap<RigModifierInstance, BrowSpringInputs>()
        fun capture(instance: RigModifierInstance) = cache.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowSpringAlgorithm.TYPE), source).copy(
            version = BrowSpringAlgorithm.VERSION,
            parameters = { BrowSpringAlgorithm.parameters(capture(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Brow spring requires captured source inputs") },
            plugin = algorithm,
            changeWrites = BrowSpringAlgorithm.writes,
            upstreamInputs = true,
            authorContext = { capture(it).encode() },
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): BrowSpringInputs {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Brow spring v2 requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
        }.map { item ->
            if (item.type == BrowSpringAlgorithm.SHAPE_ENTRYPOINT) item.copy(type = BrowSpringAlgorithm.SHAPE) else item
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        require(preceding.any { it.id == baseId && it.type == "brow/fitted" && it.version in 1..2 }) {
            "Brow spring v2 requires a compatible fitted brow provider"
        }
        require(preceding.none { it.type in setOf(BrowSpringAlgorithm.TYPE, BrowSpringAlgorithm.ENTRYPOINT, "brow.blink", "brow.lid-coupling") }) {
            "Brow spring v2 requires a brow without spring, blink or lid coupling tracks. Use v1 with those tracks already present."
        }
        val state = preceding.filter { item ->
            RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }.associateBy { it.type }
        require("mouth.outline" !in state && "mouth.tongue" !in state) {
            "Brow spring v2 requires the native artwork order. Use v1 after mouth outline or tongue binding."
        }
        val eye = state["eye/fitted"]
        val shape = state[BrowSpringAlgorithm.SHAPE]
        require(eye == null || eye.version == 2) { "Brow spring v2 requires compatible fitted eyes" }
        require(shape == null || shape.version in 1..2) { "Brow spring v2 requires a compatible brow shape" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val byArea = state.values.filter { RigLegacyBindings.spec(it.type).isBase }.associateBy { RigLegacyBindings.spec(it.type).area }
        val bases = RigModules.areas.associate { area ->
            val base = byArea[area.id]
            area.id to if (base == null || base.type == RigLegacyBindings.FITTED_FACE) BaseChoice(area.legacy.id, area.legacy.version)
                else BaseChoice(base.type, base.version, RigLegacyBindings.values(base))
        }
        val modules = RigModules.modules.associate { module ->
            val chosen = state[module.id] ?: state[RigLegacyBindings.FITTED_FACE]?.takeIf { module == RigModules.FACE_DEPTH_TURN }
            module.id to if (chosen == null) ModuleChoice(false) else ModuleChoice(true, chosen.version, RigLegacyBindings.values(chosen))
        }
        val tuning = RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            state[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
        val native = sourceConfig().copy(
            recipe = RigRecipe(true, bases = bases, modules = modules, moves = corrections.moves, eyeParts = corrections.eyeParts),
            bodyStrength = state["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f,
            headTurnStrength = (state[RigLegacyBindings.FITTED_FACE] ?: state["face/legacy"])?.let(RigLegacyBindings::strength) ?: 1f,
            rigTuning = tuning,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val kept = state.keys.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it).parameters }
        return RigBuilder.browSpringSourceInputs(analysis, atlas, native, meshCache,
            instance.targets.associate { it.id to layersByMesh.getValue(it.id) }, kept, eye?.id, shape?.id) { mesh, parent ->
            ScaffoldBinding.deformerId(scaffold, mesh, parent)
        }
    }
}
