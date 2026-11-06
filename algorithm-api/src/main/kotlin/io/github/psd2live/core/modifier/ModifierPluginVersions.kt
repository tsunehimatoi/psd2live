package io.github.psd2live.core.modifier

/** Numeric precedence, prerelease identifiers and build metadata are deliberately separate from package identity. */
@ConsistentCopyVisibility
data class ModifierPluginVersion private constructor(val core: List<java.math.BigInteger>, val pre: List<String>) : Comparable<ModifierPluginVersion> {
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

fun ModifierPluginVersionRange.validate() {
    require(exact == null || minimum == null && maximum == null) { "An exact plugin version cannot include bounds" }
    exact?.let { ModifierPluginVersion.parse(it) }
    val low = minimum?.let { ModifierPluginVersion.parse(it) }
    val high = maximum?.let { ModifierPluginVersion.parse(it) }
    require(low == null || high == null || low < high) { "Empty plugin version range" }
}

fun ModifierPluginVersionRange.accepts(version: String): Boolean {
    val actual = ModifierPluginVersion.parse(version, legacy = true)
    return (exact == null || actual == ModifierPluginVersion.parse(exact)) &&
        (minimum == null || actual >= ModifierPluginVersion.parse(minimum)) &&
        (maximum == null || actual < ModifierPluginVersion.parse(maximum))
}

