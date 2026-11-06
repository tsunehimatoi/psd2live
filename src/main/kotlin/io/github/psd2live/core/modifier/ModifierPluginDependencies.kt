package io.github.psd2live.core.modifier

/** Numeric precedence, prerelease identifiers and build metadata are deliberately separate from package identity. */
@ConsistentCopyVisibility
internal data class ModifierPluginVersion private constructor(val core: List<java.math.BigInteger>, val pre: List<String>) : Comparable<ModifierPluginVersion> {
    override fun compareTo(other: ModifierPluginVersion): Int {
        core.zip(other.core).forEach { (a, b) -> a.compareTo(b).takeIf { it != 0 }?.let { return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) return when { pre == other.pre -> 0; pre.isEmpty() -> 1; else -> -1 }
        pre.zip(other.pre).forEach { (a, b) ->
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val order = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> a.compareTo(b) }
            if (order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(text: String, legacy: Boolean = false): ModifierPluginVersion {
            val value = if (legacy && text.matches(Regex("0|[1-9][0-9]*"))) "$text.0.0" else text
            val match = requireNotNull(Regex("(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:[+]([0-9A-Za-z.-]+))?").matchEntire(value)) { "Invalid plugin version: $text; expected major.minor.patch" }
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            val build = match.groupValues[5].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            require((pre + build).none { it.isEmpty() } && pre.none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) { "Invalid plugin version identifiers: $text" }
            return ModifierPluginVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

internal fun ModifierPluginVersionRange.validate() {
    require(exact == null || minimum == null && maximum == null) { "An exact plugin version cannot include bounds" }
    exact?.let { ModifierPluginVersion.parse(it) }
    val low = minimum?.let { ModifierPluginVersion.parse(it) }
    val high = maximum?.let { ModifierPluginVersion.parse(it) }
    require(low == null || high == null || low < high) { "Empty plugin version range" }
}

internal fun ModifierPluginVersionRange.accepts(version: String): Boolean {
    val actual = ModifierPluginVersion.parse(version, legacy = true)
    return (exact == null || actual == ModifierPluginVersion.parse(exact)) &&
        (minimum == null || actual >= ModifierPluginVersion.parse(minimum)) &&
        (maximum == null || actual < ModifierPluginVersion.parse(maximum))
}

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
                        (entry.dependencyLock == null || it.reference in entry.dependencyLock) }
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
