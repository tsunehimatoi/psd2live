package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.format.model.MeshGeometry

/**
 * Identity LRU for portable geometry. The budget charges both the retained geometry key (all three
 * buffers) and its encoded value, plus a conservative entry allowance. It is not a JVM heap-size claim.
 * Eviction only drops references: a request already holding a value keeps valid, immutable bytes.
 */
internal class ModifierGeometryCache<V>(val budgetBytes: Long, private val valueBytes: (V) -> Long) {
    init { require(budgetBytes >= 0) }

    private class Key(val geometry: MeshGeometry) {
        override fun equals(other: Any?) = other is Key && geometry === other.geometry
        override fun hashCode() = System.identityHashCode(geometry)
    }
    private data class Entry<V>(val value: V, val bytes: Long)
    data class Snapshot(val budgetBytes: Long, val retainedBytes: Long, val entries: Int,
                        val hits: Long, val misses: Long, val evictions: Long, val oversized: Long)
    private val entries = LinkedHashMap<Key, Entry<V>>(16, .75f, true)
    private var retainedBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L
    private var oversized = 0L

    @Synchronized fun getOrPut(geometry: MeshGeometry, create: () -> V): V {
        val key = Key(geometry)
        entries[key]?.let {
            hits++
            RigBuildProfile.count("plugin geometry cache hits")
            return it.value
        }
        misses++
        RigBuildProfile.count("plugin geometry cache misses")
        val value = create()
        val bytes = geometryBytes(geometry) + valueBytes(value).also { require(it >= 0) } + ENTRY_BYTES
        if (bytes > budgetBytes) {
            oversized++
            RigBuildProfile.count("plugin geometry cache oversized")
            return value
        }
        while (retainedBytes > budgetBytes - bytes) {
            val iterator = entries.entries.iterator()
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
            evictions++
            RigBuildProfile.count("plugin geometry cache evictions")
        }
        entries[key] = Entry(value, bytes)
        retainedBytes += bytes
        return value
    }

    @Synchronized fun clear() { entries.clear(); retainedBytes = 0 }
    @Synchronized fun snapshot() = Snapshot(budgetBytes, retainedBytes, entries.size, hits, misses, evictions, oversized)

    companion object {
        const val DEFAULT_BUDGET_BYTES = 32L * 1024 * 1024
        const val ENTRY_BYTES = 256L
        fun geometryBytes(geometry: MeshGeometry): Long =
            4L * (geometry.positions.size.toLong() + geometry.uvs.size + geometry.indices.size)
    }
}
