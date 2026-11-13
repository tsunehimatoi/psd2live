package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.CheekV5Algorithm
import io.github.psd2live.algorithms.binding.GloomV5Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV10Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSourceMetadata
import kotlinx.serialization.json.*
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.SourceLayerKind

/** Host-only admission for one fresh source cohort, not a migration of persisted modifier locks. */
internal object WorkspaceSourceSkinDefaults {
    private val previous = mapOf("mouth.spring" to 8, "expression.cheek" to 1, "expression.gloom" to 1)
    private val promoted = mapOf("mouth.spring" to MouthSpringV10Algorithm.VERSION,
        "expression.cheek" to CheekV5Algorithm.VERSION, "expression.gloom" to GloomV5Algorithm.VERSION)

    fun select(model: RigPreviewModel, config: PipelineConfig, stack: RigModifierStack,
               requestedVersions: Map<String, Int>, cliBindings: CliPresetBindings?,
               pristineSourceStack: Boolean, automaticSingleEyeOpenMouth: Boolean): RigModifierStack {
        if (!pristineSourceStack || !automaticSingleEyeOpenMouth || requestedVersions.isNotEmpty() || cliBindings != null ||
            config.generationSource != null || config.meshSource != null || !standardControls(config) ||
            stack.appliedCapabilities.isNotEmpty() || stack.retiredIds.isNotEmpty() || stack.overrides.isNotEmpty() ||
            !stack.organization.isNullOrEmpty()) return stack
        if (previous.any { (type, version) -> stack.instances.singleOrNull { it.type == type }?.version != version }) return stack
        if (stack.instances.filter { it.type in previous }.any { !BuiltinModifierPlugins.matches(it) ||
                !ModifierPluginCatalog.available(it) }) return stack
        // This is the result of sourceSingleEyeCheekLidDefault's exact head-measurement comparison,
        // not the provisional v9 entry in fittedSources. Optional artwork is not an admission test.
        val cheekLid = stack.instances.singleOrNull { it.type == "mouth.cheek-lid" } ?: return stack
        if (cheekLid.version != 9) return stack

        val references = promoted.mapValues { (type, version) ->
            BuiltinModifierPlugins.reference(type, version) ?: return stack
        }
        if (references.any { (type, reference) -> !ModifierPluginCatalog.available(type, promoted.getValue(type), reference) }) return stack
        val candidate = stack.copy(instances = stack.instances.map { entry ->
            promoted[entry.type]?.let { entry.copy(version = it, plugin = references.getValue(entry.type)) } ?: entry
        })
        val spring = candidate.instances.single { it.type == "mouth.spring" }
        val cheek = candidate.instances.single { it.type == "expression.cheek" }
        val gloom = candidate.instances.single { it.type == "expression.gloom" }
        if (candidate.index(cheekLid.id) >= candidate.index(spring.id) ||
            candidate.index(spring.id) >= candidate.index(cheek.id) || candidate.index(cheek.id) >= candidate.index(gloom.id)) return stack
        val scaffold = candidate.instances.singleOrNull { it.type == RigSourceModifiers.SCAFFOLD } ?: return stack
        val targets = scaffold.targets.toSet()
        if (targets.isEmpty() || targets.size != scaffold.targets.size || targets.any { it.kind != "mesh" }) return stack
        val prefix = candidate.instances.take(candidate.index(gloom.id) + 1)
        // Fresh plans use one complete scope. No partial, disabled, aliased or foreign owner is promoted.
        if (prefix.any { !it.enabled || it.targets.size != targets.size || it.targets.toSet() != targets ||
                it.outputAliases.isNotEmpty() || !BuiltinModifierPlugins.matches(it) || !ModifierPluginCatalog.available(it) } ||
            prefix.map { it.type }.distinct().size != prefix.size) return stack
        if (prefix.any { !standardConfiguration(it, prefix, scaffold, config) }) return stack
        if (!providersAdmitted(candidate, spring, MouthSpringV10Algorithm.requiredVersions, MouthSpringV10Algorithm.optionalVersions) ||
            !providersAdmitted(candidate, cheek, CheekV5Algorithm.requiredVersions, CheekV5Algorithm.optionalVersions) ||
            !providersAdmitted(candidate, gloom, GloomV5Algorithm.requiredVersions, GloomV5Algorithm.optionalVersions)) return stack
        if (!sourceAdmitted(model, scaffold, prefix.takeWhile { it.id != spring.id })) return stack
        return candidate
    }

