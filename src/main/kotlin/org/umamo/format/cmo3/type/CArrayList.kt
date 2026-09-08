package org.umamo.format.cmo3.type

/**
 * A list type that serializes as `<carray_list>` (vs `<array_list>` for a plain ArrayList).
 *
 * The editor uses a distinct list type for most model collections, and the tag
 * distinguishes it on disk, so model fields that are carray_list must use this type to round-trip.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
public class CArrayList<E> private constructor(private val backing: MutableList<E>) : MutableList<E> by backing {
	public constructor() : this(mutableListOf())
	public constructor(initialCapacity: Int) : this(ArrayList(initialCapacity))
	public constructor(elements: Collection<E>) : this(ArrayList(elements))

	// Kotlin's `by` delegation forwards the interface, but NOT equals/hashCode/toString — they would
	// silently fall back to identity, so two equal lists would compare unequal.  Forward them by hand.
	override fun equals(other: Any?): Boolean = backing == other

	override fun hashCode(): Int = backing.hashCode()

	override fun toString(): String = backing.toString()
}