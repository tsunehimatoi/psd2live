package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceModifierPlan
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.ModelPresets
import kotlinx.serialization.json.*

internal enum class ModifierConflictSeverity { NONE, SOFT, HARD }

internal data class ModifierConflict(
    val severity: ModifierConflictSeverity,
    val messageKey: String? = null,
) {
    companion object {
        val NONE = ModifierConflict(ModifierConflictSeverity.NONE)
    }
}

internal object ModifierConflictAnalysis {
    private val hairSimIds = setOf(ModelPresets.FRONT_HAIR_SIM, ModelPresets.BACK_HAIR_SIM)
    private val hairPhysicsIds = setOf(PhysicsGenerator.FRONT_HAIR_ID, PhysicsGenerator.BACK_HAIR_ID)
    private val clothingSimIds = ModelPresets.CLOTHING_SIMS.values.toSet()

    private fun RigModifierInstance.simId(): String? = config["id"]?.jsonPrimitive?.contentOrNull
    private fun RigModifierInstance.simKind(): String? = config["kind"]?.jsonPrimitive?.contentOrNull
    private fun RigModifierInstance.motionName(): String? = config["name"]?.jsonPrimitive?.contentOrNull
    private fun RigModifierInstance.physicsOutputs(): Set<String> =
        config["outputs"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["parameter"]?.jsonPrimitive?.contentOrNull }
            .toSet()

    private fun overlaps(a: List<ModifierTarget>, b: List<ModifierTarget>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val set = b.toSet()
        return a.any(set::contains)
    }

    private fun isHairSwayOrPhysics(instance: RigModifierInstance): Boolean =
        instance.type == RigSourceModifiers.HAIR ||
            instance.type in setOf("hair/chains", "hair/legacy") ||
            (instance.type == "physics" && instance.simId() in hairPhysicsIds)

    private fun isHairSimulation(instance: RigModifierInstance): Boolean =
        instance.type == "simulation" && (instance.simId() in hairSimIds || instance.simKind() == "hair")

    private fun isClothingSimulation(instance: RigModifierInstance): Boolean =
        instance.type == "simulation" && (instance.simId() in clothingSimIds || instance.simKind() == "clothing")

    private fun isOutfitSway(instance: RigModifierInstance): Boolean =
        instance.type in setOf("outfit.skirt", "outfit.ribbons")

    private fun hasEyeSquash(instance: RigModifierInstance): Boolean =
        instance.type == "eye.squash" ||
            (instance.type == RigSourceModifiers.EYE && ModifierPartConfig.enabled(instance.config, "squash"))

    fun stackConflicts(
        stack: RigModifierStack,
        reports: Map<String, JsonObject>,
    ): Map<String, ModifierConflict> {
        val registry = RigModifierAlgorithms.registry
        val enabled = stack.instances.filter { it.enabled }
        return stack.instances.associate { instance ->
            instance.id to instanceConflict(instance, enabled, reports, registry)
        }
    }

