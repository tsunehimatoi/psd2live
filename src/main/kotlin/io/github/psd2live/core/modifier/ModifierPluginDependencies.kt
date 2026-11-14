package io.github.psd2live.core.modifier

internal data class ModifierPluginResolution(val dependencies: List<ModifierPluginReference>, val issues: List<String>) {
    val available get() = issues.isEmpty()
}

/** Package dependencies load code; algorithm requires separately orders model-producing stack entries. */
internal object ModifierPluginDependencies {
    fun resolve(reference: ModifierPluginReference, packages: List<InstalledModifierPlugin>): ModifierPluginResolution {
        val resolved = LinkedHashSet<ModifierPluginReference>()
        val issues = LinkedHashSet<String>()
        val path = LinkedHashSet<ModifierPluginReference>()
        val visited = HashSet<ModifierPluginReference>()
        fun visit(entry: InstalledModifierPlugin) {
            if (!path.add(entry.reference)) { issues += "Dependency cycle: " + (path.toList() + entry.reference).joinToString(" -> ") { "${it.id}@${it.version}" }; return }
            if (!entry.enabled) issues += "Disabled plugin: ${entry.reference.id}@${entry.reference.version}"
            for (other in packages.filter { it.enabled && it.reference != entry.reference }) {
                val conflict = entry.manifest.conflicts.any { it.id == other.reference.id && it.version.accepts(other.reference.version) } ||
                    other.manifest.conflicts.any { it.id == entry.reference.id && it.version.accepts(entry.reference.version) }
                if (conflict) issues += "Plugin conflict: ${entry.reference.id}@${entry.reference.version} / ${other.reference.id}@${other.reference.version}"
            }
            if (visited.add(entry.reference)) for (dependency in entry.manifest.dependencies) {
                val candidate = packages.filter { it.enabled && it.reference.id == dependency.id && dependency.version.accepts(it.reference.version) &&
                        (if (entry.dependencyLock != null) it.reference in entry.dependencyLock
                        else !it.reference.id.startsWith("builtin.") || it.reference == BuiltinModifierPlugins.entry(it.reference.id)?.reference) }
                    .maxWithOrNull(compareBy<InstalledModifierPlugin> { ModifierPluginVersion.parse(it.reference.version, legacy = true) }.thenBy { it.reference.version }.thenBy { it.reference.digest })
                if (candidate == null) issues += "Missing dependency: ${entry.reference.id}@${entry.reference.version} requires ${dependency.id} ${dependency.version}"
                else { resolved += candidate.reference; visit(candidate) }
            }
            path.remove(entry.reference)
        }
        val entry = packages.singleOrNull { it.reference == reference }
        if (entry == null) issues += "Missing plugin: ${reference.id}@${reference.version}" else visit(entry)
        return ModifierPluginResolution(resolved.toList(), issues.toList())
    }
}
