package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigTuning
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Preceding binding instances indexed for native config assembly.
 *
 * A later instance replaces an earlier one of the same type or base area, tuning follows
 * [RigLegacyBindings.specs] order, and a fitted face supplies the head strength in preference
 * to a legacy face.
 */
internal class RigSourceBindingState(instances: List<RigModifierInstance>) {
    private val normalized: List<RigModifierInstance> = instances.flatMap { expandPart(it) }
    val byType: Map<String, RigModifierInstance> = normalized.associateBy { it.type }
    val byArea: Map<String, RigModifierInstance> by lazy {
        normalized.filter { RigLegacyBindings.find(it.type)?.isBase == true }
            .associateBy { RigLegacyBindings.spec(it.type).area }
    }
    val tuning: RigTuning by lazy {
        RigLegacyBindings.specs.fold(RigTuning()) { current, spec ->
            byType[spec.type]?.let { RigLegacyBindings.tuning(it, current) } ?: current
        }
    }
    val bodyStrength: Float by lazy { byType["body/legacy"]?.let(RigLegacyBindings::strength) ?: 1f }
    val headStrength: Float by lazy {
        (byType[RigLegacyBindings.FITTED_FACE] ?: byType["face/legacy"])?.let(RigLegacyBindings::strength) ?: 1f
    }

    companion object {
        private val partBases = mapOf(
            "eye" to ("eye/fitted" to "eye/legacy"),
            "brow" to ("brow/fitted" to "brow/legacy"),
            "mouth" to ("mouth/fitted" to "mouth/legacy"),
            "face" to ("face/fitted" to "face/legacy"),
            "body" to ("body/legacy" to "body/legacy"),
            "hair" to ("hair/chains" to "hair/legacy"),
        )
        internal fun expandPart(instance: RigModifierInstance): List<RigModifierInstance> {
            val bases = partBases[instance.type] ?: return listOf(instance)
            val mode = instance.config["mode"]?.jsonPrimitive?.content ?: "fitted"
            val baseType = if (mode == "legacy") bases.second else bases.first
            val baseConfig = buildJsonObject {
                instance.config["scaffold"]?.let { put("scaffold", it) }
                instance.config["tuning"]?.let { put("tuning", it) }
                instance.config["values"]?.let { put("values", it) }
                instance.config["strength"]?.let { put("strength", it) }
                instance.config["physics"]?.let { put("physics", it) }
                instance.config["physicsEnabled"]?.let { put("physicsEnabled", it) }
            }
            val result = mutableListOf(instance.copy(type = baseType, config = baseConfig))
            for ((flag, subType) in ModifierPartConfig.features[instance.type].orEmpty()) {
                if (ModifierPartConfig.enabled(instance.config, flag)) {
                    result += ModifierPartConfig.child(instance, subType)
                }
            }
            return result
        }
    }
}
