package io.github.psd2live.ui.state

import io.github.psd2live.core.modifier.ModifierTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.ModifierAddAvailability
import io.github.psd2live.core.modifier.ModifierAddCatalog
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.application.WorkspaceModifierPlan

/** Process-only dialog state. The input state and target references are captured once, before preview. */
internal data class ModifierAddition(
    val state: String,
    val targets: List<ModifierTarget>,
    val type: String? = null,
    val group: String? = null,
    val preset: String? = null,
    val configuration: JsonObject? = null,
    val plan: JsonObject? = null,
    val preview: JsonObject? = null,
    val loading: Boolean = false,
    val committing: Boolean = false,
    val error: String? = null,
)

internal fun ModifierAddition.availability(model: RigPreviewModel, type: String, preset: String? = null): ModifierAddAvailability {
    val stack = model.config.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: return ModifierAddAvailability(true, 0)
    val result = ModifierAddCatalog.availability(stack, targets, type)
    if (preset == null || result.hidden) return result
    val selected = targets.toSet()
    val existing = stack.instances.filter { it.type == type && it.targets.any(selected::contains) }
    if (type == "physics") {
        val rule = WorkspaceModifierPlan.physicsPresets().firstOrNull { it.id == preset } ?: return result
        val outputs = rule.outputs.mapTo(HashSet()) { it.parameter }
        val driven = model.rig.modifierOutput?.physics?.groups.orEmpty().flatMap { it.outputs }.any { it.parameter in outputs }
        val duplicate = existing.any { it.config["id"]?.jsonPrimitive?.content == preset }
        return result.copy(hidden = driven || duplicate)
    }
    if (type == "motion") {
        val natural = preset.startsWith(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)
        return result.copy(existingCount = existing.count {
            it.config["name"]?.jsonPrimitive?.content == preset.removePrefix(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX) &&
                (it.config["id"]?.jsonPrimitive?.content?.startsWith(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX) == true) == natural
        })
    }
    if (type == "simulation") {
        val ids = when (preset) {
            "front_hair" -> setOf(io.github.psd2live.core.sim.ModelPresets.FRONT_HAIR_SIM)
            "back_hair" -> setOf(io.github.psd2live.core.sim.ModelPresets.BACK_HAIR_SIM)
            "clothing" -> io.github.psd2live.core.sim.ModelPresets.CLOTHING_SIMS.values.toSet()
            else -> emptySet()
        }
        return result.copy(existingCount = existing.count { it.config["id"]?.jsonPrimitive?.content in ids })
    }
    return result
}