    /** Nondefault control combinations remain explicit choices until separately verified as defaults. */
    private fun standardControls(config: PipelineConfig): Boolean {
        val defaults = PipelineConfig()
        return config.recipe == RigRecipe.Modern && config.rigTuning == RigTuning() &&
            !config.meshOnly && config.generateDeformers &&
            !config.featureDisplacementEnabled && config.headTurnStrength == defaults.headTurnStrength &&
            config.bodyStrength == defaults.bodyStrength && config.mouthOutlineEnabled &&
            config.mouthThickness == defaults.mouthThickness && config.mouthColor == defaults.mouthColor &&
            config.mouthShape == defaults.mouthShape && config.mouthCurve == defaults.mouthCurve &&
            config.seeThroughBrows == defaults.seeThroughBrows && config.seeThroughEyes == defaults.seeThroughEyes &&
            config.seeThroughOpacity == defaults.seeThroughOpacity && !config.hairSimulationFront && !config.hairSimulationBack &&
            config.physicsFrontHair == defaults.physicsFrontHair && config.physicsBackHair == defaults.physicsBackHair &&
            config.physicsEyeJelly == defaults.physicsEyeJelly && config.layerVisibility.isEmpty() &&
            config.deletedLayerIds.isEmpty() && config.drawOrderOverrides.isEmpty()
    }

    /** The worker's exact version inventory, without running a capture or catching its failures. */
    private fun providersAdmitted(stack: RigModifierStack, consumer: RigModifierInstance,
                                  required: Map<String, Int>, optional: Map<String, Set<Int>>): Boolean {
        val preceding = stack.instances.take(stack.index(consumer.id))
        if (required.any { (type, version) -> preceding.singleOrNull { it.type == type }?.version != version }) return false
        val ordered = required.keys.map { type -> preceding.indexOfFirst { it.type == type } }
        if (ordered.zipWithNext().any { (before, after) -> before >= after }) return false
        return preceding.all { owner -> required[owner.type] == owner.version || owner.version in optional[owner.type].orEmpty() }
    }

    /** A fresh plan's exact configuration also proves the optional base/dependency identities. */
    private fun standardConfiguration(entry: RigModifierInstance, prefix: List<RigModifierInstance>,
                                      scaffold: RigModifierInstance, config: PipelineConfig): Boolean {
        if (entry == scaffold) return entry.config == buildJsonObject {
            put("featureDisplacement", false); put("frontHairSway", true); put("backHairSway", true)
        }
        if (scaffold !in prefix.takeWhile { it.id != entry.id }) return false
        val spec = RigLegacyBindings.find(entry.type) ?: return false
        fun provider(type: String): RigModifierInstance? = prefix.takeWhile { it.id != entry.id }
            .singleOrNull { it.type == if (type == "face/legacy") RigLegacyBindings.FITTED_FACE else type }
        val base = spec.module?.base?.let { provider(it) ?: return false }
        val dependencies = spec.related.associateWith { provider(it)?.id ?: return false }
        val expected = buildJsonObject {
            put("scaffold", scaffold.id)
            base?.let { put("base", it.id) }
            if (dependencies.isNotEmpty()) put("dependencies", JsonObject(dependencies.mapValues { JsonPrimitive(it.value) }))
            if (spec.strength) put("strength", 1f)
            if (spec.fields.isNotEmpty()) putJsonObject("tuning") { spec.fields.forEach { put(it.id, it.get(RigTuning())) } }
            if (spec.hasPhysics) put("physicsEnabled", config.generatePhysics)
            if (spec.knobs.isNotEmpty()) putJsonObject("values") {
                spec.knobs.forEach { knob -> put(knob.id, when {
                    spec.seeThrough -> config.seeThroughOpacity * 100f
                    spec.mouthOutline -> config.mouthThickness
                    else -> knob.default
                }) }
            }
        }
        return entry.config == expected
    }

