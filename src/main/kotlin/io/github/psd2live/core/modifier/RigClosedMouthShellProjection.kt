package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.core.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Candidate-scoped local residuals for a closed drawing that moves the fallback head shell. */
internal object RigClosedMouthShellProjection {
    fun capture(owner: RigModifierSource, material: PuppetModel, instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = owner.provider(instance)
        val prefix = owner.activeBefore(instance).filter { it.id != scaffold.id && it.targets.any(instance.targets::contains) }
        val (before, old) = RigFittedMouthModuleSource.context(owner, scaffold, prefix, null)
        val canonical = instance.copy(type = "mouth/fitted")
        val (after, next) = RigFittedMouthModuleSource.context(owner, scaffold, prefix + canonical, null)
        val fallback = old.faceTurn(before) == null && next.faceTurn(after) == null
        val captured = RigFittedMouthModernSource.capture(owner, instance, closedDrawing = true, fallbackClosedShell = fallback)
        if (!fallback) return captured
        val layers = next.analysis.layers.associateBy { it.source.id.raw }
        val selected = instance.targets.mapNotNull { target ->
            val layer = layers.getValue(owner.layersByMesh.getValue(target.id))
            if (layer.semantic.tag in CharacterAnalyzer.MOUTH_TAGS ||
                layer.semantic.tag != SemanticTag.HANDWEAR && DepthTurnMotion.shell(layer.semantic.tag.name, layer.semantic.role?.name) == null)
                null else target.id to layer
        }.toMap()
        val oldIds = prefix.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        val nextIds = oldIds + RigLegacyBindings.spec("mouth/fitted").parameters
        val previous = RigBuilder.closedMouthShellProjection(old, before, owner.atlas, owner.meshCache, material, selected, oldIds)
        val desired = RigBuilder.closedMouthShellProjection(next, after, owner.atlas, owner.meshCache, material, selected, nextIds)
        val arms = selected.filterValues { it.semantic.tag == SemanticTag.HANDWEAR }.keys
        return ModifierAlgorithmContext(values = JsonObject(captured.values + ("shellArmTargets" to JsonArray(arms.map(::JsonPrimitive)))),
            resources = captured.resources + mapOf(
                "shellBefore" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(previous))),
                "shellAfter" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(desired))),
            ))
    }
}