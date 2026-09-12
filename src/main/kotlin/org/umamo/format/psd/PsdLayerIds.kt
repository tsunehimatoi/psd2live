package org.umamo.format.psd

/** Reserves all source IDs before assigning replacements, including IDs encountered later. */
internal class PsdLayerIds(ids: Iterable<Int>) {
	private val reserved = ids.toHashSet()
	private val used = HashSet<Int>()
	private var next = 1

	fun allocate(preferred: Int?): Int {
		if (preferred != null && used.add(preferred)) return preferred
		while (next in reserved || next in used) next++
		return next.also { used.add(it); next++ }
	}
}