    private fun sourceAdmitted(model: RigPreviewModel, scaffold: RigModifierInstance,
                               preceding: List<RigModifierInstance>): Boolean {
        val source = RigModifierSource.of(model)
        val analysis = source.analysis
        val authored = analysis.source.layers
        // Initial rollout excludes unproved source organization/material/transform combinations.
        // These are source contracts, not shape, filename or density heuristics.
        if (analysis.source.groups.isNotEmpty() || authored.any { layer ->
                val metadata = layer as? WorkspaceSourceMetadata
                layer.groupPath.isNotEmpty() || !layer.visible || layer.kind != SourceLayerKind.Raster ||
                    layer.opacity != 1f || layer.clipped || layer.blend != LayerBlend.Normal || layer.channelMask != ChannelMask.ALL ||
                    metadata?.derived == true || metadata?.rect != null || metadata?.layerTransform != null ||
                    metadata?.sourceSpatialReferenceId != null
            }) return false
        val originals = authored.mapTo(hashSetOf()) { it.id.raw }
        if (originals.size != authored.size || analysis.layers.any { it.source.id.raw !in originals }) return false
        val layers = analysis.layers.associateBy { it.source.id.raw }
        if (layers.size != analysis.layers.size || scaffold.targets.mapTo(hashSetOf()) { it.id } != source.primaryByLayer.values.toSet()) return false
        val selected = scaffold.targets.map { target ->
            val id = source.layersByMesh[target.id] ?: return false
            val layer = layers[id] ?: return false
            if (source.primaryByLayer[id] != target.id) return false
            layer
        }
        if (selected.map { it.source.id.raw }.distinct().size != selected.size ||
            selected.any { it.semantic.type != LayerType.PRESET || it.semantic.parameter.isNotBlank() || it.semantic.variant != null }) return false
        if (!RigCheekModifier.sourceMoodParametersAvailable(source, scaffold)) return false
        val mouthArt = analysis.layers.filter { it.opaquePixels > 0 &&
            it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE) }
        // MOUTH_OPEN-only and multiple mouth drawings have not been proved for automatic selection.
        if (mouthArt.size != 1 || mouthArt.single().semantic.tag != SemanticTag.MOUTH || mouthArt.single().semantic.role != null) return false
        val (native, context) = RigFittedMouthModuleSource.context(source, scaffold,
            preceding.filterNot { it.id == scaffold.id }, RigModules.MOUTH_SPRING)
        val mouth = context.fittedMouth(native) ?: return false
        if (mouth.source == null || mouth.rig == null || mouth.layered || mouth.lipped || mouth.closedLine) return false
        val eyes = context.fittedEyes(native)?.rigs.orEmpty()
        if (eyes.size != 1 || context.fittedBrowMeasurements(native).first.keys != eyes.keys ||
            analysis.layers.none { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.EYELASH && it.semantic.side == eyes.keys.single() }) return false
        // Gloom consumes every selected highlight. EyeHighlight only supplies eye-open opacity
        // for nonempty highlights owned by a measured eye; the other cases keep the old trio.
        val highlights = selected.filter { it.semantic.tag == SemanticTag.IRIDES && it.semantic.role == PartRole.HIGHLIGHT }
        if (highlights.isNotEmpty() && (preceding.none { it.type == "eye.highlight" } ||
                highlights.any { it.opaquePixels == 0 || it.semantic.side !in eyes })) return false
        return selected.any { it.semantic.tag == SemanticTag.FACE && it.semantic.role == null }
    }
}
