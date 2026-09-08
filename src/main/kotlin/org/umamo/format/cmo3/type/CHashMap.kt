package org.umamo.format.cmo3.type

/**
 * A map that serializes as `<hash_map>` but preserves insertion/read order. The editor writes
 * hash_map entries in JVM-hash order; reading into a plain HashMap and re-emitting would reorder
 * them (Enum/object keys hash by identity), breaking byte-identity. Reading into this order-keeping
 * type re-emits entries in their original document order.
 *
 * Delegates to a backing LinkedHashMap rather than extending it, for the same reason as [CArrayList]:
 * `kotlin.collections.LinkedHashMap` is a typealias to the open java.util class on JVM but is FINAL
 * on Kotlin/Native.  The backing map is still a LinkedHashMap, so the insertion-order guarantee this
 * type exists for is unchanged.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
public class CHashMap<K, V> private constructor(private val backing: MutableMap<K, V>) : MutableMap<K, V> by backing {
	public constructor() : this(LinkedHashMap())

	// As in CArrayList: `by` does not forward these, and identity equality would be a silent trap.
	override fun equals(other: Any?): Boolean = backing == other

	override fun hashCode(): Int = backing.hashCode()

	override fun toString(): String = backing.toString()
}