    private fun instanceConflict(
        instance: RigModifierInstance,
        enabled: List<RigModifierInstance>,
        reports: Map<String, JsonObject>,
        registry: RigModifierRegistry,
    ): ModifierConflict {
        val report = reports[instance.id]
        val status = report?.get("status")?.jsonPrimitive?.contentOrNull
        if (status == "missing_dependency" || status == "missing_plugin") {
            return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.status.$status")
        }
        val overrideIssues = report?.get("override_issues")?.jsonArray.orEmpty()
        if (overrideIssues.any { it.jsonObject["kind"]?.jsonPrimitive?.contentOrNull == "conflict" }) {
            return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.conflict.override")
        }
        if (!instance.enabled) return ModifierConflict.NONE

        val others = enabled.filter { it.id != instance.id }
        val family = registry.defaultDefinition(instance.type)?.exclusiveFamily
        if (family != null && others.any { other ->
                registry.defaultDefinition(other.type)?.exclusiveFamily == family && overlaps(instance.targets, other.targets)
            }) {
            return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.conflict.exclusive")
        }
        if (instance.type == "physics") {
            val outputs = instance.physicsOutputs()
            if (outputs.isNotEmpty() && others.any { other ->
                    other.type == "physics" && other.physicsOutputs().any(outputs::contains)
                }) {
                return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.conflict.physicsOutput")
            }
        }

        // Soft conflicts (functional duplication)
        if ((isHairSwayOrPhysics(instance) && others.any { isHairSimulation(it) && overlaps(instance.targets, it.targets) }) ||
            (isHairSimulation(instance) && others.any { isHairSwayOrPhysics(it) && overlaps(instance.targets, it.targets) })) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.hairSwayAndSim")
        }
        if ((isOutfitSway(instance) && others.any { isClothingSimulation(it) && overlaps(instance.targets, it.targets) }) ||
            (isClothingSimulation(instance) && others.any { isOutfitSway(it) && overlaps(instance.targets, it.targets) })) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.clothingSwayAndSim")
        }
        if ((instance.type == "physics" && instance.simId() == PhysicsGenerator.EYE_JELLY_ID && others.any(::hasEyeSquash)) ||
            (hasEyeSquash(instance) && others.any { it.type == "physics" && it.simId() == PhysicsGenerator.EYE_JELLY_ID })) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.eyeJellyAndSquash")
        }
        if (instance.type == "swing" && others.any {
                (it.type == "swing" || it.type == "simulation") && overlaps(instance.targets, it.targets)
            }) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
        }
        if (instance.type == "simulation" && others.any {
                it.type == "swing" && overlaps(instance.targets, it.targets)
            }) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
        }
        if (instance.type == "motion") {
            val name = instance.motionName()
            if (name != null && others.any { it.type == "motion" && it.motionName() == name }) {
                return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
            }
        }
        return ModifierConflict.NONE
    }

    fun additionConflict(
        model: RigPreviewModel,
        targets: List<ModifierTarget>,
        type: String,
        preset: String? = null,
    ): ModifierConflict {
        val stack = model.config.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: return ModifierConflict.NONE
        val selected = targets.toSet()
        val enabled = stack.instances.filter { it.enabled }
        val existingOnTargets = stack.instances.filter { it.type == type && it.targets.any(selected::contains) }

        if (type == "physics" && preset != null) {
            val rule = WorkspaceModifierPlan.physicsPresets().firstOrNull { it.id == preset }
            if (rule != null) {
                val outputs = rule.outputs.mapTo(HashSet()) { it.parameter }
                val driven = model.rig.modifierOutput?.physics?.groups.orEmpty()
                    .flatMap { it.outputs }
                    .any { it.parameter in outputs }
                val duplicate = existingOnTargets.any { it.simId() == preset }
                if (driven && !duplicate) {
                    return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.conflict.physicsOutput")
                }
            }
        }

        val catalogAvail = ModifierAddCatalog.availability(stack, targets, type)
        if (catalogAvail.hidden) {
            val stored = RigModifierAlgorithms.registry.storedType(type)
            val covered = (stack.instances.filter { it.type == stored }.flatMap { it.targets } +
                stack.appliedCapabilities.filter { it.type == stored }.flatMap { it.targets }).toSet()
            if (!targets.all(covered::contains)) {
                return ModifierConflict(ModifierConflictSeverity.HARD, "modifier.conflict.exclusive")
            }
        }

        // Soft conflicts for presets / modifiers being added
        if (type == "simulation") {
            if (preset in setOf("front_hair", "back_hair") || preset == null) {
                if (enabled.any { isHairSwayOrPhysics(it) && overlaps(targets, it.targets) }) {
                    return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.hairSwayAndSim")
                }
            }
            if (preset == "clothing" && enabled.any { isOutfitSway(it) && overlaps(targets, it.targets) }) {
                return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.clothingSwayAndSim")
            }
            if (preset == null && enabled.any { (it.type == "swing" || it.type == "simulation") && overlaps(targets, it.targets) }) {
                return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
            }
        }
        if (type == RigSourceModifiers.HAIR || (type == "physics" && preset in hairPhysicsIds)) {
            if (enabled.any { isHairSimulation(it) && overlaps(targets, it.targets) }) {
                return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.hairSwayAndSim")
            }
        }
        if (type in setOf("outfit.skirt", "outfit.ribbons") && enabled.any { isClothingSimulation(it) && overlaps(targets, it.targets) }) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.clothingSwayAndSim")
        }
        if (type == "physics" && preset == PhysicsGenerator.EYE_JELLY_ID && enabled.any(::hasEyeSquash)) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.eyeJellyAndSquash")
        }
        if (type == "swing" && enabled.any { (it.type == "swing" || it.type == "simulation") && overlaps(targets, it.targets) }) {
            return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
        }
        if (type == "motion" && preset != null) {
            val clipName = preset.removePrefix(WorkspaceModifierPlan.NATURAL_MOTION_PREFIX)
            if (enabled.any { it.type == "motion" && it.motionName() == clipName }) {
                return ModifierConflict(ModifierConflictSeverity.SOFT, "modifier.conflict.duplicateMotion")
            }
        }
        return ModifierConflict.NONE
    }
}
