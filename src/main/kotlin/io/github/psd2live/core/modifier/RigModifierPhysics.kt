package io.github.psd2live.core.modifier

import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.RigPhysicsEdit
import kotlinx.serialization.json.*
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR

/** Module pendulums use the existing algorithm; compatible shared standard outputs retain their first identity. */
internal object RigModifierPhysics {
    /** Chain slots come from actual evaluated groups; their native ordinals are intentionally not public IDs. */
    fun entries(instance: RigModifierInstance, rig: RigIR): Map<String, PhysicsGroup> {
        if (RigModifierChains.kinds(instance.type).isEmpty()) return requested(instance, rig, emptyList())
        return rig.physics.groups.mapNotNull { group ->
            val local = instance.localOutputName(group.id)?.takeIf { it.startsWith("physics:chain:") } ?: return@mapNotNull null
            local.removePrefix("physics:") to group
        }.toMap()
    }

    private fun requested(instance: RigModifierInstance, rig: RigIR, chains: List<RigModifierChains.Owned>): Map<String, PhysicsGroup> {
        val spec = RigLegacyBindings.spec(instance.type)
        if (!spec.hasPhysics) return emptyMap()
        val fixed = spec.module?.physicsGroups.orEmpty().toSet()
        val available = rig.parameters.mapTo(HashSet()) { it.id }
        val softness = if (instance.type == "hair/chains") (RigLegacyBindings.values(instance)["softness"] ?: 50f) / 100f else .5f
        val rules = LinkedHashMap<String, RigPhysicsEdit>()
        PhysicsGenerator.moduleRules(available).filter { it.id in fixed }.forEach { rules[it.id] = it }
        for (chain in chains) {
            val aliases = chain.aliases
            for (rule in PhysicsGenerator.moduleRules(available + aliases.keys, softness).filter {
                it.outputs.isNotEmpty() && it.outputs.all { output -> output.parameter in aliases }
            }) {
                val slot = "chain:${chain.binding.key}:${chain.binding.suffix(rule.outputs.first().parameter)}"
                require(slot !in rules) { "${instance.id}: duplicate chain physics slot $slot" }
                rules[slot] = rule.copy(inputs = rule.inputs.map { it.copy(parameter = aliases[it.parameter] ?: it.parameter) },
                    outputs = rule.outputs.map { it.copy(parameter = aliases.getValue(it.parameter)) })
            }
        }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it in rules }) { "${instance.id}: unknown or unavailable module physics group" }
        return rules.mapValues { (slot, native) ->
            val edit = overrides[slot]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: native
            val issue = PhysicsCatalog.issueOf(edit, available)
            require(issue == null) { "${instance.id}: invalid physics $slot: $issue" }
            Physics3Json.group(edit.copy(id = instance.outputId("physics:$slot")))
        }
    }

    private fun compatible(existing: PhysicsGroup, requested: PhysicsGroup) =
        existing.copy(id = requested.id, name = requested.name) == requested

    private fun reused(rig: RigIR, group: PhysicsGroup) = rig.physics.groups.firstOrNull { compatible(it, group) }

    fun add(rig: RigIR, instance: RigModifierInstance, chains: List<RigModifierChains.Owned>): RigIR {
        var next = rig
        for (group in requested(instance, rig, chains).values) {
            if (reused(next, group) != null) continue
            require(next.physics.groups.none { it.id == group.id }) { "${instance.id}: physics group ID collision ${group.id}" }
            val outputs = group.outputs.mapTo(HashSet()) { it.parameter }
            val conflict = next.physics.groups.firstOrNull { it.outputs.any { output -> output.parameter in outputs } }
            require(conflict == null) { "${instance.id}: incompatible physics outputs with ${conflict?.id}: $outputs" }
            next = next.copy(physics = next.physics.copy(groups = next.physics.groups + group))
        }
        return next
    }

    fun addExpressionInputs(rig: RigIR, owners: List<RigModifierInstance>, parameters: List<String>): RigIR {
        val live = rig.parameters.mapTo(HashSet()) { it.id }
        val inputs = io.github.psd2live.core.fit.HairChain.expressionInputs(parameters.filter { it in live }.distinct().sorted()).map {
            io.github.psd2live.format.model.PhysicsInput(it.parameter, it.weight, io.github.psd2live.format.model.PhysicsSource.X, it.reflect)
        }
        if (inputs.isEmpty()) return rig
        val chainOwners = owners.filter { RigModifierChains.kinds(it.type).isNotEmpty() }
        val groups = rig.physics.groups.map { group ->
            val slot = chainOwners.firstNotNullOfOrNull { it.localOutputName(group.id) }
            if (slot?.startsWith("physics:chain:") != true || slot.endsWith(":Y")) return@map group
            val existing = group.inputs.groupBy { it.parameter }
            for (input in inputs) require(existing[input.parameter].orEmpty().all { it == input }) {
                "Incompatible expression input ${input.parameter} in physics group ${group.id}"
            }
            group.copy(inputs = group.inputs + inputs.filter { it.parameter !in existing })
        }
        return rig.copy(physics = rig.physics.copy(groups = groups))
    }

    fun references(before: RigIR, after: RigIR, instance: RigModifierInstance) = entries(instance, after).mapNotNull { (slot, requested) ->
        reused(before, requested)?.let { ModifierContribution("physics", it.id, ModifierChange.REUSED, physicsSlot = slot) }
    }
}
