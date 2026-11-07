package io.github.psd2live.core.modifier

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Source measurements for a plugin that changes only selected meshes in shared binding frames. */
internal fun RigModifierSource.captureBindingTransition(
    instance: RigModifierInstance,
    scaffold: RigModifierInstance,
    preceding: List<RigModifierInstance>,
    capturedTags: Set<SemanticTag>,
    projectedTags: Set<SemanticTag> = capturedTags,
    values: JsonObject,
): ModifierAlgorithmContext {
    val (beforeConfig, before) = RigFittedMouthModuleSource.context(this, scaffold, preceding, null)
    val (afterConfig, after) = RigFittedMouthModuleSource.context(this, scaffold, preceding + instance, null)
    val beforeLayers = bindingSourceLayers(instance, before) { it.semantic.tag in capturedTags }
    val afterLayers = bindingSourceLayers(instance, after) { it.semantic.tag in capturedTags }
    require(beforeLayers.keys == afterLayers.keys) { "Source targets changed while projecting ${instance.type}" }
    val projected = beforeLayers.filterValues { it.semantic.tag in projectedTags }.keys
    require(projected.isNotEmpty()) { "${instance.type} needs source meshes in its captured frame" }
    val axes = preceding.flatMapTo(HashSet()) { RigLegacyBindings.spec(it.type).parameters }
    return ModifierAlgorithmContext(values, mapOf(
        "before" to outlineShellSource(before, beforeConfig, beforeLayers, axes, includeFaceLean = true, projectedMeshes = projected),
        "after" to outlineShellSource(after, afterConfig, afterLayers, axes + RigLegacyBindings.spec(instance.type).parameters,
            includeFaceLean = true, projectedMeshes = projected),
    ))
}

internal fun RigModifierSource.bindingPredecessors(instance: RigModifierInstance, scaffold: RigModifierInstance): List<RigModifierInstance> =
    activeBefore(instance).filter { entry ->
        entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
            RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
    }

internal fun RigModifierSource.bindingSourceLayers(
    instance: RigModifierInstance, context: RigBuilder.RigContext, accept: (ClassifiedLayer) -> Boolean = { true },
): Map<String, ClassifiedLayer> {
    val layers = context.analysis.layers.associateBy { it.source.id.raw }
    return instance.targets.mapNotNull { target ->
        val layer = layers[layersByMesh[target.id]] ?: return@mapNotNull null
        (target.id to layer).takeIf { layer.source.id.raw in atlas.placementByLayerId && accept(layer) }
    }.toMap()
}

internal val eyeBindingTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
