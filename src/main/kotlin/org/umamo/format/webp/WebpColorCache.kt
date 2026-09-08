// Ported from TwelveMonkeys imageio-webp lossless ColorCache (BSD-3-Clause, Copyright (c) 2017 Harald
// Kuhr).  VP8L hash color cache; see CREDITS.md.

package org.umamo.format.webp

private const val HASH_MULTIPLIER = 0x1e35a7bdL

/**
 * The VP8L color cache: a small hash table of recently emitted ARGB pixels, indexed by a fixed
 * multiplicative hash of the packed ARGB value.
 */
internal class WebpColorCache(hashBits: Int) {
	private val colors = IntArray(1 shl hashBits)
	private val hashShift = 32 - hashBits

	/**
	 * Returns the ARGB stored at cache slot [key].
	 *
	 * @param Int key The cache index.
	 * @return Int The packed ARGB value.
	 */
	fun lookup(key: Int): Int = colors[key]

	/**
	 * Inserts a packed ARGB value at its hashed slot.
	 *
	 * @param Int argb The packed ARGB value.
	 */
	fun insert(argb: Int) {
		colors[hashIndex(argb)] = argb
	}

	/**
	 * The cache slot for a packed ARGB value.
	 *
	 * @param Int argb The packed ARGB value.
	 * @return Int The slot index.
	 */
	private fun hashIndex(argb: Int): Int = (((argb.toLong() and 0xFFFFFFFFL) * HASH_MULTIPLIER) and 0xFFFFFFFFL).ushr(hashShift).toInt()
}