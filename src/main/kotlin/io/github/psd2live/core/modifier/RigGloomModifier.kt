package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm
import io.github.psd2live.algorithms.binding.GloomBindingAlgorithm
import io.github.psd2live.algorithms.binding.GloomCarrierAlgorithm
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object RigGloomModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by GloomBindingAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = GloomBindingAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(GloomBindingAlgorithm.TYPE), source).copy(
            version = GloomBindingAlgorithm.VERSION,
            exclusiveFamily = GloomBindingAlgorithm.TYPE,
            parameters = { emptyList() },
            resolveParameter = { _, _, _ -> null },
            validate = { _, instance ->
                require(instance.config.keys.all { it in setOf("scaffold", "values", "tuning") } && "scaffold" in instance.config) {
                    "Specify Gloom's scaffold and editable values"
                }
                RigLegacyBindings.values(instance)
                RigLegacyBindings.tuning(instance, RigTuning())
                require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) {
                    "Gloom targets must have captured source layers"
                }
            },
            plugin = algorithm,
            changeWrites = GloomBindingAlgorithm.WRITES,
            upstreamInputs = true,
            authorContext = { requireNotNull(source).gloomContext(it) },
        )

    fun carrierDefinition(source: RigModifierSource?): RigModifierDefinition {
        val carrier = object : ReadingModifierAlgorithmPlugin by GloomCarrierAlgorithm(),
            ModifierVersionSelection, ModifierOptionalUpstream {
            override val canonicalOptInType = GloomBindingAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.mapTo(linkedSetOf()) { it.type } -
                setOf(GloomBindingAlgorithm.TYPE, "expression.toggles")
        }
        return definition(source).copy(version = GloomCarrierAlgorithm.VERSION, plugin = carrier,
            changeWrites = GloomCarrierAlgorithm.WRITES, authorContext = { requireNotNull(source).gloomContext(it, true) })
    }

    private fun RigModifierSource.gloomContext(instance: RigModifierInstance, captureCarriers: Boolean = false): ModifierAlgorithmContext {
        // The scaffold may have a shared hierarchy, but Gloom only writes channels on its own targets.
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val preceding = activeBefore(instance)
        val scaffold = preceding.single { it.id == scaffoldId && it.type == RigSourceModifiers.SCAFFOLD }
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.associate { it.id to layers.getValue(layersByMesh.getValue(it.id)) }
        val scaffoldMeshes = instance.targets.associate { target ->
            val model = scaffoldFor(scaffold, target) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            }
            target.id to model.drawables.single { it.id.raw == target.id }
        }
        val previous = preceding.filter { it.config["scaffold"]?.jsonPrimitive?.content == scaffold.id }
        fun contributions(type: String): Map<String, ModifierUpstreamCapability> = buildMap {
            for (entry in previous.filter { it.type == type || it.type == GloomBindingAlgorithm.ENTRYPOINT && type == GloomBindingAlgorithm.TYPE }) {
                val capability = ModifierUpstreamCapability(type, type, entry.version, entry.targets, entry.id, entry.config,
                    entry.outputAliases, entry.plugin)
                for (target in entry.targets) if (target.id in targets) put(target.id, capability)
            }
        }
        val context = RigGloomSource.capture(instance, RigModules.EXPRESSION_GLOOM.applies(RecipeSubject.of(analysis)), targets, scaffoldMeshes.mapValues { PuppetIr.meshToIr(it.value) },
            contributions("expression.cheek"), contributions(GloomBindingAlgorithm.TYPE))
        val eyeHighlights = previous.filter { it.type in setOf(EyeHighlightAlgorithm.TYPE, EyeHighlightAlgorithm.ENTRYPOINT) }
            .flatMap { it.targets }.distinct().mapNotNull { target ->
                val semantic = targets[target.id]?.semantic ?: return@mapNotNull null
                target.id.takeIf { semantic.tag == SemanticTag.IRIDES && semantic.role == PartRole.HIGHLIGHT }
            }
        val base = ModifierAlgorithmContext(JsonObject(context.values +
            ("eye_highlights" to JsonArray(eyeHighlights.map(::JsonPrimitive)))), context.resources)
        return if (captureCarriers) captureSkinLowering(instance.targets, scaffold, previous, scaffoldMeshes, base) else base
    }

}
