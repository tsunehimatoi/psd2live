package io.github.psd2live.core.modifier

/** A cheap catalog projection; insertion still performs the authoritative dependency and write validation. */
internal data class ModifierAddAvailability(val hidden: Boolean, val existingCount: Int)

internal object ModifierAddCatalog {
    fun availability(stack: RigModifierStack, targets: List<ModifierTarget>, type: String): ModifierAddAvailability {
        val registry = RigModifierAlgorithms.registry
        val definition = registry.defaultDefinition(type)
            ?: return ModifierAddAvailability(true, 0)
        if (definition.pluginReference == null && !(BuiltinModifierPlugins.reference(type, definition.version)?.let { ModifierPluginStore.shared.builtinEnabled(it.id) }
                ?: ModifierPluginStore.shared.builtinsEnabled()) || definition.pluginReference != null && ModifierPluginCatalog.algorithm(type) == null)
            return ModifierAddAvailability(true, 0)
        val selected = targets.toSet()
        val existing = stack.instances.filter { entry -> entry.targets.any(selected::contains) }
        val applied = stack.appliedCapabilities.filter { entry -> entry.targets.any(selected::contains) }
        val storedType = registry.storedType(type)
        val count = existing.count { it.type == storedType } + applied.count { it.type == storedType }
        val source = storedType == RigSourceModifiers.SCAFFOLD || RigLegacyBindings.find(storedType) != null
        // A disabled instance remains editable and should be re-enabled instead of duplicated.
        val covered = (existing.filter { it.type == storedType }.flatMap { it.targets } +
            applied.filter { it.type == storedType }.flatMap { it.targets }).toSet()
        if ((source || definition.exclusiveFamily != null) && targets.all(covered::contains)) return ModifierAddAvailability(true, count)
        val active = existing.filter { it.enabled }.map { it.capability() } + applied
        fun conflicts(wanted: String, target: ModifierTarget, visited: MutableSet<String>): Boolean {
            if (!visited.add(wanted)) return false
            val providers = active.filter { target in it.targets }
            if (providers.any { registry.provides(it, wanted) }) return false
            val requirement = registry.defaultDefinition(wanted) ?: return false
            if (requirement.exclusiveFamily != null && providers.any { present ->
                    registry.definitions.any { it.type == present.type && it.exclusiveFamily == requirement.exclusiveFamily }
                }) return true
            return requirement.requires.any { conflicts(it, target, visited) }
        }
        val familyConflict = definition.exclusiveFamily != null && active.any { present ->
            present.type != storedType && registry.definitions.any { it.type == present.type && it.exclusiveFamily == definition.exclusiveFamily }
        }
        return ModifierAddAvailability(familyConflict || targets.any { target ->
            definition.requires.any { conflicts(it, target, HashSet()) }
        }, count)
    }
}
