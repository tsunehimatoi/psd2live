package io.github.psd2live.core.modifier

/** A cheap catalog projection; insertion still performs the authoritative dependency and write validation. */
internal data class ModifierAddAvailability(val hidden: Boolean, val existingCount: Int)

internal object ModifierAddCatalog {
    fun availability(stack: RigModifierStack, targets: List<ModifierTarget>, type: String): ModifierAddAvailability {
        val registry = RigModifierAlgorithms.registry
        val definition = registry.definitions.filter { it.type == type }.maxByOrNull { it.version }
            ?: return ModifierAddAvailability(true, 0)
        val selected = targets.toSet()
        val existing = stack.instances.filter { entry -> entry.targets.any(selected::contains) }
        val applied = stack.appliedCapabilities.filter { entry -> entry.targets.any(selected::contains) }
        val count = existing.count { it.type == type } + applied.count { it.type == type }
        val source = type == RigSourceModifiers.SCAFFOLD || RigLegacyBindings.find(type) != null
        // A disabled instance remains editable and should be re-enabled instead of duplicated.
        val covered = (existing.filter { it.type == type }.flatMap { it.targets } +
            applied.filter { it.type == type }.flatMap { it.targets }).toSet()
        if (source && targets.all(covered::contains)) return ModifierAddAvailability(true, count)
        val activeEntries = existing.filter { it.enabled }.map { it.type to it.targets } + applied.map { it.type to it.targets }
        val active = activeEntries.map { it.first }
        fun conflicts(wanted: String, target: ModifierTarget, visited: MutableSet<String>): Boolean {
            if (!visited.add(wanted)) return false
            val providers = activeEntries.filter { target in it.second }.map { it.first }
            if (providers.any { registry.provides(it, wanted) }) return false
            val requirement = registry.definitions.filter { it.type == wanted }.maxByOrNull { it.version } ?: return false
            if (requirement.exclusiveFamily != null && providers.any { present ->
                    registry.definitions.any { it.type == present && it.exclusiveFamily == requirement.exclusiveFamily }
                }) return true
            return requirement.requires.any { conflicts(it, target, visited) }
        }
        val familyConflict = definition.exclusiveFamily != null && active.any { present ->
            present != type && registry.definitions.any { it.type == present && it.exclusiveFamily == definition.exclusiveFamily }
        }
        return ModifierAddAvailability(familyConflict || targets.any { target ->
            definition.requires.any { conflicts(it, target, HashSet()) }
        }, count)
    }
}
